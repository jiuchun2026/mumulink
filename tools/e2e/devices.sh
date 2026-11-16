#!/usr/bin/env bash
# devices.sh — 列出主/副实例状态、MuMu 管理的 adb 端口、分辨率、当前前台。
#
# 用法：
#   ./devices.sh            # 一次性快照
#   ./devices.sh --wait     # 等待 A/B 都进入 device 状态（默认 120s）
#   ./devices.sh --wait 180
#   ./devices.sh --json     # 机器可读（每行 key=value）
#
# 退出码：0 = 两台都可用；1 = 至少一台不可用。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

WAIT=0
WAIT_TIMEOUT=120
JSON=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --wait) WAIT=1; shift; if [ "${1:-}" != "" ] && [ "${1#-}" = "$1" ]; then WAIT_TIMEOUT="$1"; shift; fi ;;
    --json) JSON=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

if [ "$WAIT" = 1 ]; then
  end=$(( $(date +%s) + WAIT_TIMEOUT ))
  while :; do
    a=0; b=0
    e2e_is_device A && a=1
    e2e_is_device B && b=1
    if [ "$a" = 1 ] && [ "$b" = 1 ]; then
      echo "OK: A 和 B 都已就绪（等待 $(( WAIT_TIMEOUT - (end - $(date +%s)) ))s）"
      break
    fi
    if [ "$(date +%s)" -ge "$end" ]; then
      echo "TIMEOUT: 等待超时（A=$a B=$b）" >&2
      break
    fi
    sleep 2
  done
fi

echo "===== MuMu 实例（MuMuManager info） ====="
"$MUMU_MGR" info -v all 2>/dev/null | grep -E '"(index|name|adb_port|adb_host_ip|is_android_started|player_state)"' | awk '{print "  " $0}'

echo
echo "===== adb devices ====="
"$ADB" devices -l 2>&1 | sed 's/\r$//'

echo
echo "===== 角色映射 ====="
printf '  A main  (index 0) = %s\n' "$SERIAL_MAIN"
printf '  B test  (index 1) = %s\n' "$SERIAL_TEST"

rc=0
for role in A B; do
  ser="$(e2e_resolve_serial "$role")"
  echo
  echo "===== $role ($ser) ====="
  state="$("$ADB" -s "$ser" get-state 2>/dev/null | tr -d '\r')"
  if [ "$state" != "device" ]; then
    echo "  state=$state  -> 不可用"
    rc=1
    continue
  fi
  boot="$("$ADB" -s "$ser" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  size_raw="$("$ADB" -s "$ser" shell wm size 2>/dev/null | tr -d '\r' | grep -m1 'Physical size')"
  density_raw="$("$ADB" -s "$ser" shell wm density 2>/dev/null | tr -d '\r' | grep -m1 'Physical density')"
  size="${size_raw#*: }"; [ -n "$size" ] || size="$size_raw"
  density="${density_raw#*: }"; [ -n "$density" ] || density="$density_raw"
  model="$("$ADB" -s "$ser" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  release="$("$ADB" -s "$ser" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
  focus="$("$ADB" -s "$ser" shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r')"
  # 去掉前导空白（不用 sed：本机 sed 为 WinuxCmd 模拟版，行为受限）
  while [[ "$focus" == [[:space:]]* ]]; do focus="${focus#?}"; done
  echo "  state=$state boot_completed=${boot:-?} size=${size:-?} density=${density:-?} model=${model:-?} android=${release:-?}"
  echo "  focus=${focus:-unknown}"
  if [ "$JSON" = 1 ]; then
    echo "ROLE=$role SERIAL=$ser STATE=$state BOOT=$boot SIZE=$size DENSITY=$density MODEL=$model ANDROID=$release"
  fi
done

exit "$rc"
