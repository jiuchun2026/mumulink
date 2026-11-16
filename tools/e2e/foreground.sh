#!/usr/bin/env bash
# foreground.sh — 打印当前前台窗口（包名/Activity），供断言“界面切换成功”。
#
# 用法：
#   ./foreground.sh <serial|A|B>
#   ./foreground.sh A --package        # 只输出包名
#   ./foreground.sh A --expect com.mumulink.remote   # 断言包名，失败退出 1

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,9p' "$0"; exit 0; }
[ "${#}" -lt 1 ] && { sed -n '2,9p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "$1")"; shift
EXPECT=""; ONLY_PKG=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --package) ONLY_PKG=1; shift ;;
    --expect) EXPECT="${2:-}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_require_device "$SERIAL" || exit 1

FOCUS="$("$ADB" -s "$SERIAL" shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//')"
if [ -z "$FOCUS" ]; then
  echo "ERROR: 无法读取 mCurrentFocus" >&2
  exit 1
fi

# 用 Python 解析（本机 bash 的 [[ =~ ]] 与 sed -nE 都不可靠）
PKGNAME="$("$PY" - "$FOCUS" <<'PYEOF'
import re, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
s = sys.argv[1]
m = re.search(r'Window\{[^ ]+\s+u\d+\s+([^/ ]+)/', s)
if not m:
    m = re.search(r'([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+', s)
print(m.group(1) if m else "")
PYEOF
)"

if [ "$ONLY_PKG" = 1 ]; then echo "$PKGNAME"; else echo "$FOCUS"; fi

if [ -n "$EXPECT" ]; then
  case "$PKGNAME" in
    "$EXPECT"|"$EXPECT".*) echo "RESULT=MATCH expect=$EXPECT actual=$PKGNAME"; exit 0 ;;
    *) echo "RESULT=MISMATCH expect=$EXPECT actual=$PKGNAME"; exit 1 ;;
  esac
fi
