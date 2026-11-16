#!/usr/bin/env bash
# tap.sh — 单点点击。
#
# 用法：
#   ./tap.sh <serial|A|B> <x> <y>
#   ./tap.sh A 1280 720
#   ./tap.sh A 50% 80%        # 支持百分比，按当前截图尺寸换算（受旋转影响）
#
# 坐标空间 = 当前截图像素坐标（横屏时 x∈[0,宽)）。退出码 0 = 命令已下发。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,12p' "$0"; exit 0; }
[ "$#" -ne 3 ] && { sed -n '2,12p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"
e2e_require_device "$SERIAL" || exit 1

pct() {  # <value> <total>
  awk -v p="${1%\%}" -v t="$2" 'BEGIN{printf "%d", p*t/100}'
}

X="$2"; Y="$3"
if [ "${X%\%}" != "$X" ] || [ "${Y%\%}" != "$Y" ]; then
  tmp="$STATE_DIR/.tap_geom.png"
  e2e_mkdirs
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$tmp" || { echo "ERROR: 取几何失败" >&2; exit 1; }
  geom="$(e2e_png_size "$tmp")"; rm -f "$tmp"
  W="${geom%x*}"; H="${geom#*x}"
  [ "${X%\%}" != "$X" ] && X="$(pct "$X" "$W")"
  [ "${Y%\%}" != "$Y" ] && Y="$(pct "$Y" "$H")"
fi

"$ADB" -s "$SERIAL" shell input tap "$X" "$Y" 2>&1 | tr -d '\r'
echo "TAP serial=$SERIAL x=$X y=$Y"
