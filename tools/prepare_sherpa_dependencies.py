"""Cache upstream's pinned archives with Windows trust and SHA256 verification."""
import concurrent.futures
import hashlib
import json
import re
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / 'qualcomm/build/sherpa-native'


def cache(cmake):
    return cache_text(cmake.read_text(encoding='utf8'), str(cmake.relative_to(ROOT)))


def cache_text(content, origin):
    urls = re.findall(r'set\([^\s]+_URL\s+"(https://[^\"]+)"', content)
    hashes = re.findall(r'SHA256=([a-f0-9]{64})', content)
    names = re.findall(r'\$ENV\{HOME\}/Downloads/([^\s]+)', content)
    assert len(urls) == len(hashes) == len(names) == 1, origin
    target = OUTPUT / names[0]
    if not target.is_file():
        print('GET', urls[0], flush=True)
        temporary = target.with_suffix(target.suffix + '.part')
        # Windows curl uses Schannel's system trust and network certificate
        # discovery. No --insecure or custom trust exceptions.
        subprocess.run(['curl.exe', '--http1.1', '--location', '--fail', '--silent', '--show-error',
                        '--max-time', '120', '--output', str(temporary), urls[0]], check=True)
        assert hashlib.sha256(temporary.read_bytes()).hexdigest() == hashes[0], origin
        temporary.replace(target)
    actual = hashlib.sha256(target.read_bytes()).hexdigest()
    assert actual == hashes[0], origin
    print('VERIFIED', target.name, actual, flush=True)
    return dict(cmake=origin, url=urls[0], sha256=actual, file=str(target.relative_to(ROOT)))


if __name__ == '__main__':
    OUTPUT.mkdir(parents=True, exist_ok=True)
    files = [ROOT / 'upstream/sherpa-onnx/cmake' / (name + '.cmake') for name in ['kaldi-native-fbank', 'utf8proc', 'kaldi-decoder', 'simple-sentencepiece', 'json', 'openfst']]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        records = list(pool.map(cache, files))
    # Read the dependency's CMake text directly from the verified archive;
    # do not extract untrusted paths or use a floating nested dependency.
    with tarfile.open(OUTPUT / 'kaldi-decoder-0.3.0.tar.gz') as archive:
        member = next(m for m in archive.getmembers() if m.name.endswith('/cmake/kaldifst.cmake'))
        content = archive.extractfile(member).read().decode('utf8')
        records.append(cache_text(content, 'kaldi-decoder-0.3.0/' + member.name.split('/',1)[1]))
    (ROOT / 'qualcomm/sherpa-dependencies.json').write_text(json.dumps(records, indent=2), encoding='utf8')
