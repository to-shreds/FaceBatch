#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$(dirname "$ROOT")}"
NAME="FaceBatch-snapshot-2026-08-29"

cd "$ROOT"
cp web/FaceBatch.html web/index.html
cp web/FaceBatch.html docs/index.html
python scripts/generate-manifest.py
find . -type f -not -path './.git/*' -not -name SHA256SUMS -print0 \
  | sort -z \
  | xargs -0 sha256sum \
  | sed 's#  \./#  #' > SHA256SUMS
python scripts/validate_snapshot.py

rm -f "$OUT_DIR/$NAME.zip"
python - "$ROOT" "$OUT_DIR/$NAME.zip" <<'__ZIP_SCRIPT__'
from pathlib import Path
import sys
import zipfile
root = Path(sys.argv[1])
out = Path(sys.argv[2])
with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(root.rglob('*')):
        if not path.is_file() or '.git' in path.parts:
            continue
        archive.write(path, Path(root.name) / path.relative_to(root))
print(out)
__ZIP_SCRIPT__

if [[ -d .git ]]; then
  git bundle create "$OUT_DIR/$NAME.bundle" --all
fi
