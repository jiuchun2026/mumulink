#!/usr/bin/env bash
# wait_for.sh — 等待 UI 中出现（或消失）指定文本/正则。
#
# 用法：
#   ./wait_for.sh <serial|A|B> <pattern> [--timeout 15] [--interval 1] [--not]
#   ./wait_for.sh A "设置" --timeout 8
#   ./wait_for.sh B "mumulink" --not --timeout 10
#
# pattern 为 grep -E 正则，匹配整个 uiautomator XML（含 text/content-desc/resource-id）。
# 退出码：0 = 条件达成（FOUND / GONE）；1 = 超时；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,11p' "$0"; exit 0; }

POS=(); TIMEOUT=15; INTERVAL=1; NEG=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --timeout) TIMEOUT="${2:-15}"; shift 2 ;;
    --interval) INTERVAL="${2:-1}"; shift 2 ;;
    --not) NEG=1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) POS+=("$1"); shift ;;
  esac
done
[ "${#POS[@]}" -lt 2 ] && { sed -n '2,11p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "${POS[0]}")"
PATTERN="${POS[1]}"
e2e_require_device "$SERIAL" || exit 1

XML="$STATE_DIR/wait-$(e2e_ts).xml"
start=$(date +%s)
while :; do
  # uiautomator dump 在部分设备上即使 dump 成功也返回非 0（idle 警告），
  # 所以不能把写文件这一步挂在 dump 的退出码后面：先尝试 exec-out 直读，
  # 失败再走 /sdcard 文件回读，最后只以"内容是否以 <?xml 开头"为准。
  if "$ADB" -s "$SERIAL" exec-out uiautomator dump /dev/tty > "$XML" 2>/dev/null &&
     head -c 5 "$XML" 2>/dev/null | grep -q '<?xml'; then
    :
  else
    "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
    "$ADB" -s "$SERIAL" shell cat /sdcard/window_dump.xml > "$XML" 2>/dev/null
  fi
  hit=0
  if [ -s "$XML" ] && grep -qE -- "$PATTERN" "$XML"; then hit=1; fi
  if [ "$NEG" = 1 ] && [ "$hit" = 0 ]; then
    echo "RESULT=GONE pattern='$PATTERN' waited=$(( $(date +%s) - start ))s serial=$SERIAL"
    rm -f "$XML"
    exit 0
  fi
  if [ "$NEG" = 0 ] && [ "$hit" = 1 ]; then
    echo "RESULT=FOUND pattern='$PATTERN' waited=$(( $(date +%s) - start ))s serial=$SERIAL"
    snippet="$(grep -oE -- ".{0,60}$PATTERN.{0,60}" "$XML" | head -1)"
    printf '  match: %s\n' "$snippet"
    rm -f "$XML"
    exit 0
  fi
  if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
    echo "RESULT=TIMEOUT pattern='$PATTERN' timeout=${TIMEOUT}s serial=$SERIAL"
    [ -s "$XML" ] && echo "  XML 保留在 $XML 供排查"
    exit 1
  fi
  sleep "$INTERVAL"
done
