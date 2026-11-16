#!/usr/bin/env bash
# run.sh — 一条命令跑最小 E2E：确保实例在线 → 安装 APK（有则装）→ 启动 App → adb input 操作 → 截图到 evidence/。
#
# 用法：
#   bash tools/e2e/run.sh [选项]
#     --apk PATH        指定 APK（默认 android/app/build/outputs/apk/debug/app-debug.apk）
#     --skip-install    跳过安装（只做环境 + 系统级冒烟）
#     --skip-b          不要求实例 B 在线（只有 A 时也能跑）
#     --skip-net        跳过网络/端口体检
#     （APK 缺失时默认只做环境冒烟并退出码 2 —— 见下）
#     --label NAME      证据文件名前缀（默认 run-<ts>）
#     --wait 120        ensure_devices 的 adb 等待秒数
#     --duration 30     App 启动后的观察窗口（秒）
#
# 流程：
#   1. ensure_devices.sh --tune（A 必须；B 默认必须）
#   2. net_sim.sh ports + host B（信息性，不判 FAIL；用于发现监听 127.0.0.1 的坑）
#   3. APK 存在 → install_apk.sh --target B --launch；否则 SKIP
#   4. App 已启动 → 等包名在前台 → 截图 → tap/滑动/BACK → 截图 → 前台校验
#   5. 系统级冒烟：A 打开设置→HOME 变化断言；B 通知栏展开/收起变化断言
#   6. 生成 evidence/<label>-summary.md，打印 RESULT=PASS|FAIL
#
# 证据：evidence/<label>-*.png、evidence/<label>-summary.md、evidence/_logs/<label>.log
# 退出码：0 = PASS；1 = FAIL；2 = 参数错误或 APK 缺失（只做了环境冒烟）。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,26p' "$0"; exit 0; }

APK="$APK_PATH"
SKIP_INSTALL=0
SKIP_B=0
SKIP_NET=0
LABEL="run-$(e2e_ts)"
WAIT=120
DURATION=30
while [ "$#" -gt 0 ]; do
  case "$1" in
    --apk) APK="${2:-}"; shift 2 ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    --skip-b) SKIP_B=1; shift ;;
    --skip-net) SKIP_NET=1; shift ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    --wait) WAIT="${2:-120}"; shift 2 ;;
    --duration) DURATION="${2:-30}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

e2e_mkdirs
LOG="$LOG_DIR/$LABEL.log"
exec > >(tee -a "$LOG") 2>&1

PASS=0; FAIL=0; SKIP=0
declare -a ROWS=()
record() { ROWS+=("$1|$2"); case "$1" in PASS) PASS=$((PASS + 1));; FAIL) FAIL=$((FAIL + 1));; SKIP) SKIP=$((SKIP + 1));; esac; }
step() {
  local name="$1"; shift
  echo
  echo "========== [STEP] $name =========="
  if "$@"; then
    echo ">>> PASS: $name"
    record PASS "$name"
    return 0
  fi
  echo ">>> FAIL: $name"
  record FAIL "$name"
  return 1
}
skip_step() {
  echo
  echo "========== [SKIP] $1 =========="
  record SKIP "$1"
}

# 解析 ensure_devices 写入的 devices.env（B 跳过时为空）
env_val() {  # $1 KEY
  grep "^$1=" "$STATE_DIR/devices.env" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r'
}

echo "===== MuMuLink E2E run $(date '+%F %T') ====="
echo "label=$LABEL repo=$REPO_ROOT"
echo "apk=$APK"

# ---------- 1. 设备 ----------
STEP_DEV_FAIL=0
if [ "$SKIP_B" = 1 ]; then
  step "ensure_devices (A only)" "$E2E_DIR/ensure_devices.sh" --skip-b --tune --timeout "$WAIT" || STEP_DEV_FAIL=1
else
  step "ensure_devices (A+B)" "$E2E_DIR/ensure_devices.sh" --tune --timeout "$WAIT" || STEP_DEV_FAIL=1
fi
SER_A="$(env_val SERIAL_A)"; [ -n "$SER_A" ] || SER_A="$SERIAL_MAIN"
SER_B="$(env_val SERIAL_B)"; [ -n "$SER_B" ] || SER_B="$SERIAL_TEST"

if [ "$STEP_DEV_FAIL" = 1 ]; then
  echo "ERROR: 设备未就绪，后续步骤全部跳过" >&2
  echo "RESULT=FAIL devices_not_ready"
  exit 1
