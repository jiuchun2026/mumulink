#!/usr/bin/env bash
# key.sh — 发送按键。
#
# 用法：
#   ./key.sh <serial|A|B> <key>
#   ./key.sh A BACK          # 自动补 KEYCODE_ 前缀
#   ./key.sh A KEYCODE_HOME
#   ./key.sh A 4             # 数字 keycode 原样传
#
# 常用：BACK(4) HOME(3) APP_SWITCH(187) WAKEUP(224) ENTER(66) DEL(67)

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,12p' "$0"; exit 0; }
[ "$#" -ne 2 ] && { sed -n '2,12p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"
e2e_require_device "$SERIAL" || exit 1
K="$2"
case "$K" in
  [0-9]*) : ;;
  KEYCODE_*) : ;;
  *) K="KEYCODE_$K" ;;
esac
"$ADB" -s "$SERIAL" shell input keyevent "$K" 2>&1 | tr -d '\r'
echo "KEY serial=$SERIAL key=$K"
