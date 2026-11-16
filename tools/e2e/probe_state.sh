#!/usr/bin/env bash
# probe_state.sh — 主实例状态探针：截图并与基线比较差异比例，用于断言触控是否生效。
#
# 用法：
#   ./probe_state.sh baseline [serial|A] [--out FILE]
#   ./probe_state.sh diff [serial|A] [--baseline FILE] [--threshold 0.01]
#                         [--pixel-threshold 24] [--assert changed|same]
#                         [--crop-top 0.04] [--crop-bottom 0.02] [--out FILE] [--json]
#   ./probe_state.sh watch  [serial|A] [--interval 1] [--count 5]
#
# baseline  采集基线图（默认 evidence/_state/baseline-<serial>.png）
# diff      采集当前图，与基线比较；--assert changed 时 changed_ratio >= threshold 为 PASS
# watch     连续采样，打印每一帧相对上一帧的差异比例（观察动画是否稳定）
#
# 退出码：0 = PASS / 采集成功；1 = 断言失败；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

[ "${1:-}" = "-h" ] && { sed -n '2,20p' "$0"; exit 0; }
MODE="${1:-}"; shift || true
case "$MODE" in baseline|diff|watch) : ;; *) sed -n '2,20p' "$0"; exit 2 ;; esac

POS=()
THRESHOLD="0.01"; PIXEL_THRESHOLD=24; ASSERT="changed"; BASELINE=""; OUT=""
CROP_TOP="0.04"; CROP_BOTTOM="0.02"; INTERVAL=1; COUNT=5; JSON=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --threshold) THRESHOLD="${2:-0.01}"; shift 2 ;;
    --pixel-threshold) PIXEL_THRESHOLD="${2:-24}"; shift 2 ;;
    --assert) ASSERT="${2:-changed}"; shift 2 ;;
    --baseline) BASELINE="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --crop-top) CROP_TOP="${2:-0.04}"; shift 2 ;;
    --crop-bottom) CROP_BOTTOM="${2:-0.02}"; shift 2 ;;
    --interval) INTERVAL="${2:-1}"; shift 2 ;;
    --count) COUNT="${2:-5}"; shift 2 ;;
    --json) JSON=1; shift ;;
    *) POS+=("$1"); shift ;;
  esac
done

SERIAL="$(e2e_resolve_serial "${POS[0]:-A}")"
SAFE="${SERIAL//[:\/]/_}"
[ -n "$BASELINE" ] || BASELINE="$STATE_DIR/baseline-$SAFE.png"
e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs

shoot() {  # $1 = out path
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$1" 2>/dev/null
  [ -s "$1" ] || { echo "ERROR: 截图失败 $1" >&2; return 1; }
}

imgdiff_json() {  # $1 before  $2 after
  "$PY" "$E2E_DIR/imgdiff.py" "$1" "$2" --pixel-threshold "$PIXEL_THRESHOLD" \
      --crop-top "$CROP_TOP" --crop-bottom "$CROP_BOTTOM" --json
}

get_field() {  # $1 json  $2 field
  printf '%s' "$1" | "$PY" -c 'import json,sys
d = json.load(sys.stdin)
print(d.get(sys.argv[1], ""))' "$2"
}

case "$MODE" in
  baseline)
    [ -n "$OUT" ] || OUT="$BASELINE"
    shoot "$OUT" || exit 1
    echo "BASELINE $OUT serial=$SERIAL size=$(e2e_png_size "$OUT")"
    ;;
  diff)
    [ -s "$BASELINE" ] || { echo "ERROR: 基线不存在: $BASELINE（先跑 probe_state.sh baseline）" >&2; exit 2; }
    CUR="$STATE_DIR/current-$SAFE-$(e2e_ts).png"
    shoot "$CUR" || exit 1
    j="$(imgdiff_json "$BASELINE" "$CUR")" || exit 1
    ratio="$(get_field "$j" changed_ratio)"
    mean="$(get_field "$j" mean_abs)"
    if [ "$JSON" = 1 ]; then echo "$j"; fi
    pass=0
    case "$ASSERT" in
      changed) awk -v r="$ratio" -v t="$THRESHOLD" 'BEGIN{exit !(r>=t)}' && pass=1 ;;
      same)    awk -v r="$ratio" -v t="$THRESHOLD" 'BEGIN{exit !(r<t)}' && pass=1 ;;
      *) echo "ERROR: --assert 只能是 changed|same" >&2; exit 2 ;;
    esac
    echo "PROBE mode=diff serial=$SERIAL baseline=$BASELINE current=$CUR"
    echo "  changed_ratio=$ratio mean_abs=$mean threshold=$THRESHOLD assert=$ASSERT"
    if [ "$pass" = 1 ]; then echo "RESULT=PASS"; else
      echo "RESULT=FAIL（画面变化未达预期）"
      [ -n "$OUT" ] && cp -f "$CUR" "$OUT"
      exit 1
    fi
    [ -n "$OUT" ] && cp -f "$CUR" "$OUT"
    ;;
  watch)
    prev="$STATE_DIR/watch-prev-$SAFE.png"
    shoot "$prev" || exit 1
    i=1
    while [ "$i" -le "$COUNT" ]; do
      sleep "$INTERVAL"
      cur="$STATE_DIR/watch-cur-$SAFE.png"
      shoot "$cur" || exit 1
      j="$(imgdiff_json "$prev" "$cur")" || exit 1
      echo "WATCH[$i] changed_ratio=$(get_field "$j" changed_ratio) mean_abs=$(get_field "$j" mean_abs)"
      mv -f "$cur" "$prev"
      i=$((i + 1))
    done
    ;;
esac
