#!/usr/bin/env bash
# type.sh — 向当前焦点输入框输入文本（adb input text）。
#
# 用法：
#   ./type.sh <serial|A|B> <text>
#   ./type.sh A "hello world"
#
# 限制：adb input text 只可靠支持 ASCII；中文等非 ASCII 字符会被丢弃。
#       需要中文输入时安装 ADBKeyboard 并切换 IME，或改用剪贴板方案（待 App 就绪后追加）。
# 退出码 0 = 命令已下发。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,14p' "$0"; exit 0; }
[ "$#" -lt 2 ] && { sed -n '2,14p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"; shift
e2e_require_device "$SERIAL" || exit 1
TEXT="$*"

if printf '%s' "$TEXT" | LC_ALL=C grep -q '[^ -~]'; then
  echo "WARN: 文本含非 ASCII 字符，adb input text 可能无法输入（见脚本头注释）。" >&2
fi

# 空格 -> %s；单引号转义，整体包在单引号里交给远端 sh。
ESC=$(printf '%s' "$TEXT" | sed "s/ /%s/g; s/'/'\\\\''/g")
"$ADB" -s "$SERIAL" shell "input text '$ESC'" 2>&1 | tr -d '\r'
echo "TYPE serial=$SERIAL chars=${#TEXT}"
