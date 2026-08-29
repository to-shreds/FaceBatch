#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def sha(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

canonical = ROOT / 'web' / 'FaceBatch.html'
for copy in [ROOT / 'web' / 'index.html', ROOT / 'docs' / 'index.html']:
    if not copy.exists() or sha(copy) != sha(canonical):
        fail(f'Publishing copy differs from canonical HTML: {copy.relative_to(ROOT)}')

html = canonical.read_text(encoding='utf-8')
required_html = [
    "const VERSION = '1.0.0-snapshot-2026-08-29'",
    'Cross-product face swap',
    'Multi-face batch',
    'Auto assign',
    'Same donor',
    'indexedDB',
    "gatewayFetch('/v1/multi'",
]
for marker in required_html:
    if marker not in html:
        fail(f'Missing web feature marker: {marker}')
if re.search(r'<(?:script|link|img)\b[^>]+(?:src|href)=["\']https?://', html, re.I):
    fail('Standalone HTML contains a remote asset dependency')

scripts = re.findall(r'<script(?:\s[^>]*)?>(.*?)</script>', html, flags=re.I | re.S)
if len(scripts) != 1:
    fail(f'Expected one inline application script, found {len(scripts)}')
else:
    temp = ROOT / '.tmp-facebatch-script-check.js'
    temp.write_text(scripts[0], encoding='utf-8')
    result = subprocess.run(['node', '--check', str(temp)], capture_output=True, text=True)
    temp.unlink(missing_ok=True)
    if result.returncode:
        fail('Inline JavaScript syntax failed: ' + result.stderr.strip())

gateway = ROOT / 'web' / 'gateway' / 'server.mjs'
result = subprocess.run(['node', '--check', str(gateway)], capture_output=True, text=True)
if result.returncode:
    fail('Gateway JavaScript syntax failed: ' + result.stderr.strip())

binary_extensions = {'.zip', '.png', '.jpg', '.jpeg', '.webp', '.apk', '.aab', '.jks', '.keystore', '.bundle'}
for path in ROOT.rglob('*'):
    if not path.is_file() or '.git' in path.parts or path.suffix.lower() in binary_extensions:
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    if '\u2014' in text:
        fail(f'Unicode em dash found in {path.relative_to(ROOT)}')

for path in ROOT.rglob('*'):
    if path.is_file() and path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx'}:
        fail(f'Signing material committed: {path.relative_to(ROOT)}')

# Detect likely literal signing-password assignments while allowing environment references.
secret_assignment = re.compile(r'(?im)^\s*(?:storepass|keypass|password)\s*[:=]\s*["\']?(?!\$\{|process\.env|os\.environ|<|replace-)([^\s"\']{4,})')
for path in ROOT.rglob('*'):
    if not path.is_file() or path.suffix.lower() in binary_extensions or '.git' in path.parts:
        continue
    try:
        text = path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    if secret_assignment.search(text):
        fail(f'Possible literal password assignment in {path.relative_to(ROOT)}')

source_tree = ROOT / 'apk' / 'archive' / 'FaceBatch-0.4.0-source'
required_source = [
    source_tree / 'README.md',
    source_tree / 'SIGNING.txt',
    source_tree / 'app' / 'build.gradle',
    source_tree / 'app' / 'src' / 'main' / 'AndroidManifest.xml',
    source_tree / 'app' / 'src' / 'main' / 'java' / 'com' / 'jon' / 'facebatch' / 'MainActivity.java',
]
for required in required_source:
    if not required.exists():
        fail(f'Sanitized Android source path is missing: {required.relative_to(ROOT)}')

scrubbed = ROOT / 'apk' / 'archive' / 'FaceBatch-0.4.0-source-scrubbed.zip'
if scrubbed.exists():
    try:
        with zipfile.ZipFile(scrubbed) as archive:
            bad = archive.testzip()
            if bad:
                fail(f'Sanitized source ZIP has a corrupt entry: {bad}')
            forbidden = [name for name in archive.namelist() if Path(name).suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx'}]
            if forbidden:
                fail('Sanitized source ZIP contains signing material')
    except zipfile.BadZipFile:
        fail('Sanitized source ZIP is invalid')

manifest_path = ROOT / 'manifests' / 'SNAPSHOT.json'
if manifest_path.exists():
    try:
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
        for entry in manifest.get('files', []):
            path = ROOT / entry['path']
            if not path.exists():
                fail(f'Manifest path missing: {entry["path"]}')
            elif sha(path) != entry['sha256']:
                fail(f'Manifest hash mismatch: {entry["path"]}')
    except Exception as exc:
        fail(f'Manifest could not be validated: {exc}')

sums = ROOT / 'SHA256SUMS'
if sums.exists():
    for raw in sums.read_text(encoding='utf-8').splitlines():
        if not raw.strip():
            continue
        digest, rel = raw.split('  ', 1)
        path = ROOT / rel
        if not path.exists():
            fail(f'SHA256SUMS path missing: {rel}')
        elif sha(path) != digest:
            fail(f'SHA256SUMS mismatch: {rel}')

if errors:
    print('VALIDATION FAILED')
    for error in errors:
        print(f'- {error}')
    sys.exit(1)

print('VALIDATION PASSED')
print(f'Canonical HTML SHA-256: {sha(canonical)}')
print(f'Gateway SHA-256: {sha(gateway)}')
