#!/usr/bin/env bash
# env_guard.sh — 采样检测“是否有第三方并发输入/界面变化”，用于给 A 侧断言标注环境稳定性。
#
# 背景：实例 0（A）上还有另一组并行 agent 用 ws-scrcpy 驱动（会旋转/改 wm/注入输入）。
# 本脚本不做断言，只输出 RESULT=STABLE|UNSTABLE；STABLE 表示采样窗口内前台和画面都没被动过，
# 此时紧接着做的 A 侧断言可信度较高；UNSTABLE 表示有并发干扰，断言需重跑或降级为“仅供参考”。
#
# 用法：
#   ./env_guard.sh [A|B|<serial>] [--seconds 2] [--samples 3] [--threshold 0.002]
#
# 产物：evidence/_state/envguard-*.png + 终端输出 SAMPLES/FOCUS_CHANGES/IMG_CHANGES/RESULT=
# 退出码：0 = 采样完成（无论稳定与否）；2 = 参数错误；3 = 设备不可用。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  awk 'NR>1 && /^#/ {sub(/^# ?/, ""); print} NR>1 && !/^#/ {exit}' "$0"
  exit 0
fi

SER_ARG="${1:-A}"
case "$SER_ARG" in
  -*) SER_ARG="A" ;;
  *) shift ;;
esac
SECONDS_BETWEEN=2
SAMPLES=3
THRESHOLD=0.002
while [ "$#" -gt 0 ]; do
  case "$1" in
    --seconds) SECONDS_BETWEEN="${2:-2}"; shift 2 ;;
    --samples) SAMPLES="${2:-3}"; shift 2 ;;
    --threshold) THRESHOLD="${2:-0.002}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

ser="$(e2e_resolve_serial "$SER_ARG")"
e2e_require_device "$ser" || { echo "ERROR: 设备不可用 $ser" >&2; exit 3; }
e2e_mkdirs

focus_of() {
  "$ADB" -s "$ser" shell "dumpsys window 2>/dev/null | grep mCurrentFocus | head -1" 2>/dev/null \
    | tr -d '\r' | sed 's/^[[:space:]]*//'
}

prev_focus=""
prev_png=""
focus_changes=0
img_changes=0
i=0
while [ "$i" -lt "$SAMPLES" ]; do
  png="$STATE_DIR/envguard-$i.png"
  "$ADB" -s "$ser" exec-out screencap -p > "$png" 2>/dev/null
  focus="$(focus_of)"
  echo "SAMPLE $i focus=$focus"
  if [ "$i" -gt 0 ]; then
    [ "$focus" != "$prev_focus" ] && focus_changes=$((focus_changes + 1))
    ratio="$("$PY" "$E2E_DIR/imgdiff.py" "$prev_png" "$png" --json 2>/dev/null \
      | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])' 2>/dev/null || echo 0)"
    echo "  diff vs prev: changed_ratio=$ratio"
    if awk -v r="$ratio" -v t="$THRESHOLD" 'BEGIN{exit !(r > t)}'; then
      img_changes=$((img_changes + 1))
    fi
  fi
  prev_focus="$focus"
  prev_png="$png"
  i=$((i + 1))
  [ "$i" -lt "$SAMPLES" ] && sleep "$SECONDS_BETWEEN"
done

echo "SAMPLES=$SAMPLES FOCUS_CHANGES=$focus_changes IMG_CHANGES=$img_changes threshold=$THRESHOLD"
if [ "$focus_changes" -gt 0 ] || [ "$img_changes" -gt 0 ]; then
  echo "RESULT=UNSTABLE（采样期间有并发输入/界面变化：A 侧断言请重跑或标注“环境不稳定”）"
else
  echo "RESULT=STABLE（窗口内无并发干扰，可做 A 侧相对断言）"
fi
exit 0
