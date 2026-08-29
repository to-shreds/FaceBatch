#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'manifests' / 'SNAPSHOT.json'
EXCLUDED = {
    Path('manifests/SNAPSHOT.json'),
    Path('SHA256SUMS'),
}
EXCLUDED_PARTS = {'.git', '__pycache__'}

entries = []
for path in sorted(ROOT.rglob('*')):
    if not path.is_file():
        continue
    rel = path.relative_to(ROOT)
    if rel in EXCLUDED or any(part in EXCLUDED_PARTS for part in rel.parts):
        continue
    data = path.read_bytes()
    entries.append({
        'path': rel.as_posix(),
        'bytes': len(data),
        'sha256': hashlib.sha256(data).hexdigest(),
    })

payload = {
    'snapshot': 'FaceBatch status snapshot',
    'snapshot_date': '2026-08-29',
    'android_controlling_release': '0.7.0',
    'web_release': '1.0.0-snapshot-2026-08-29',
    'repository_intended_visibility': 'private',
    'pages_artifact': 'docs',
    'files': entries,
    'file_count': len(entries),
    'total_bytes': sum(item['bytes'] for item in entries),
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
print(f'Wrote {OUT.relative_to(ROOT)} with {len(entries)} entries')
