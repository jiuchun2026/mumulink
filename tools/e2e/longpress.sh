#!/usr/bin/env bash
# longpress.sh — 长按（原地 swipe，按住 duration 毫秒）。
#
# 用法：
#   ./longpress.sh <serial|A|B> <x> <y> [duration_ms=800]

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,8p' "$0"; exit 0; }
[ "$#" -lt 3 ] && { sed -n '2,8p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"; shift
e2e_require_device "$SERIAL" || exit 1
DUR="${3:-800}"
"$ADB" -s "$SERIAL" shell input swipe "$1" "$2" "$1" "$2" "$DUR" 2>&1 | tr -d '\r'
echo "LONGPRESS serial=$SERIAL x=$1 y=$2 ${DUR}ms"
