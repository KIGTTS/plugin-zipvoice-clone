"""Place the pinned CPU weights in signed, directly readable plugin assets."""
import hashlib
import json
import shutil
from pathlib import Path


PREFIX = "assets/default-model/"
WEIGHTS = {"encoder.int8.onnx", "decoder.int8.onnx", "vocos_24khz.onnx", "tokens.txt", "lexicon.txt"}
MODEL_FILES = WEIGHTS | {"model.json", "MODEL-CARD.md", "VOCODER-MODEL-CARD.md"}
MANIFEST_SHA256 = "b04d452714300b030c567d9d17bed06ac9706b6359c0444c46c67c57ab0ce501"


def file_sha256(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def check_manifest(raw, hashes):
    if hashlib.sha256(raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("Bundled model does not match the pinned CPU release")
    model = json.loads(raw)
    if model["engine"] != "zipvoice" or model["formatVersion"] != 1 or model["sha256"] != hashes:
        raise ValueError("Bundled ZipVoice model assets failed manifest verification")


def stage_model(source: Path, payload: Path):
    try:
        check_manifest((source / "model.json").read_bytes(), {name: file_sha256(source / name) for name in WEIGHTS})
        target = payload / PREFIX
        target.mkdir()
        for name in sorted(MODEL_FILES):
            shutil.copy2(source / name, target / name)
    except FileNotFoundError as error:
        raise ValueError("Prepare the pinned assets with: python tools/prepare_models.py zipvoice") from error


def verify_model(archive, manifest):
    files = manifest["files"]
    entries = {name for name in files if name.startswith(PREFIX)}
    if not entries:
        return {}
    if manifest["id"] != "engine.zipvoice-clone" or entries != {PREFIX + name for name in MODEL_FILES}:
        raise ValueError("Unexpected bundled model assets")
    check_manifest(archive.read(PREFIX + "model.json"), {name: files[PREFIX + name] for name in WEIGHTS})
    sizes = [item.file_size for item in archive.infolist()]
    if max(sizes) > 512 * 1024**2 or sum(sizes) > 768 * 1024**2:
        raise ValueError("Package exceeds the host extraction limits")
    return {"modelAssetBytes": sum(archive.getinfo(name).file_size for name in entries),
            "modelManifestSha256": MANIFEST_SHA256}