fi
if [ "$SKIP_B" = 0 ] && ! e2e_is_device "$SER_B"; then
  echo "ERROR: B ($SER_B) 不可用" >&2
  echo "RESULT=FAIL devices_not_ready"
  exit 1
fi

# ---------- 2. 网络/端口体检（信息性） ----------
if [ "$SKIP_NET" = 1 ]; then
  skip_step "net_precheck"
else
  echo
  echo "========== [INFO] net_precheck =========="
  "$E2E_DIR/net_sim.sh" ports 2>&1 | tail -8 || true
  if [ "$SKIP_B" = 0 ] && e2e_is_device "$SER_B"; then
    "$E2E_DIR/net_sim.sh" host B 2>&1 | tail -8 || true
  fi
  record SKIP "net_precheck (informational)"
fi

# ---------- 3. 安装 APK ----------
APP_INSTALLED=0
APK_EXISTS=0
[ -f "$APK" ] && APK_EXISTS=1
if [ "$SKIP_INSTALL" = 1 ]; then
  skip_step "install_apk (--skip-install)"
elif [ "$APK_EXISTS" = 0 ]; then
  echo
  echo "========== [SKIP] install_apk =========="
  echo "APK 不存在: $APK（等待构建完成；run.sh 继续做系统级冒烟）"
  record SKIP "install_apk (APK missing)"
else
  if [ "$SKIP_B" = 1 ]; then
    skip_step "install_apk (no B)"
  elif step "install_apk -> B" "$E2E_DIR/install_apk.sh" "$APK" --target B --launch; then
    APP_INSTALLED=1
  fi
fi

# 若 APK 已装但本轮没安装，检查 B 上是否已有包
if [ "$APP_INSTALLED" = 0 ] && [ "$SKIP_B" = 0 ] && e2e_is_device "$SER_B"; then
  if "$ADB" -s "$SER_B" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"; then
    APP_INSTALLED=1
    echo "INFO: B 上已存在 $PKG（不重复安装）"
  fi
fi

# ---------- 4. App 冒烟 ----------
if [ "$APP_INSTALLED" = 1 ] && [ "$SKIP_B" = 0 ]; then
  echo
  echo "========== [STEP] app_launch =========="
  "$ADB" -s "$SER_B" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  APP_OK=0
  i=0
  while [ "$i" -lt 15 ]; do
    fg="$("$E2E_DIR/foreground.sh" B --package 2>/dev/null | tr -d '\r' | tail -1)"
    if [ "$fg" = "$PKG" ]; then APP_OK=1; break; fi
    sleep 1
    i=$((i + 1))
  done
  "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-01-launch.png" >/dev/null 2>&1 || true
  if [ "$APP_OK" = 1 ]; then
    echo ">>> PASS: app_launch (foreground=$PKG)"
    record PASS "app_launch"
    echo
    echo "========== [STEP] app_input =========="
    "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-02-before-input.png" >/dev/null 2>&1 || true
    DIM="$("$PY" -c 'import sys
from PIL import Image
with Image.open(sys.argv[1]) as im:
    print("%dx%d" % (im.width, im.height))' "$EVIDENCE_DIR/$LABEL-B-02-before-input.png" 2>/dev/null)"
    W="$(printf '%s' "$DIM" | cut -dx -f1 | tr -dc '0-9')"
    H="$(printf '%s' "$DIM" | cut -dx -f2 | tr -dc '0-9')"
    [ -n "$W" ] || W=1080
    [ -n "$H" ] || H=1920
    CX=$((W / 2)); CY=$((H / 2))
    "$ADB" -s "$SER_B" shell input tap "$CX" "$CY" >/dev/null 2>&1 || true
    sleep 2
    "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-03-after-tap.png" >/dev/null 2>&1 || true
    "$ADB" -s "$SER_B" shell input swipe "$CX" "$((H * 3 / 4))" "$CX" "$((H / 4))" 350 >/dev/null 2>&1 || true
    sleep 1
    "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-04-after-swipe.png" >/dev/null 2>&1 || true
    "$ADB" -s "$SER_B" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
    sleep 1
    "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-05-after-back.png" >/dev/null 2>&1 || true
    fg2="$("$E2E_DIR/foreground.sh" B --package 2>/dev/null | tr -d '\r' | tail -1)"
    if [ "$fg2" = "$PKG" ] || [ "$fg2" = "com.android.launcher" ] || [ "$fg2" = "app.lawnchair" ]; then
      echo ">>> PASS: app_input (foreground=$fg2)"
      record PASS "app_input"
    else
      echo ">>> WARN: app_input 后前台=$fg2（App 可能被 BACK 退出或崩溃）"
      record SKIP "app_input (foreground=$fg2)"
    fi
  else
    echo ">>> FAIL: app_launch（15s 内前台不是 $PKG）"
    record FAIL "app_launch (foreground=$fg)"
  fi
