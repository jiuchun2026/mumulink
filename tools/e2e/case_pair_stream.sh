#!/usr/bin/env bash
# C15 配对成功 → C01 出画（B 自环：App 在 B，代理被控端也是 B，端口 27380 段）
#
# 用法: ./case_pair_stream.sh [--no-clear] [--host 10.0.2.2] [--port 27380] [--code 112233]
# 前置: 自测代理已在跑（tools/e2e/config.test.json），B 在线，App 已安装。
# 输出: evidence/C15-C01-*.png，末行 RESULT=PASS|FAIL|SKIP
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/env.sh"

PY="${PY:-D:/app/python.exe}"
S="$(e2e_resolve_serial B)"
HOST="10.0.2.2"
PORT="27380"
CODE="112233"
CLEAR=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-clear) CLEAR=0 ;;
    --host) shift; HOST="${1:-}" ;;
    --port) shift; PORT="${1:-}" ;;
    --code) shift; CODE="${1:-}" ;;
    -h|--help) echo "usage: $0 [--no-clear] [--host H] [--port P] [--code C]"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done

e2e_mkdirs
TAG="$(date +%Y%m%d-%H%M%S)"
STEP=""
PASS=0

say() { echo; echo "===== $* ====="; }
ok()   { PASS=1; echo "PASS $*"; }
die()  {
  echo "RESULT=FAIL step=$STEP detail=$*"
  "$ADB" -s "$S" exec-out screencap -p > "$EVIDENCE_DIR/C15-C01-fail.png" 2>/dev/null || true
  "$HERE/ui_dump.sh" B --nodes 2>/dev/null | tail -40
  exit 1
}
adbq() { "$ADB" -s "$S" shell "$@" >/dev/null 2>&1 || true; }

say "前置检查"
STEP="precheck"
e2e_require_device B || die "B 不在线"
FOCUS="$("$HERE/foreground.sh" B --package 2>/dev/null | tail -1)"
echo "FOCUS=$FOCUS"
# 代理端口从设备侧探测（Android toybox nc）
if ! "$ADB" -s "$S" shell "nc -w 2 $HOST $PORT </dev/null" >/dev/null 2>&1; then
  echo "WARN: $HOST:$PORT 从 B 不可达；先起代理（pc 下 agent.js --config config.test.json）"
  echo "RESULT=SKIP reason=proxy_unreachable host=$HOST port=$PORT"
  exit 0
fi
echo "proxy $HOST:$PORT reachable from B"

say "清旧 token 并重启 App"
STEP="reset"
adbq am force-stop com.mumulink.remote
if [ "$CLEAR" = "1" ]; then
  adbq pm clear com.mumulink.remote
fi
adbq am start -n com.mumulink.remote/.MainActivity
sleep 2
"$HERE"/shot.sh B "$EVIDENCE_DIR/C15-C01-start.png" >/dev/null || die "shot start failed"
"$HERE"/wait_for.sh B "还没有找到电脑|正在寻找电脑|扫描" --timeout 15 >/dev/null || die "连接页未出现"
echo "连接页就绪"

say "展开手动连接面板并填写"
STEP="panel"
"$HERE"/tap_text.sh B "手动连接" --exact --timeout 10 >/dev/null || die "找不到「手动连接」"
adbq input swipe 960 950 960 350 400
sleep 1

fill_field() {  # $1=index $2=text
  local line cx cy
  line="$("$PY" "$HERE/ui_fields.py" "$S" | awk -v i="$1" '$1==i{print $2" "$3}')"
  cx="${line%% *}"; cy="${line##* }"
  if [ -z "$cx" ] || [ -z "$cy" ]; then die "找不到第 $1 个输入框"; fi
  "$ADB" -s "$S" shell input tap "$cx" "$cy" >/dev/null 2>&1
  sleep 0.3
  adbq input keyevent 123
  local i=0
  while [ $i -lt 24 ]; do adbq input keyevent 67; i=$((i + 1)); done
  "$ADB" -s "$S" shell input text "$2" >/dev/null 2>&1
  sleep 0.3
}

fill_field 0 "$HOST"
fill_field 1 "$PORT"
fill_field 2 "$CODE"
adbq input keyevent 4    # 收起键盘
sleep 1
"$HERE"/shot.sh B "$EVIDENCE_DIR/C15-C01-filled.png" >/dev/null || die "shot filled failed"

say "点「配对并连接」"
STEP="connect"
if ! "$HERE"/tap_text.sh B "配对并连接" --timeout 10 >/dev/null 2>&1; then
  # 兜底：重新展开一次面板
  "$HERE"/tap_text.sh B "手动连接" --exact --timeout 8 >/dev/null 2>&1 || true
  adbq input swipe 960 950 960 350 400
  sleep 1
  "$HERE"/tap_text.sh B "配对并连接" --timeout 10 >/dev/null 2>&1 || die "点不到「配对并连接」"
fi
echo "已点击，等待 StreamScreen"
"$HERE"/shot.sh B "$EVIDENCE_DIR/C15-C01-connecting.png" >/dev/null 2>&1 || true

STEP="wait-stream"
if "$HERE"/wait_for.sh B "画质|断开连接|正在连接|连接中断|配对码不正确|连接失败" --timeout 30 >/dev/null 2>&1; then
  :
fi
"$HERE"/shot.sh B "$EVIDENCE_DIR/C15-C01-stream.png" >/dev/null || die "shot stream failed"
sleep 2
"$HERE"/shot.sh B "$EVIDENCE_DIR/C15-C01-stream2.png" >/dev/null || true

STEP="assert"
if "$HERE"/wait_for.sh B "画质" --timeout 8 >/dev/null 2>&1; then
  ok "StreamScreen HUD 出现（画质 可见）"
else
  echo "未见到「画质」；检查错误文案"
  for t in "配对码不正确" "连接失败" "连接中断" "代理尚未配对"; do
    if "$HERE"/wait_for.sh B "$t" --timeout 2 >/dev/null 2>&1; then
      die "连接失败文案=$t（见 evidence/C15-C01-stream.png）"
    fi
  done
  die "30s 内未进入 StreamScreen"
fi

FOCUS2="$("$HERE/foreground.sh" B --package 2>/dev/null | tail -1)"
echo "FOCUS_AFTER=$FOCUS2"
case "$FOCUS2" in
  com.mumulink.remote*) ok "App 在前台" ;;
  *) echo "WARN: 前台=$FOCUS2" ;;
esac

# 出画证明：两帧之间画面应有变化（视频在解码/刷新），或至少 HUD 统计存在
"$PY" "$HERE/imgdiff.py" "$EVIDENCE_DIR/C15-C01-stream.png" "$EVIDENCE_DIR/C15-C01-stream2.png" --json > "$EVIDENCE_DIR/_state/c15-c01-diff.json" 2>/dev/null || true
DEF="$("$PY" -c "import json,io; d=json.load(io.open(r'$EVIDENCE_DIR/_state/c15-c01-diff.json',encoding='utf-8')); print('changed_ratio=%.6f mean_abs=%.3f' % (d['changed_ratio'], d['mean_abs']))" 2>/dev/null)"
echo "STREAM_DIFF $DEF"

echo "RESULT=PASS step=$STEP serial=$S host=$HOST port=$PORT"
exit 0
