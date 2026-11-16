"""Download public weights and build independently importable model ZIPs. Python 3.12+."""
import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request
import zipfile
from pathlib import Path

HEADERS = {"User-Agent": "KIGTTS-Clone-Plugins/0.1.0"}


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90) as response:
        return json.load(response)


def download(url, path, expected=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and (expected is None or digest(path) == expected):
        print("Using", path.name, flush=True)
        return path
    partial = path.with_name(path.name + ".part")
    start = partial.stat().st_size if partial.exists() else 0
    headers = HEADERS | ({"Range": f"bytes={start}-"} if start else {})
    print("Downloading", url, flush=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=180) as response:
        append = start > 0 and response.status == 206
        with partial.open("ab" if append else "wb") as output:
            shutil.copyfileobj(response, output, 1024 * 1024)
    if expected and digest(partial) != expected:
        raise ValueError(f"SHA-256 mismatch: {path.name}")
    partial.replace(path)
    return path


def released_asset(tag, name):
    release = get_json(f"https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/{tag}")
    asset = next(item for item in release["assets"] if item["name"] == name)
    expected = asset.get("digest", "").removeprefix("sha256:") or None
    return asset["browser_download_url"], expected


def prepare_zipvoice(root):
    name = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2"
    url, expected = released_asset("tts-models", name)
    archive = download(url, root / "downloads" / name, expected)
    stage = root / "zipvoice"
    stage.mkdir(exist_ok=True)
    extracted = root / "zipvoice-source"
    if not extracted.exists():
        extracted.mkdir()
        with tarfile.open(archive) as source:
            source.extractall(extracted, filter="data")
    model = next(extracted.glob("*/encoder.int8.onnx")).parent
    files = ["encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "lexicon.txt"]
    for name in files:
        shutil.copy2(model / name, stage / name)
    url, expected = released_asset("vocoder-models", "vocos_24khz.onnx")
    download(url, stage / "vocos_24khz.onnx", expected)
    files.append("vocos_24khz.onnx")
    download("https://huggingface.co/k2-fsa/ZipVoice/raw/main/README.md", stage / "MODEL-CARD.md")
    download("https://huggingface.co/charactr/vocos-mel-24khz/raw/main/README.md", stage / "VOCODER-MODEL-CARD.md")
    # Official model example, retained only as a local test fixture, never bundled in plugins.
    examples = root / "reference"
    examples.mkdir(exist_ok=True)
    candidates = list(model.glob("test_wavs/leijun-1.wav"))
    if candidates:
        shutil.copy2(candidates[0], examples / "reference.wav")
        (examples / "reference.txt").write_text("那还是三十六年前, 一九八七年. 我呢考上了武汉大学的计算机系.", encoding="utf-8")
    manifest = {"formatVersion": 1, "engine": "zipvoice", "license": "CC-BY-NC (ZipVoice); MIT (Vocos)", "source": str(archive.name)}
    return stage, files, manifest


def prepare_qwen(root):
    info = get_json("https://huggingface.co/api/models/Serveurperso/Qwen3-TTS-GGUF?blobs=true")
    revision = info["sha"]
    stage = root / "qwen3"
    stage.mkdir(exist_ok=True)
    files = ["qwen-talker-0.6b-base-Q4_K_M.gguf", "qwen-tokenizer-12hz-Q4_K_M.gguf"]
    for name in files:
        entry = next(item for item in info["siblings"] if item["rfilename"] == name)
        expected = entry.get("lfs", {}).get("sha256")
        if not expected:
            raise ValueError("Hugging Face LFS hash missing for " + name)
        download(f"https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/{revision}/{name}", stage / name, expected)
    download(f"https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/raw/{revision}/README.md", stage / "MODEL-CARD.md")
    return stage, files, {"formatVersion": 1, "engine": "qwen3", "license": "Apache-2.0", "source": "Serveurperso/Qwen3-TTS-GGUF", "revision": revision}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("engine", choices=["zipvoice", "qwen3"])
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parent.parent / "test-assets")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    stage, files, manifest = prepare_zipvoice(args.output) if args.engine == "zipvoice" else prepare_qwen(args.output)
    manifest["sha256"] = {name: digest(stage / name) for name in files}
    (stage / "model.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    target = args.output / f"{args.engine}-model.zip"
    with zipfile.ZipFile(target, "w", zipfile.ZIP_STORED, allowZip64=True) as archive:
        for file in sorted(stage.iterdir()):
            if file.is_file():
                archive.write(file, file.name)
    print(f"Created {target} ({target.stat().st_size} bytes), sha256={digest(target)}", flush=True)


if __name__ == "__main__":
    main()
