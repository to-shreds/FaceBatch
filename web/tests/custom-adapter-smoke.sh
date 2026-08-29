#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TMP="$(mktemp -d)"
cleanup(){
  [[ -n "${GATEWAY_PID:-}" ]] && kill "$GATEWAY_PID" 2>/dev/null || true
  [[ -n "${PROVIDER_PID:-}" ]] && kill "$PROVIDER_PID" 2>/dev/null || true
  rm -rf "$TMP"
}
trap cleanup EXIT

read -r PROVIDER_PORT GATEWAY_PORT < <(python - <<'PY'
import socket
ports=[]
for _ in range(2):
    s=socket.socket(); s.bind(('127.0.0.1',0)); ports.append(s.getsockname()[1]); s.close()
print(*ports)
PY
)

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=127.0.0.1' -addext 'subjectAltName=IP:127.0.0.1' \
  -keyout "$TMP/key.pem" -out "$TMP/cert.pem" >/dev/null 2>&1

python - "$TMP/result.jpg" <<'PY'
from PIL import Image, ImageDraw
from pathlib import Path
import sys
path=Path(sys.argv[1])
image=Image.new('RGB',(160,100),(236,242,248)); draw=ImageDraw.Draw(image)
draw.ellipse((55,18,105,82),fill=(238,188,150),outline=(60,55,50),width=2)
image.save(path,'JPEG',quality=92)
PY
cp "$TMP/result.jpg" "$TMP/donor.jpg"
cp "$TMP/result.jpg" "$TMP/target.jpg"

FAKE_PROVIDER_PORT="$PROVIDER_PORT" FAKE_PROVIDER_KEY="$TMP/key.pem" FAKE_PROVIDER_CERT="$TMP/cert.pem" FAKE_PROVIDER_IMAGE="$TMP/result.jpg" \
  node "$ROOT/web/tests/fake-custom-provider.mjs" >"$TMP/provider.log" 2>&1 &
PROVIDER_PID=$!

TOKEN='custom-smoke-token'
ORIGIN='http://127.0.0.1:8765'
HOST=127.0.0.1 PORT="$GATEWAY_PORT" FACEBATCH_GATEWAY_TOKEN="$TOKEN" FACEBATCH_ALLOWED_ORIGINS="$ORIGIN" NODE_TLS_REJECT_UNAUTHORIZED=0 \
  node "$ROOT/web/gateway/server.mjs" >"$TMP/gateway.log" 2>&1 &
GATEWAY_PID=$!

python - "$PROVIDER_PORT" "$GATEWAY_PORT" <<'PY'
import socket, sys, time
for raw in sys.argv[1:]:
    port=int(raw)
    for _ in range(100):
        try:
            with socket.create_connection(('127.0.0.1',port),.1): break
        except OSError: time.sleep(.05)
    else: raise SystemExit(f'port {port} did not open')
PY

BASE="http://127.0.0.1:$GATEWAY_PORT"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Origin: $ORIGIN")

run_case(){
  local name="$1"
  local endpoint="$2"
  local settings="$3"
  curl -fsS "${AUTH[@]}" \
    -F 'engine=custom' \
    -F "settings=$settings" \
    -F "donor=@$TMP/donor.jpg" \
    -F "target=@$TMP/target.jpg" \
    "$BASE/v1/single" -o "$TMP/$name.jpg"
}

COMMON=$(python - "$PROVIDER_PORT" <<'PY'
import json,sys
port=sys.argv[1]
print(json.dumps({
  'endpoint':f'https://127.0.0.1:{port}/validate',
  'sourceField':'face','targetField':'image',
  'enhancerEnabled':True,'enhancerField':'enhancer','safetyField':'check-nsfw',
  'origin':'https://facebatch.test','userAgent':'FaceBatch-QA-UA',
  'authHeaderName':'Authorization','authHeaderValue':'Bearer qa',
  'extraHeaders':{'X-Extra':'yes'},'extraFormFields':{'swap_all':'true'},
  'responseMode':'auto','resultPath':'result.image'
}))
PY
)
run_case validate "https://127.0.0.1:$PROVIDER_PORT/validate" "$COMMON"

for MODE in json fallback plain; do
  SETTINGS=$(python - "$PROVIDER_PORT" "$MODE" <<'PY'
import json,sys
port,mode=sys.argv[1:]
print(json.dumps({'endpoint':f'https://127.0.0.1:{port}/{mode}','sourceField':'face','targetField':'image','responseMode':'auto','resultPath':'result.image'}))
PY
)
  run_case "$MODE" "https://127.0.0.1:$PROVIDER_PORT/$MODE" "$SETTINGS"
done

POLL=$(python - "$PROVIDER_PORT" <<'PY'
import json,sys
port=sys.argv[1]
print(json.dumps({
  'endpoint':f'https://127.0.0.1:{port}/poll-start','sourceField':'face','targetField':'image',
  'responseMode':'polling','pollIdPath':'id','pollUrlTemplate':f'https://127.0.0.1:{port}/poll?id={{id}}',
  'pollStatusPath':'status','pollSuccessValue':'success','pollFailureValues':'failed,error,cancelled',
  'pollIntervalSeconds':1,'maxPolls':2,'resultPath':'output_url'
}))
PY
)
run_case poll "https://127.0.0.1:$PROVIDER_PORT/poll-start" "$POLL"

python - "$TMP" <<'PY'
from PIL import Image
from pathlib import Path
import sys
root=Path(sys.argv[1])
for name in ('validate','json','fallback','plain','poll'):
    with Image.open(root/f'{name}.jpg') as image: image.verify()
PY

echo 'CUSTOM ADAPTER SMOKE PASSED'
