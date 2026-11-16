#!/usr/bin/env bash
# assert_change.sh — 通用断言：执行一个 adb shell 动作，断言屏幕因此发生变化。
#
# 用法：
#   ./assert_change.sh <serial|A|B> "<adb shell 命令>" [--label NAME] [--threshold 0.01]
#                      [--settle 2] [--pixel-threshold 24] [--expect same]
#
# 例：
#   ./assert_change.sh A "input keyevent KEYCODE_HOME" --label C07-home
#   ./assert_change.sh A "input swipe 1280 1200 1280 300 400" --label C06-swipe
#
# 证据：evidence/<label>-before.png、evidence/<label>-after.png
# 退出码：0 = 达到预期；1 = 未达到；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,16p' "$0"; exit 0; }
[ "$#" -lt 2 ] && { sed -n '2,16p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"; CMD="$2"; shift 2
LABEL="assert-$(date +%H%M%S)"; THRESHOLD="0.01"; SETTLE=2; PIXEL_THRESHOLD=24; EXPECT="changed"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --label) LABEL="${2:-}"; shift 2 ;;
    --threshold) THRESHOLD="${2:-0.01}"; shift 2 ;;
    --settle) SETTLE="${2:-2}"; shift 2 ;;
    --pixel-threshold) PIXEL_THRESHOLD="${2:-24}"; shift 2 ;;
    --expect) EXPECT="${2:-changed}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs

BEFORE="$EVIDENCE_DIR/${LABEL}-before.png"
AFTER="$EVIDENCE_DIR/${LABEL}-after.png"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$BEFORE" || { echo "ERROR: baseline 截图失败" >&2; exit 1; }

echo "ACTION serial=$SERIAL cmd=$CMD"
"$ADB" -s "$SERIAL" shell "$CMD" 2>&1 | tr -d '\r' | sed 's/^/  /'
sleep "$SETTLE"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$AFTER" || { echo "ERROR: after 截图失败" >&2; exit 1; }

j="$("$PY" "$E2E_DIR/imgdiff.py" "$BEFORE" "$AFTER" --pixel-threshold "$PIXEL_THRESHOLD" --json)" || exit 1
ratio="$(printf '%s' "$j" | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])')"
mean="$(printf '%s' "$j" | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["mean_abs"])')"

pass=0
if [ "$EXPECT" = "changed" ]; then
  awk -v r="$ratio" -v t="$THRESHOLD" 'BEGIN{exit !(r>=t)}' && pass=1
else
  awk -v r="$ratio" -v t="$THRESHOLD" 'BEGIN{exit !(r<t)}' && pass=1
fi

echo "CHANGE label=$LABEL changed_ratio=$ratio mean_abs=$mean threshold=$THRESHOLD expect=$EXPECT"
if [ "$pass" = 1 ]; then echo "RESULT=PASS"; exit 0; else echo "RESULT=FAIL"; exit 1; fi
