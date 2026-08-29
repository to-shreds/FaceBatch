#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
trap '[[ -n "${PID:-}" ]] && kill "$PID" 2>/dev/null || true; rm -rf "$TMP"' EXIT

PORT="$(python - <<'__PORT__'
import socket
s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()
__PORT__
)"
TOKEN='facebatch-smoke-token'
ORIGIN='http://127.0.0.1:8765'

HOST=127.0.0.1 PORT="$PORT" FACEBATCH_MOCK=1 FACEBATCH_GATEWAY_TOKEN="$TOKEN" FACEBATCH_ALLOWED_ORIGINS="$ORIGIN" \
  node "$ROOT/web/gateway/server.mjs" >"$TMP/gateway.log" 2>&1 &
PID=$!

python - "$PORT" <<'__WAIT__'
import socket, sys, time
port=int(sys.argv[1])
for _ in range(100):
    try:
        with socket.create_connection(('127.0.0.1',port),.1):
            raise SystemExit(0)
    except OSError:
        time.sleep(.05)
raise SystemExit('gateway did not start')
__WAIT__

python - "$TMP" <<'__IMAGES__'
from pathlib import Path
from PIL import Image, ImageDraw
import sys
out=Path(sys.argv[1])
for name,size,face in [('donor.jpg',(80,80),True),('target.jpg',(160,100),True)]:
    image=Image.new('RGB',size,(238,242,248))
    draw=ImageDraw.Draw(image)
    if face:
        x=size[0]//2; y=size[1]//2
        draw.ellipse((x-24,y-30,x+24,y+30),fill=(241,190,155),outline=(80,70,65),width=2)
        draw.ellipse((x-10,y-8,x-6,y-4),fill=(20,20,20))
        draw.ellipse((x+6,y-8,x+10,y-4),fill=(20,20,20))
    image.save(out/name,'JPEG',quality=90)
__IMAGES__

BASE="http://127.0.0.1:$PORT"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Origin: $ORIGIN")

curl -fsS "${AUTH[@]}" "$BASE/v1/health" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'
curl -fsS -D - -o /dev/null "${AUTH[@]}" "$BASE/v1/health" | grep -qi "access-control-allow-origin: $ORIGIN"
[[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer wrong" -H "Origin: $ORIGIN" "$BASE/v1/health")" == 401 ]]
[[ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" -H 'Origin: https://not-allowed.example' "$BASE/v1/health")" == 403 ]]

curl -fsS "${AUTH[@]}" -H 'content-type: application/json' -d '{}' "$BASE/v1/session/start" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'
curl -fsS "${AUTH[@]}" -F "target=@$TMP/target.jpg" "$BASE/v1/analyze" | grep -q '"faces"'
curl -fsS "${AUTH[@]}" -F 'engine=aifaceswap_hq' -F "donor=@$TMP/donor.jpg" -F "target=@$TMP/target.jpg" "$BASE/v1/single" -o "$TMP/single.jpg"
curl -fsS "${AUTH[@]}" -F 'assignments=[{"faceIndex":0,"donorId":"0"}]' -F "target=@$TMP/target.jpg" -F "donor_0=@$TMP/donor.jpg" "$BASE/v1/multi" -o "$TMP/multi.jpg"
python - "$TMP" <<'__VERIFY__'
from PIL import Image
from pathlib import Path
import sys
root=Path(sys.argv[1])
for name in ('single.jpg','multi.jpg'):
    with Image.open(root/name) as image:
        image.verify()
__VERIFY__
curl -fsS "${AUTH[@]}" -H 'content-type: application/json' -d '{}' "$BASE/v1/session/end" | grep -Eq '"ok"[[:space:]]*:[[:space:]]*true'

echo 'GATEWAY SMOKE PASSED'
