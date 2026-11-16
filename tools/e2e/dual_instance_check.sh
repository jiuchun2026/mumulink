#!/usr/bin/env bash
# dual_instance_check.sh — 双实例互验链路就绪检查（不依赖 APK）。
#
# 背景：最终验收形态是——实例 B 上的 App 播放实例 A 的画面（视频流解码）；
#       在 B 的 App 画面上点按 → 指令转发到 A → A 界面变化 → A 截图断言变化，
#       同时 B 截图证明解码正常（无花屏/黑屏）。
#
# 本脚本在 App 就绪前验证同一链路的“基础设施段”：
#   1. A 可切已知界面并截到可解码的 PNG（模拟“A 的画面流”）
#   2. A 的界面切换能被截图差异断言捕获（画面变化 → 差异比例）
#   3. B 的输入（系统手势）可下发且能被截图差异断言捕获（证明 B 侧操作可观测）
#   4. A 不受 B 操作影响（当前基线；App 就绪后第 4 步改为“B 点按 → A 变化”）
#
# 用法：
#   ./dual_instance_check.sh [--threshold 0.01] [--label dual]
#
# 证据：evidence/<label>-1-A-home.png、-2-A-settings.png、-3-B-home.png、
#       -4-B-notifications.png、-5-A-after.png、-stream-frame.png
# 退出码：0 = 全部 PASS；1 = 有 FAIL。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,22p' "$0"; exit 0; }

THRESHOLD="0.01"; LABEL="dual-$(date +%H%M%S)"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --threshold) THRESHOLD="${2:-0.01}"; shift 2 ;;
    --label) LABEL="${2:-}"; shift 2 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done
e2e_require_device A || exit 1
e2e_require_device B || exit 1
e2e_mkdirs

PASS=0; FAIL=0
declare -a SUMMARY=()
mark() { SUMMARY+=("$1"); if [ "$1" = PASS ]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi; }
ratio() { "$PY" "$E2E_DIR/imgdiff.py" "$1" "$2" --json | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["changed_ratio"])'; }
ge() { awk -v r="$1" -v t="$2" 'BEGIN{exit !(r>=t)}'; }
lt() { awk -v r="$1" -v t="$2" 'BEGIN{exit !(r<t)}'; }

F1="$EVIDENCE_DIR/${LABEL}-1-A-home.png"
F2="$EVIDENCE_DIR/${LABEL}-2-A-settings.png"
F3="$EVIDENCE_DIR/${LABEL}-3-B-home.png"
F4="$EVIDENCE_DIR/${LABEL}-4-B-notifications.png"
F5="$EVIDENCE_DIR/${LABEL}-5-A-after.png"

echo "===== [1/5] A 切桌面并截图（模拟画面流首帧） ====="
"$E2E_DIR/known_screen.sh" A home --settle 1 >/dev/null 2>&1
sleep 1
"$ADB" -s "$(e2e_resolve_serial A)" exec-out screencap -p > "$F1" || mark FAIL
[ -s "$F1" ] && echo "  frame=$(e2e_png_size "$F1") $F1" && mark PASS
cp -f "$F1" "$EVIDENCE_DIR/${LABEL}-stream-frame.png"
echo "  stream_frame=$EVIDENCE_DIR/${LABEL}-stream-frame.png"

echo "===== [2/5] A 切设置，断言画面变化可被捕获 ====="
"$E2E_DIR/known_screen.sh" A settings --settle 2 >/dev/null 2>&1
sleep 1
"$ADB" -s "$(e2e_resolve_serial A)" exec-out screencap -p > "$F2" || mark FAIL
R2="$(ratio "$F1" "$F2")"
echo "  A home->settings changed_ratio=$R2 threshold=$THRESHOLD"
if ge "$R2" "$THRESHOLD"; then echo "  => A 画面变化断言成立"; mark PASS; else echo "  => A 画面变化不足"; mark FAIL; fi

echo "===== [3/5] B 切桌面并截图（证明 B 侧画面可解码） ====="
"$E2E_DIR/known_screen.sh" B home --settle 1 >/dev/null 2>&1
sleep 1
"$ADB" -s "$(e2e_resolve_serial B)" shell cmd statusbar collapse >/dev/null 2>&1
"$ADB" -s "$(e2e_resolve_serial B)" exec-out screencap -p > "$F3" || mark FAIL
[ -s "$F3" ] && echo "  frame=$(e2e_png_size "$F3") $F3" && mark PASS

echo "===== [4/5] B 触发系统输入（拉通知栏），断言 B 画面变化 ====="
"$ADB" -s "$(e2e_resolve_serial B)" shell cmd statusbar expand-notifications >/dev/null 2>&1
sleep 1.5
"$ADB" -s "$(e2e_resolve_serial B)" exec-out screencap -p > "$F4" || mark FAIL
R4="$(ratio "$F3" "$F4")"
echo "  B home->notifications changed_ratio=$R4 threshold=$THRESHOLD"
if ge "$R4" "$THRESHOLD"; then echo "  => B 侧输入可观测"; mark PASS; else echo "  => B 侧输入不可观测"; mark FAIL; fi
"$ADB" -s "$(e2e_resolve_serial B)" shell cmd statusbar collapse >/dev/null 2>&1

echo "===== [5/5] B 操作后 A 保持不变（当前基线） ====="
sleep 1
"$ADB" -s "$(e2e_resolve_serial A)" exec-out screencap -p > "$F5" || mark FAIL
R5="$(ratio "$F2" "$F5")"
echo "  A settings before/after B 操作 changed_ratio=$R5（期望 < 0.002）"
if lt "$R5" 0.002; then echo "  => 两实例当前相互独立"; mark PASS; else echo "  => A 发生了意外变化（检查是否有投屏/转发在跑）"; mark FAIL; fi

echo
echo "===== 汇总 ====="
i=1
for s in "${SUMMARY[@]}"; do echo "  step$i: $s"; i=$((i+1)); done
echo "RESULT=$([ "$FAIL" = 0 ] && echo PASS || echo FAIL) pass=$PASS fail=$FAIL label=$LABEL"
echo "说明：App 就绪后，真实链路断言为「B 上点按 → A 变化」，可直接复用 assert_change.sh。"
[ "$FAIL" = 0 ] && exit 0 || exit 1
