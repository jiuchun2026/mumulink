#!/usr/bin/env bash
# agent_api.sh — 调 E2E 自测代理（默认 27380）的 HTTP API
# 用法：
#   ./agent_api.sh hello
#   ./agent_api.sh pair 112233
#   ./agent_api.sh quality 720 60 2000000
#   ./agent_api.sh display fit
#   ./agent_api.sh display match 1920 1080 280
#   ./agent_api.sh logs [sinceSeq]
#   ./agent_api.sh stop
#   ./agent_api.sh start [qualityJson]           # POST /api/session/start
# 环境：AGENT_URL（默认 http://127.0.0.1:27380）、TOKEN（默认读 evidence/_state/token.txt）
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
URL="${AGENT_URL:-http://127.0.0.1:27380}"
TOKFILE="$ROOT/evidence/_state/token.txt"
TOKEN="${TOKEN:-}"
[ -z "$TOKEN" ] && [ -f "$TOKFILE" ] && TOKEN="$(tr -d ' \r\n' < "$TOKFILE")"

cmd="${1:-hello}"; shift || true
_h() { if [ -n "$TOKEN" ]; then printf ' -H "X-MuMuLink-Token: %s"' "$TOKEN"; fi; }

case "$cmd" in
  hello)
    curl -sS --max-time 5 "$URL/api/hello"; echo ;;
  pair)
    code="${1:-}"; [ -z "$code" ] && { echo "usage: agent_api.sh pair <code>" >&2; exit 2; }
    out="$(curl -sS --max-time 8 -X POST -H 'Content-Type: application/json' -d "{\"code\":\"$code\"}" "$URL/api/pair")"
    echo "$out"
    tok="$(printf '%s' "$out" | "$PY" -c 'import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
if d.get("token"):
    sys.stdout.write(d["token"])')"
    if [ -n "$tok" ]; then
      mkdir -p "$ROOT/evidence/_state"
      printf '%s\n' "$tok" > "$TOKFILE"
      echo "TOKEN_SAVED=$TOKFILE"
    fi ;;
  quality)
    body="{\"quality\":{\"maxSize\":${1:-1080},\"maxFps\":${2:-60},\"bitRate\":${3:-4000000}}}"
    curl -sS --max-time 20 -X POST -H 'Content-Type: application/json' -H "X-MuMuLink-Token: $TOKEN" -d "$body" "$URL/api/session/quality"; echo ;;
  display)
    mode="${1:-fit}"
    if [ "$mode" = "fit" ]; then body='{"mode":"fit"}';
    else body="{\"mode\":\"match\",\"width\":${2:-1920},\"height\":${3:-1080},\"density\":${4:-280}}"; fi
    curl -sS --max-time 25 -X POST -H 'Content-Type: application/json' -H "X-MuMuLink-Token: $TOKEN" -d "$body" "$URL/api/display"; echo ;;
  start)
    body="${1:-{\"quality\":{\"maxSize\":1080,\"maxFps\":60,\"bitRate\":4000000},\"display\":{\"mode\":\"fit\"}}}"
    curl -sS --max-time 30 -X POST -H 'Content-Type: application/json' -H "X-MuMuLink-Token: $TOKEN" -d "$body" "$URL/api/session/start"; echo ;;
  stop)
    curl -sS --max-time 20 -X POST -H 'Content-Type: application/json' -H "X-MuMuLink-Token: $TOKEN" -d '{}' "$URL/api/session/stop"; echo ;;
  logs)
    since="${1:-0}"
    curl -sS --max-time 8 -H "X-MuMuLink-Token: $TOKEN" "$URL/api/logs?since=$since"; echo ;;
  *)
    echo "unknown cmd: $cmd" >&2; exit 2 ;;
esac
