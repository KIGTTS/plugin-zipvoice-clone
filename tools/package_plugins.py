"""Build signed KIGTTS packages without touching the host or its publisher keys."""
import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import zipfile
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from bundled_zipvoice import PREFIX, WEIGHTS, file_sha256, stage_model, verify_model

ROOT = Path(__file__).resolve().parent.parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def dex_classes(data):
    strings_size, strings_off, types_size, types_off = struct.unpack_from("<4I", data, 56)
    classes_size, classes_off = struct.unpack_from("<2I", data, 96)
    def string(index):
        offset = struct.unpack_from("<I", data, strings_off + index * 4)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        return data[offset:data.index(0, offset)].decode("utf-8")
    result = []
    for index in range(classes_size):
        class_index = struct.unpack_from("<I", data, classes_off + index * 32)[0]
        string_index = struct.unpack_from("<I", data, types_off + class_index * 4)[0]
        result.append(string(string_index))
    return result


def verify(path):
    with zipfile.ZipFile(path) as archive:
        raw = archive.read("plugin.json")
        manifest = json.loads(raw)
        public = archive.read("signature/public.der")
        key = serialization.load_der_public_key(public)
        key.verify(archive.read("signature/manifest.sig"), raw, padding.PKCS1v15(), hashes.SHA256())
        files = {name for name in archive.namelist() if not name.endswith("/")} - {"plugin.json", "signature/public.der", "signature/manifest.sig"}
        assert files == set(manifest["files"])
        model = verify_model(archive, manifest)
        for name in files:
            with archive.open(name) as source:
                assert hashlib.file_digest(source, "sha256").hexdigest() == manifest["files"][name], name
            assert not name.endswith((".gguf", ".onnx", ".wav", ".der")) or (
                model and name in {PREFIX + weight for weight in WEIGHTS})
        classes = []
        for name in sorted(files):
            if name.endswith(".dex"):
                classes += dex_classes(archive.read(name))
        assert "L" + manifest["entryClass"].replace(".", "/") + ";" in classes
        assert not any(name.startswith(("Lcom/kigtts/plugin/api/", "Lcom/kigtts/plugin/android/", "Lkotlin/", "Lkotlinx/")) for name in classes)
        assert len(classes) == len(set(classes))
        report = {"file": path.name, "sha256": file_sha256(path), "bytes": path.stat().st_size, "version": manifest["version"],
                  "id": manifest["id"], "apiVersion": manifest["apiVersion"], "publisherSha256": sha(public),
                  "payloadFiles": len(files), "dexClassCount": len(classes), "signatureVerified": True,
                  "hostApiBundled": False, "modelsBundled": bool(model), "native": [], **model}
        for name in sorted(files):
            if name.endswith(".so"):
                data = archive.read(name)
                assert data[:5] == b"\x7fELF\x02"
                phoff = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                alignments = [struct.unpack_from("<Q", data, phoff + i * size + 48)[0]
                              for i in range(count) if struct.unpack_from("<I", data, phoff + i * size)[0] == 1]
                report["native"].append({"file": name, "loadAlignments": alignments, "aligned16k": min(alignments) >= 16384})
        return report


