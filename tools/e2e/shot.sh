#!/usr/bin/env bash
# shot.sh — 截图并校验 PNG 有效性。
#
# 用法：
#   ./shot.sh <serial|A|B> <out.png>
#   ./shot.sh A                      # 自动命名 evidence/<HHMMSS>-A.png
#   ./shot.sh B evidence/03-quality-hd-B.png
#   ./shot.sh A --sleep 500 <out.png>    # 截图前等待 500ms
#
# 截图坐标 = 当前旋转方向下的像素坐标，与 input tap 的坐标空间一致。
# 退出码：0 = 成功且 PNG 可解析；1 = 采集/校验失败。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

SLEEP_MS=0
if [ "${1:-}" = "--sleep" ]; then SLEEP_MS="${2:-0}"; shift 2; fi

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  sed -n '2,12p' "$0"; exit 2
fi

SERIAL="$(e2e_resolve_serial "$1")"
if [ "$#" -ge 2 ]; then OUT="$2"; else OUT="$EVIDENCE_DIR/$(date +%H%M%S)-${SERIAL//[:\/]/_}.png"; fi

e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs
mkdir -p "$(dirname "$OUT")"

if [ "$SLEEP_MS" -gt 0 ] 2>/dev/null; then sleep "$(awk "BEGIN{print $SLEEP_MS/1000}")"; fi

tmp="$OUT.part"
ok=0
for attempt in 1 2 3; do
  if "$ADB" -s "$SERIAL" exec-out screencap -p > "$tmp" 2>"$LOG_DIR/shot.err"; then
    sig="$(head -c 8 "$tmp" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
    if [ "$sig" = "89504e470d0a1a0a" ] && [ "$(wc -c < "$tmp")" -gt 10000 ]; then
      ok=1
      break
    fi
  fi
  echo "WARN: 截图失败/无效（attempt $attempt），重试..." >&2
  sleep 1
done

if [ "$ok" != 1 ]; then
  rm -f "$tmp"
  echo "ERROR: 截图失败 serial=$SERIAL" >&2
  [ -s "$LOG_DIR/shot.err" ] && sed 's/^/  adb: /' "$LOG_DIR/shot.err" >&2
  exit 1
fi

mv -f "$tmp" "$OUT"
size="$(e2e_png_size "$OUT")"
bytes="$(wc -c < "$OUT" | tr -d ' ')"
echo "SAVED $OUT ${size} ${bytes}bytes serial=$SERIAL"
