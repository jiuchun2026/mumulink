#!/usr/bin/env bash
# MuMuLink E2E lab — 共享环境变量与辅助函数。
# 用法（其他脚本内）：. "$(dirname "$0")/env.sh"
# 也可手动 source 后调用函数：  source tools/e2e/env.sh; e2e_adb A shell getprop sys.boot_completed

set -u

# Windows/MSYS 下必须关闭 POSIX 路径自动转换，否则 /sdcard/... 会被改写。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$E2E_DIR/../.." && pwd)"
EVIDENCE_DIR="${EVIDENCE_DIR:-$REPO_ROOT/evidence}"
STATE_DIR="${STATE_DIR:-$EVIDENCE_DIR/_state}"
LOG_DIR="${LOG_DIR:-$EVIDENCE_DIR/_logs}"

ADB="${ADB:-adb.exe}"
MUMU_MGR="${MUMU_MGR:-C:/Program Files/Netease/MuMuPlayer/nx_main/MuMuManager.exe}"
PY="${PY:-D:/app/python.exe}"

# A = 主实例（index 0，被控端）；B = 第二个实例（index 1，测试手机/控制端）
SERIAL_MAIN="${SERIAL_MAIN:-127.0.0.1:16384}"
SERIAL_TEST="${SERIAL_TEST:-127.0.0.1:16416}"

PKG="${PKG:-com.mumulink.remote}"
APK_PATH="${APK_PATH:-$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"

# serial 别名：A/main/0 -> 主实例；B/test/1 -> 测试实例；其他原样返回。
e2e_resolve_serial() {
  case "${1:-}" in
    A|a|main|MAIN|0) printf '%s\n' "$SERIAL_MAIN" ;;
    B|b|test|TEST|1) printf '%s\n' "$SERIAL_TEST" ;;
    *) printf '%s\n' "${1:-}" ;;
  esac
}

# e2e_adb <serial|alias> [adb args...]
e2e_adb() {
  local s
  s="$(e2e_resolve_serial "${1:-}")"
  if [ "$#" -ge 1 ]; then shift; fi
  "$ADB" -s "$s" "$@"
}

e2e_is_device() {
  local s
  s="$(e2e_resolve_serial "${1:-}")"
  "$ADB" -s "$s" get-state 2>/dev/null | tr -d '\r' | grep -qx 'device'
}

e2e_require_device() {
  local s
  s="$(e2e_resolve_serial "${1:-}")"
  if ! e2e_is_device "$s"; then
    echo "ERROR: 设备 '$s' 不可用（adb state != device）。" >&2
    echo "  hint: \"$ADB\" connect $s   |   \"$MUMU_MGR\" control -v 1 launch" >&2
    return 1
  fi
  return 0
}

e2e_win_path() { cygpath -w "$1" 2>/dev/null || printf '%s\n' "$1"; }

e2e_mkdirs() { mkdir -p "$EVIDENCE_DIR" "$STATE_DIR" "$LOG_DIR"; }

e2e_ts() { date +%Y%m%d-%H%M%S; }

# PNG 尺寸，输出 WxH；失败返回非 0。
e2e_png_size() {
  "$PY" -c 'import sys
from PIL import Image
with Image.open(sys.argv[1]) as im:
    print("%dx%d" % (im.width, im.height))' "$1" 2>/dev/null
}

e2e_need() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: 缺少依赖命令: $1" >&2; return 1; }
}

e2e_mkdirs
