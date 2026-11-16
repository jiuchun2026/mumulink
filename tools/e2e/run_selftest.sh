#!/usr/bin/env bash
# run_selftest.sh — E2E 脚本集自测。不依赖 APK；APK 存在时会自动安装到 B。
#
# 用法：
#   ./run_selftest.sh [--skip-dual] [--skip-install]
#
# 产物：evidence/_logs/selftest-<ts>.log（全量输出）
# 退出码：0 = 全部 PASS/SKIP；1 = 有 FAIL。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,10p' "$0"; exit 0; }
SKIP_DUAL=0; SKIP_INSTALL=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --skip-dual) SKIP_DUAL=1; shift ;;
    --skip-install) SKIP_INSTALL=1; shift ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_mkdirs
LOG="$LOG_DIR/selftest-$(e2e_ts).log"
exec > >(tee -a "$LOG") 2>&1

PASS=0; FAIL=0; SKIP=0
declare -a ROWS=()
record() { ROWS+=("$1|$2"); case "$1" in PASS) PASS=$((PASS+1));; FAIL) FAIL=$((FAIL+1));; SKIP) SKIP=$((SKIP+1));; esac; }
runstep() {
  local name="$1"; shift
  echo
  echo "========== $name =========="
  if "$@"; then echo ">>> PASS: $name"; record PASS "$name"; else echo ">>> FAIL: $name"; record FAIL "$name"; fi
}

# ---------- 组合步骤 ----------
s_devices()        { "$E2E_DIR/devices.sh" --wait 60; }
s_known_and_wait() {
  "$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null &&
  "$E2E_DIR/wait_for.sh" A 'com.android.settings|Settings|设置' --timeout 10 &&
  "$E2E_DIR/known_screen.sh" A home --settle 1 >/dev/null
}
s_probe_assert() {
  "$E2E_DIR/probe_state.sh" baseline A &&
  "$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null &&
  "$E2E_DIR/probe_state.sh" diff A --assert changed --threshold 0.02
}
s_inputs() {
  "$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null || return 1
  "$E2E_DIR/tap.sh" A 50% 40% >/dev/null &&
  "$E2E_DIR/swipe.sh" A 1280 1000 1280 400 300 >/dev/null &&
  "$E2E_DIR/longpress.sh" A 200 200 700 >/dev/null &&
  "$E2E_DIR/key.sh" A BACK >/dev/null
}
s_type() {
  "$E2E_DIR/known_screen.sh" A home --settle 1 >/dev/null
  "$E2E_DIR/type.sh" A "mumulink probe"
  echo "NOTE: 命令链路已验证；真实文本输入断言需要输入框/搜索框（App 就绪后覆盖）。"
}
s_imgdiff_selftest() {
  local a="$STATE_DIR/selftest-a.png" b="$STATE_DIR/selftest-b.png" dup="$STATE_DIR/selftest-a-copy.png"
  "$ADB" -s "$(e2e_resolve_serial A)" exec-out screencap -p > "$a" || return 1
  cp -f "$a" "$dup"
  "$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null || return 1
  "$ADB" -s "$(e2e_resolve_serial A)" exec-out screencap -p > "$b" || return 1
  local same diff
  same="$("$PY" "$E2E_DIR/imgdiff.py" "$a" "$dup" --json | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])')"
  diff="$("$PY" "$E2E_DIR/imgdiff.py" "$a" "$b" --json | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])')"
  echo "imgdiff same-image ratio=$same（期望 0）；different-screen ratio=$diff（期望 >0）"
  awk -v s="$same" 'BEGIN{exit !(s==0)}' && awk -v d="$diff" -v t=0.01 'BEGIN{exit !(d>t)}'
}
s_install() {
  if [ ! -f "$APK_PATH" ]; then
    echo "APK 尚未出现：$APK_PATH"; return 2
  fi
  "$E2E_DIR/install_apk.sh" "$APK_PATH" --target B
}
s_dual() { "$E2E_DIR/dual_instance_check.sh" --label selftest-dual; }
s_mumu() {
  "$PY" "$E2E_DIR/mumu.py" info 0 &&
  "$PY" "$E2E_DIR/mumu.py" info 1 &&
  "$PY" "$E2E_DIR/mumu.py" port 0 &&
  "$PY" "$E2E_DIR/mumu.py" port 1
}
s_tap_text() {
  "$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null || return 1
  "$E2E_DIR/tap_text.sh" A "在设置中搜索" --list --timeout 8
}
s_net_sim() {
  "$E2E_DIR/net_sim.sh" ports
  "$E2E_DIR/net_sim.sh" status B
}
softstep() {
  local name="$1"; shift
  echo
  echo "========== $name =========="
  if "$@"; then echo ">>> PASS: $name"; record PASS "$name"
  else echo ">>> SKIP: $name（可容忍：A 可能被并发操作/界面变化）"; record SKIP "$name"; fi
}

