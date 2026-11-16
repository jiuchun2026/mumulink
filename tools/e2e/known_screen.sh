#!/usr/bin/env bash
# known_screen.sh — 把设备切到“已知界面”，用于可重复的截图差异断言。
#
# 用法：
#   ./known_screen.sh <serial|A|B> <target> [--settle 1.5]
#   ./known_screen.sh A settings
#   ./known_screen.sh --list
#
# target:
#   home         返回桌面（KEYCODE_HOME）
#   settings     系统设置主页
#   display      显示设置
#   wifi         WLAN 设置
#   about        关于手机
#   apps         应用列表
#   notifications 下拉通知栏
#   quick        下拉快捷设置面板
#   back         返回键
#   calc         计算器（尽力而为，找不到包会失败）
#   clock        闹钟（尽力而为）
#
# 退出码：0 = 已下发且前台非空；1 = 失败；2 = 参数错误。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

SETTLE="1.5"
if [ "${1:-}" = "--list" ]; then
  sed -n '8,27p' "$0"; exit 0
fi
if [ "$#" -lt 2 ]; then sed -n '2,27p' "$0"; exit 2; fi

SERIAL="$(e2e_resolve_serial "$1")"; TARGET="$2"; shift 2
while [ "$#" -gt 0 ]; do
  case "$1" in
    --settle) SETTLE="${2:-1.5}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_require_device "$SERIAL" || exit 1

LOG="$LOG_DIR/known_screen.log"
e2e_mkdirs
run() { echo "+ adb shell $*" >> "$LOG"; "$ADB" -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r' | sed 's/^/  /'; }

case "$TARGET" in
  home) run input keyevent KEYCODE_HOME ;;
  back) run input keyevent KEYCODE_BACK ;;
  settings) run am start -a android.settings.SETTINGS ;;
  display) run am start -a android.settings.DISPLAY_SETTINGS ;;
  wifi) run am start -a android.settings.WIFI_SETTINGS ;;
  about) run am start -a android.settings.DEVICE_INFO_SETTINGS ;;
  apps) run am start -a android.settings.APPLICATION_SETTINGS ;;
  notifications) run cmd statusbar expand-notifications ;;
  quick) run cmd statusbar expand-settings ;;
  calc)
    found=0
    for p in com.android.calculator2 com.google.android.calculator com.android.calculator; do
      if "$ADB" -s "$SERIAL" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$p"; then
        run monkey -p "$p" -c android.intent.category.LAUNCHER 1 >/dev/null; found=1; break
      fi
    done
    [ "$found" = 0 ] && { echo "ERROR: 未找到计算器包（可改用 settings/calc 之外的目标）" >&2; exit 1; }
    ;;
  clock)
    run am start -a android.intent.action.SHOW_ALARMS || true
    "$ADB" -s "$SERIAL" shell monkey -p com.android.deskclock -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
    ;;
  *) echo "ERROR: 未知 target '$TARGET'（--list 查看）" >&2; exit 2 ;;
esac

sleep "$SETTLE"
FOCUS="$("$ADB" -s "$SERIAL" shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//')"
echo "SCREEN target=$TARGET serial=$SERIAL"
echo "  focus=${FOCUS:-unknown}"
case "$TARGET" in
  settings|display|wifi|about|apps)
    case "$FOCUS" in
      *com.android.settings*) echo "  RESULT=OK（系统设置已在前台）" ;;
      *) echo "  WARN: 前台不是 com.android.settings，可能启动失败或被别的窗口遮挡" ;;
    esac ;;
esac
[ -n "$FOCUS" ] || exit 1