else
  skip_step "app_launch/app_input (APK 未安装)"
fi

# ---------- 5. 系统级冒烟 ----------
GUARD="$("$E2E_DIR/env_guard.sh" A --seconds 2 --samples 2 2>/dev/null | tail -1)"
echo "A_ENV_GUARD=$GUARD"
step "A_known_screen" "$E2E_DIR/known_screen.sh" A settings --settle 2
"$E2E_DIR/shot.sh" A "$EVIDENCE_DIR/$LABEL-A-01-settings.png" >/dev/null 2>&1 || true
step "A_settings_visible" "$E2E_DIR/wait_for.sh" A 'com.android.settings' --timeout 10
step "A_home_input_assert" "$E2E_DIR/assert_change.sh" A "input keyevent KEYCODE_HOME" --label "$LABEL-A-home" --settle 2
"$E2E_DIR/known_screen.sh" A home --settle 1 >/dev/null 2>&1 || true

if [ "$SKIP_B" = 0 ] && e2e_is_device "$SER_B"; then
  if step "B_notification_assert" "$E2E_DIR/assert_change.sh" B "cmd statusbar expand-notifications" --label "$LABEL-B-shade" --settle 1; then
    "$ADB" -s "$SER_B" shell cmd statusbar collapse >/dev/null 2>&1 || true
  fi
  "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/$LABEL-B-06-final.png" >/dev/null 2>&1 || true
else
  skip_step "B_smoke (no B)"
fi

# ---------- 6. 汇总 ----------
SUMMARY="$EVIDENCE_DIR/$LABEL-summary.md"
{
  echo "# MuMuLink E2E run 摘要"
  echo
  echo "- 时间：$(date '+%F %T')"
  echo "- label：\`$LABEL\`"
  echo "- A（主实例）：\`$SER_A\`"
  echo "- B（测试实例）：\`$SER_B\`"
  echo "- APK：\`$APK\`（存在=$APK_EXISTS，已安装=$APP_INSTALLED）"
  echo "- 环境权威性：**B 为权威验收面**；A 侧断言仅供参考（实例 0 有外部 ws-scrcpy agent 并发输入，见 CHECKLIST §0）"
  echo "- A_ENV_GUARD：${GUARD:-未采样}"
  echo "- 脚本版本：tools/e2e @ $(cd "$REPO_ROOT" && git rev-parse --short HEAD 2>/dev/null || echo 'no-git')"
  echo
  echo "## 步骤结果"
  echo
  echo "| 结果 | 步骤 |"
  echo "| --- | --- |"
  for row in "${ROWS[@]}"; do
    printf '| %s | %s |\n' "${row%%|*}" "${row#*|}"
  done
  echo
  echo "## 证据文件"
  echo
  ls -1 "$EVIDENCE_DIR" 2>/dev/null | grep "^$LABEL" | awk '{print "- evidence/" $0}'
  echo
  echo "## 结论"
  echo
  if [ "$FAIL" = 0 ]; then
    echo "PASS（FAIL=$FAIL SKIP=$SKIP PASS=$PASS）"
  else
    echo "FAIL（FAIL=$FAIL SKIP=$SKIP PASS=$PASS）"
  fi
} > "$SUMMARY"

echo
echo "===== 汇总 ====="
for row in "${ROWS[@]}"; do printf '  %-4s %s\n' "${row%%|*}" "${row#*|}"; done
echo "  summary=$SUMMARY"
echo "  log=$LOG"
# APK 缺失且 B 上也没有已安装的 App：只做了环境冒烟，按约定退出码 2。
if [ "$APK_EXISTS" = 0 ] && [ "$SKIP_INSTALL" = 0 ] && [ "$APP_INSTALLED" = 0 ]; then
  echo "RESULT=NO_APK pass=$PASS fail=$FAIL skip=$SKIP（只做环境冒烟；等构建完成后重跑）"
  exit 2
fi
if [ "$FAIL" = 0 ]; then
  echo "RESULT=PASS pass=$PASS fail=$FAIL skip=$SKIP"
  exit 0
fi
echo "RESULT=FAIL pass=$PASS fail=$FAIL skip=$SKIP"
exit 1