def build(module, sdk, java, key, force, qualcomm=False, segment_probe=False, cpu_probe=False):
    if segment_probe and (module != "zipvoice" or not qualcomm):
        raise ValueError("The segmentation probe requires the patched ZipVoice runtime")
    spec_dir = ROOT / ("qualcomm/segment-probe" if segment_probe else module)
    spec = json.loads((spec_dir / "plugin-spec.json").read_text(encoding="utf-8"))
    if module != "zipvoice":
        raise ValueError("Only the CPU voice cloning engine is distributed")
    # The Android JNI is built in the pinned wrapper; shipping includes only its CPU dependencies.
    qualcomm = True
    api = int(re.search(r"const val PLUGIN_API_VERSION = (\d+)", (ROOT / "sdk/plugin-api/src/main/kotlin/com/kigtts/plugin/api/Plugin.kt").read_text()).group(1))
    assert spec["apiVersion"] == api
    destination = ROOT / "dist" / f'{spec["id"]}-{spec["version"]}.kigplugin'
    destination.parent.mkdir(exist_ok=True)
    if destination.exists() and not force:
        raise FileExistsError(f"{destination} already exists; use --force for a local development rebuild")
    work = ROOT / "build/package-work" / ("segment-probe" if segment_probe else module)
    if work.exists():
        assert work.resolve().is_relative_to((ROOT / "build/package-work").resolve())
        shutil.rmtree(work)
    payload = work / "payload"
    (payload / "code").mkdir(parents=True)
    jars = [ROOT / "clone-core/build/libs/clone-core.jar"]
    aars = [ROOT / module / f"build/outputs/aar/{module}-debug.aar"]
    if segment_probe:
        aars.append(spec_dir / "build/outputs/aar/segment-probe-debug.aar")
    if module == "zipvoice":
        library = ROOT / "zipvoice/libs/sherpa-onnx-2.0.1.aar"
        assert sha(library.read_bytes()) == "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
        aars.append(library)
    for index, aar in enumerate(aars):
        with zipfile.ZipFile(aar) as archive:
            jar = work / f"input-{index}.jar"
            jar.write_bytes(archive.read("classes.jar"))
            jars.append(jar)
            for name in archive.namelist():
                if name.startswith("jni/arm64-v8a/") and name.endswith(".so"):
                    if module == "zipvoice" and qualcomm:
                        continue
                    target = payload / "lib/arm64-v8a" / Path(name).name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if target.exists():
                        raise ValueError("Duplicate native library: " + target.name)
                    target.write_bytes(archive.read(name))
    if module == "zipvoice" and qualcomm:
        native = payload / "lib/arm64-v8a"
        native.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "qualcomm/build/sherpa-native/lib/libsherpa-onnx-jni.so", native / "libsherpa-onnx-jni.so")
        strip = sdk / "ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe"
        subprocess.run([str(strip), "--strip-unneeded", str(native / "libsherpa-onnx-jni.so")], check=True)
        names = ["libonnxruntime.so"]
        if segment_probe and not cpu_probe:
            names += ["libonnxruntime_providers_qnn.so", "libQnnGpu.so"]
        for name in names:
            shutil.copy2(ROOT / "qualcomm/native/arm64-v8a" / name, native / name)
    classpath = [ROOT / "sdk/plugin-api/build/libs/plugin-api.jar"]
    cache_roots = [ROOT / ".gradle-home/caches/modules-2/files-2.1", Path.home() / ".gradle/caches/modules-2/files-2.1"]
    for group, artifact, version in [("org.jetbrains.kotlin", "kotlin-stdlib", "2.2.21"),
                                     ("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
                                     ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", "1.9.0"),
                                     ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", "1.9.0")]:
        candidates = [file for cache in cache_roots for file in (cache / group / artifact / version).glob("*/*.jar")]
        if candidates:
            classpath.append(candidates[0])
    arguments = [str(java), "-cp", str(sdk / "build-tools/36.0.0/lib/d8.jar"), "com.android.tools.r8.D8", "--release", "--min-api", "26",
                 "--lib", str(sdk / "platforms/android-36/android.jar"), "--output", str(payload / "code")]
    for file in classpath:
        arguments += ["--classpath", str(file)]
    subprocess.run(arguments + list(map(str, jars)), check=True)
    shutil.copytree(ROOT / "licenses" / "common", payload / "assets/licenses")
    shutil.copytree(ROOT / "licenses" / module, payload / "assets/licenses", dirs_exist_ok=True)
    if module == "zipvoice":
        for file in (ROOT / "qualcomm/licenses").rglob("*"):
            if not segment_probe and file.name not in {"ort-LICENSE.txt", "ort-ThirdPartyNotices.txt"}:
                continue
            if file.is_file() and (file.suffix.lower() == ".pdf" or "LICENSE" in file.name or "NOTICE" in file.name or "Notices" in file.name):
                target = payload / "assets/licenses/qualcomm" / file.relative_to(ROOT / "qualcomm/licenses")
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(file, target)
        if segment_probe:
            shutil.copy2(ROOT / "qualcomm/artifacts.json", payload / "assets/licenses/QNN-ARTIFACTS.json")
    shutil.copy2(ROOT / "LICENSE", payload / "assets/licenses/PLUGIN-LICENSE.txt")
    shutil.copy2(ROOT / "NOTICE.md", payload / "assets/licenses/NOTICE.md")
    if module == "zipvoice" and not segment_probe:
        stage_model(ROOT / "test-assets/zipvoice", payload)
    spec["abis"] = ["arm64-v8a"]
    spec["files"] = {file.relative_to(payload).as_posix(): file_sha256(file) for file in sorted(payload.rglob("*")) if file.is_file()}
    raw = json.dumps(spec, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    (payload / "plugin.json").write_bytes(raw)
    signature = payload / "signature"
    signature.mkdir()
    (signature / "public.der").write_bytes(key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
    (signature / "manifest.sig").write_bytes(key.sign(raw, padding.PKCS1v15(), hashes.SHA256()))
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in sorted(payload.rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(payload).as_posix())
    report = verify(destination)
    if not segment_probe or cpu_probe:
        assert {Path(x["file"]).name for x in report["native"]} == {"libsherpa-onnx-jni.so", "libonnxruntime.so"}
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "C:/Users/LHT02/AppData/Local/Android/Sdk")))
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java")))
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--module", choices=["zipvoice"])
    parser.add_argument("--qualcomm", action="store_true")
    parser.add_argument("--segment-probe", action="store_true", help="Build only the isolated CPU trial")
    parser.add_argument("--cpu-only-probe", action="store_true", help="Exclude GPU libraries from the isolated probe")
    args = parser.parse_args()
    if args.cpu_only_probe and not args.segment_probe:
        parser.error("--cpu-only-probe requires --segment-probe")
    if args.segment_probe:
        if args.module not in (None, "zipvoice"):
            parser.error("--segment-probe only targets ZipVoice")
        args.module, args.qualcomm = "zipvoice", True
    secret = ROOT / ".signing/development-private.der"
    if secret.exists():
        key = serialization.load_der_private_key(secret.read_bytes(), password=None)
    else:
        key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
        secret.parent.mkdir(exist_ok=True)
        secret.write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    reports = [build(module, args.android_sdk, args.java, key, args.force, args.qualcomm, args.segment_probe, args.cpu_only_probe) for module in ([args.module] if args.module else ["zipvoice"])]
    report_path = ROOT / "dist/package-verification.json"
    if args.segment_probe:
        report_path = ROOT / "qualcomm/build/segment-probe-verification.json"
    report_path.write_text(json.dumps(reports, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(reports, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
