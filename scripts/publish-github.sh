#!/usr/bin/env bash
set -euo pipefail

FULL_NAME="${1:-jonathanjablon-stack/FaceBatch}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

command -v git >/dev/null || { echo 'Git is required.' >&2; exit 1; }
command -v gh >/dev/null || { echo 'GitHub CLI is required.' >&2; exit 1; }
gh auth status >/dev/null

python scripts/validate_snapshot.py

if [[ ! -d .git ]]; then
  git init -b main
  git config user.name "${GIT_AUTHOR_NAME:-Jon Jablon}"
  git config user.email "${GIT_AUTHOR_EMAIL:-jonathanjablon-stack@users.noreply.github.com}"
  git add -A
  git commit -m "Preserve FaceBatch Android and web status snapshot"
  git tag -a snapshot-2026-08-29 -m "FaceBatch status snapshot 2026-08-29"
fi

if gh repo view "$FULL_NAME" >/dev/null 2>&1; then
  if ! git remote get-url origin >/dev/null 2>&1; then
    git remote add origin "https://github.com/${FULL_NAME}.git"
  fi
  git push -u origin main
else
  gh repo create "$FULL_NAME" --private --source=. --remote=origin --push
fi

git push origin --tags

if ! gh api --method POST "repos/${FULL_NAME}/pages" -f build_type=workflow >/dev/null 2>&1; then
  gh api --method PUT "repos/${FULL_NAME}/pages" -f build_type=workflow >/dev/null
fi

gh workflow run pages.yml --repo "$FULL_NAME" || true

echo "Private repository: https://github.com/${FULL_NAME}"
OWNER="${FULL_NAME%%/*}"
REPO="${FULL_NAME#*/}"
echo "Expected public Pages site: https://${OWNER}.github.io/${REPO}/"
echo "Check GitHub Actions for deployment status."
