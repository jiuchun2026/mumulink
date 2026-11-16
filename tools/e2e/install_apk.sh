#!/usr/bin/env bash
# install_apk.sh — 把 MuMuLink APK 安装到指定实例，并记录包名/版本证据。
#
# 用法：
#   ./install_apk.sh [apk_path] [--target B|A|both] [--no-uninstall] [--launch]
#
# 默认：apk = android/app/build/outputs/apk/debug/app-debug.apk，target = B（测试手机实例1）
# 行为：校验 APK 是有效 zip 且含 AndroidManifest.xml → 记录 sha256 → uninstall（忽略失败）
#       → install -r -t → pm list packages | grep mumulink → dumpsys package | grep version
#       → 可选 am start 启动
# 证据：evidence/install-<ts>.log（同时输出到终端）
# 退出码：0 = 至少目标安装成功；1 = APK 缺失/无效或安装失败；2 = 参数错误。
#
# 注意：本脚本绝不触发 gradle 构建；APK 缺失时应等待 Lead 构建完成后重跑。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,18p' "$0"; exit 0; }

APK=""; TARGETS="B"; DO_UNINSTALL=1; DO_LAUNCH=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --target) TARGETS="${2:-B}"; shift 2 ;;
    --no-uninstall) DO_UNINSTALL=0; shift ;;
    --launch) DO_LAUNCH=1; shift ;;
    --) shift; break ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *) APK="$1"; shift ;;
  esac
done
[ -n "$APK" ] || APK="$APK_PATH"
e2e_mkdirs
LOG="$EVIDENCE_DIR/install-$(e2e_ts).log"
exec > >(tee -a "$LOG") 2>&1

echo "===== MuMuLink APK 安装 $(date '+%F %T') ====="
echo "APK=$APK"
if [ ! -f "$APK" ]; then
  echo "RESULT=SKIP APK 不存在（等待构建完成）"
  echo "  期望路径: $APK"
  exit 1
fi
echo "size=$(wc -c < "$APK" | tr -d ' ') bytes mtime=$(date -r "$APK" '+%F %T')"
echo "sha256=$(sha256sum "$APK" 2>/dev/null | cut -d' ' -f1)"

"$PY" - "$APK" <<'PYEOF'
import sys, zipfile
p = sys.argv[1]
try:
    with zipfile.ZipFile(p) as z:
        names = z.namelist()
        assert "AndroidManifest.xml" in names, "缺少 AndroidManifest.xml（不是有效 APK）"
        bad = z.testzip()
        assert bad is None, "zip 损坏: %s" % bad
    print("apk_zip=OK entries=%d" % len(names))
except Exception as e:
    print("apk_zip=INVALID: %s" % e)
    sys.exit(1)
PYEOF
[ "$?" = 0 ] || { echo "RESULT=FAIL APK 无效"; exit 1; }

case "$TARGETS" in
  A|a) LIST="A" ;;
  B|b) LIST="B" ;;
  both) LIST="A B" ;;
  *) echo "ERROR: --target 只能是 A|B|both" >&2; exit 2 ;;
esac

FAIL=0; OK=0
for ROLE in $LIST; do
  SER="$(e2e_resolve_serial "$ROLE")"
  echo
  echo "----- 目标 $ROLE ($SER) -----"
  if ! e2e_is_device "$SER"; then
    echo "ERROR: 设备不可用"; FAIL=$((FAIL + 1)); continue
  fi
  if [ "$DO_UNINSTALL" = 1 ]; then
    echo "uninstall $PKG: $("$ADB" -s "$SER" uninstall "$PKG" 2>&1 | tr -d '\r')"
  fi
  WIN_APK="$(e2e_win_path "$APK")"
  echo "install: $("$ADB" -s "$SER" install -r -t "$WIN_APK" 2>&1 | tr -d '\r' | tr '\n' ' ')"
  # 校验安装结果（install 输出可能被 OEM 改写过）
  if "$ADB" -s "$SER" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"; then
    echo "pm_list: $("$ADB" -s "$SER" shell pm list packages 2>/dev/null | tr -d '\r' | grep "$PKG")"
    echo "version: $("$ADB" -s "$SER" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' | grep -E 'versionName|versionCode' | head -2 | tr '\n' ' ')"
    OK=$((OK + 1))
    if [ "$DO_LAUNCH" = 1 ]; then
      echo "launch: $("$ADB" -s "$SER" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 2>&1 | tr -d '\r' | tail -1)"
    fi
  else
    echo "ERROR: 安装后未发现包 $PKG"
    FAIL=$((FAIL + 1))
  fi
done

echo
echo "RESULT=$([ "$FAIL" = 0 ] && echo PASS || echo FAIL) ok=$OK fail=$FAIL log=$LOG"
[ "$FAIL" = 0 ] && exit 0 || exit 1
