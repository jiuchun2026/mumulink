#!/usr/bin/env bash
# swipe.sh — 滑动（也用于双指操作的底座验证：先证明单指滑动链路可用）。
#
# 用法：
#   ./swipe.sh <serial|A|B> <x1> <y1> <x2> <y2> [duration_ms]
#   ./swipe.sh A 1280 1200 1280 300 400
#
# 退出码 0 = 命令已下发。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,10p' "$0"; exit 0; }
[ "$#" -lt 5 ] && { sed -n '2,10p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"; shift
e2e_require_device "$SERIAL" || exit 1
DUR="${5:-300}"
"$ADB" -s "$SERIAL" shell input swipe "$1" "$2" "$3" "$4" "$DUR" 2>&1 | tr -d '\r'
echo "SWIPE serial=$SERIAL $1,$2 -> $3,$4 ${DUR}ms"
