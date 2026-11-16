"""Fetch reviewed public Maven artifacts for an isolated Qualcomm adaptation build."""
import argparse
import concurrent.futures
import hashlib
import json
import urllib.request
import urllib.error
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "qualcomm"
BASE = "https://repo.maven.apache.org/maven2/"
PACKAGES = [
    ("com.microsoft.onnxruntime", "onnxruntime-android", "1.29.0"),
    ("com.qualcomm.qti", "onnxruntime-android-qnn", "2.7.0"),
    ("com.qualcomm.qti", "qnn-runtime", "2.51.0"),
]


def fetch(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.is_file():
        temporary = target.with_suffix(target.suffix + ".part")
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as output:
            while block := response.read(1024 * 1024):
                output.write(block)
        temporary.replace(target)
    return target.read_bytes()


def artifact(item, binary):
    group, name, version = item
    stem = f"{name}-{version}"
    url = BASE + f"{group.replace('.', '/')}/{name}/{version}/{stem}"
    folder = ROOT / "downloads" / f"{group}-{name}-{version}"
    pom = fetch(url + ".pom", folder / (stem + ".pom"))
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    tree = ET.fromstring(pom)
    dependencies = [dict((field, dep.findtext("m:" + field, namespaces=namespace)) for field in ["groupId", "artifactId", "version"]) for dep in tree.findall("./m:dependencies/m:dependency", namespace)]
    licenses = [dict((field, dep.findtext("m:" + field, namespaces=namespace)) for field in ["name", "url"]) for dep in tree.findall("./m:licenses/m:license", namespace)]
    record = {"group": group, "artifact": name, "version": version, "dependencies": dependencies, "licenses": licenses, "pomUrl": url + ".pom"}
    print(json.dumps(record, ensure_ascii=False), flush=True)
    if binary:
        data = fetch(url + ".aar", folder / (stem + ".aar"))
        sha256 = hashlib.sha256(data).hexdigest()
        checksum = None
        for algorithm in ["sha512", "sha256", "sha1"]:
            try:
                published = fetch(url + ".aar." + algorithm, folder / (stem + ".aar." + algorithm)).decode().strip().split()[0]
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    continue
                raise
            assert published.lower() == hashlib.new(algorithm, data).hexdigest(), f"Maven {algorithm} mismatch for {stem}"
            checksum = {"algorithm": algorithm, "value": published.lower(), "url": url + ".aar." + algorithm}
            break
        assert checksum is not None, f"No published Maven checksum for {stem}"
        record.update(aarUrl=url + ".aar", sha256=sha256, publishedChecksum=checksum, bytes=len(data), localPath=str(folder / (stem + ".aar")))
        with zipfile.ZipFile(folder / (stem + ".aar")) as archive:
            record["files"] = archive.namelist()
        print("VERIFIED", stem, len(data), sha256, flush=True)
    return record


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--monolithic-pom", action="store_true")
    args = parser.parse_args()
    items = PACKAGES if not args.monolithic_pom else [("com.microsoft.onnxruntime", "onnxruntime-android-qnn", "1.29.0")]
    # Downloads are independent; all per-item results are inspected before committing the lock.
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        records = list(pool.map(lambda item: artifact(item, args.download), items))
    ROOT.mkdir(exist_ok=True)
    (ROOT / ("artifacts.json" if args.download else "pom-audit.json")).write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