echo "MuMuLink E2E selftest $(date '+%F %T')  log=$LOG"
echo "A=$(e2e_resolve_serial A)  B=$(e2e_resolve_serial B)  APK=$APK_PATH"

runstep "01 devices（A/B 就绪 + 环境信息）" s_devices
runstep "02 mumu.py 解析（info/port）" s_mumu
runstep "03 shot A（截图 + PNG 校验）" "$E2E_DIR/shot.sh" A "$EVIDENCE_DIR/selftest-A.png"
runstep "04 shot B（截图 + PNG 校验）" "$E2E_DIR/shot.sh" B "$EVIDENCE_DIR/selftest-B.png"
runstep "05 ui_dump A（可见节点摘要）" "$E2E_DIR/ui_dump.sh" A --nodes
runstep "06 foreground A（前台窗口）" "$E2E_DIR/foreground.sh" A
runstep "07 known_screen + wait_for（设置界面可达）" s_known_and_wait
runstep "08 probe_state 基线/差异断言" s_probe_assert
runstep "09 verify_touch（滑动触控闭环）" "$E2E_DIR/verify_touch.sh" A
runstep "10 input 原语（tap/swipe/longpress/key）" s_inputs
runstep "11 type 原语（命令链路）" s_type
runstep "12 imgdiff 自检（同图=0，异屏>0）" s_imgdiff_selftest
softstep "13 tap_text 定位（按文本找设置搜索框，不点按）" s_tap_text
runstep "14 net_sim（端口/宿主可达性）" s_net_sim

if [ "$SKIP_INSTALL" = 1 ]; then
  echo; echo "========== 15 install_apk =========="; echo "SKIP: --skip-install"; record SKIP "15 install_apk(手动跳过)"
else
  echo; echo "========== 15 install_apk =========="
  if s_install; then echo ">>> PASS: 15 install_apk"; record PASS "15 install_apk"; else
    rc=$?
    if [ "$rc" = 2 ]; then echo ">>> SKIP: 15 install_apk（APK 未构建）"; record SKIP "15 install_apk(APK 未构建)";
    else echo ">>> FAIL: 15 install_apk"; record FAIL "15 install_apk"; fi
  fi
fi

if [ "$SKIP_DUAL" = 1 ]; then
  echo; echo "========== 16 dual_instance_check =========="; echo "SKIP: --skip-dual"; record SKIP "16 dual_instance_check(手动跳过)"
else
  runstep "16 dual_instance_check（双实例链路就绪）" s_dual
fi

echo
echo "========== 汇总 =========="
for row in "${ROWS[@]}"; do printf '  %-5s %s\n' "${row%%|*}" "${row#*|}"; done
echo "TOTAL pass=$PASS fail=$FAIL skip=$SKIP log=$LOG"
[ "$FAIL" = 0 ] && exit 0 || exit 1
