#!/usr/bin/env bash
# verify_touch.sh — 触控闭环自检：切已知界面 → 滑动 → 断言画面差异达阈值。
#
# 用法：
#   ./verify_touch.sh [serial|A] [--threshold 0.01] [--settle 1.5]
#
# 逻辑：
#   1. 切到系统设置（可复现的列表界面）
#   2. 截图 before
#   3. 按当前截图尺寸做一次向上滑动（列表滚动）
#   4. 截图 after，计算 changed_ratio
#   5. 若滑动未达阈值，退化用一次点击（屏幕中央）再断言
#   6. 回桌面
#
# 证据：evidence/verify-touch-<serial>-before/after.png
# 退出码：0 = PASS；1 = FAIL。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,16p' "$0"; exit 0; }

SERIAL="$(e2e_resolve_serial "${1:-A}")"
[ "$#" -ge 1 ] && shift
THRESHOLD="0.01"; SETTLE="1.5"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --threshold) THRESHOLD="${2:-0.01}"; shift 2 ;;
    --settle) SETTLE="${2:-1.5}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs
SAFE="${SERIAL//[:\/]/_}"
BEFORE="$EVIDENCE_DIR/verify-touch-$SAFE-before.png"
AFTER="$EVIDENCE_DIR/verify-touch-$SAFE-after.png"

echo "STEP 1/4 切到系统设置"
"$E2E_DIR/known_screen.sh" "$SERIAL" settings --settle 2 >/dev/null || true

echo "STEP 2/4 截图 before"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$BEFORE" || { echo "RESULT=FAIL（before 截图失败）"; exit 1; }
GEOM="$(e2e_png_size "$BEFORE")"; W="${GEOM%x*}"; H="${GEOM#*x}"
echo "  geometry=${W}x${H}"

X=$((W / 2)); Y1=$((H * 72 / 100)); Y2=$((H * 28 / 100))
echo "STEP 3/4 滑动 ($X,$Y1) -> ($X,$Y2)"
"$ADB" -s "$SERIAL" shell input swipe "$X" "$Y1" "$X" "$Y2" 400 >/dev/null 2>&1
sleep "$SETTLE"
"$ADB" -s "$SERIAL" exec-out screencap -p > "$AFTER" || { echo "RESULT=FAIL（after 截图失败）"; exit 1; }
RATIO="$("$PY" "$E2E_DIR/imgdiff.py" "$BEFORE" "$AFTER" --json | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])')"
echo "  swipe changed_ratio=$RATIO threshold=$THRESHOLD"

PASS=0
awk -v r="$RATIO" -v t="$THRESHOLD" 'BEGIN{exit !(r>=t)}' && PASS=1
if [ "$PASS" = 0 ]; then
  echo "STEP 3b 滑动差异不足，退化测试：屏幕中央单击"
  "$ADB" -s "$SERIAL" shell input tap $((W / 2)) $((H / 2)) >/dev/null 2>&1
  sleep "$SETTLE"
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$AFTER" || true
  RATIO="$("$PY" "$E2E_DIR/imgdiff.py" "$BEFORE" "$AFTER" --json | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])')"
  echo "  tap changed_ratio=$RATIO threshold=$THRESHOLD"
  awk -v r="$RATIO" -v t="$THRESHOLD" 'BEGIN{exit !(r>=t)}' && PASS=1
fi

echo "STEP 4/4 回桌面"
"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1

if [ "$PASS" = 1 ]; then
  echo "RESULT=PASS changed_ratio=$RATIO serial=$SERIAL evidence=$EVIDENCE_DIR"
  exit 0
else
  echo "RESULT=FAIL changed_ratio=$RATIO serial=$SERIAL（触控未产生预期画面变化）"
  exit 1
fi
