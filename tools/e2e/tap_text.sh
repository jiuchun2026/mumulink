#!/usr/bin/env bash
# tap_text.sh — 在 uiautomator UI 里按文本/content-desc/resource-id 定位节点并点按（或列出）。
#
# 用法：
#   ./tap_text.sh <serial|A|B> <pattern> [选项]
#     --field text|desc|id|any   匹配哪个属性（默认 any）
#     --exact                    完全匹配（默认子串/正则搜索）
#     --ignore-case              忽略大小写（实现为给 pattern 加 (?i) 前缀）
#     --index N                  多个匹配时点第 N 个（默认 0）
#     --list                     只列出全部匹配，不点按
#     --dry-run                  只打印将要点的坐标
#     --timeout 10               等待匹配出现的秒数
#     --settle 0.4               点按后等待秒数
#
# 例：
#   ./tap_text.sh B "连接" --timeout 15
#   ./tap_text.sh B "设置" --field text --index 1
#   ./tap_text.sh A "android.settings.SETTINGS" --field id --list
#
# 退出码：0 = 找到（且已点）；1 = 超时未匹配；2 = 参数错误；3 = dump 失败。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"
[ "${1:-}" = "-h" ] && { sed -n '2,22p' "$0"; exit 0; }

POS=(); FIELD=any; EXACT=0; ICASE=0; INDEX=0; LISTMODE=0; DRY=0; TIMEOUT=10; SETTLE=0.4
while [ "$#" -gt 0 ]; do
  case "$1" in
    --field) FIELD="${2:-any}"; shift 2 ;;
    --exact) EXACT=1; shift ;;
    --ignore-case) ICASE=1; shift ;;
    --index) INDEX="${2:-0}"; shift 2 ;;
    --list) LISTMODE=1; shift ;;
    --dry-run) DRY=1; shift ;;
    --timeout) TIMEOUT="${2:-10}"; shift 2 ;;
    --settle) SETTLE="${2:-0.4}"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    --) shift; while [ "$#" -gt 0 ]; do POS+=("$1"); shift; done ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *) POS+=("$1"); shift ;;
  esac
done
[ "${#POS[@]}" -lt 2 ] && { sed -n '2,22p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "${POS[0]}")"
PATTERN="${POS[1]}"
e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs

XML="$STATE_DIR/tap-$(e2e_ts).xml"
start=$(date +%s)
while :; do
  # 优先文件式 dump：/dev/tty 的输出尾部会混入 "UI hierchary dumped to..." 之类文字，
  # 直接 ET.parse 会报 "junk after document element"。文件式 XML 更干净。
  "$ADB" -s "$SERIAL" shell rm -f /sdcard/window_dump.xml >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell cat /sdcard/window_dump.xml > "$XML" 2>/dev/null
  if ! head -c 5 "$XML" 2>/dev/null | grep -q '<?xml'; then
    "$ADB" -s "$SERIAL" exec-out uiautomator dump /dev/tty > "$XML" 2>/dev/null || true
  fi
  if ! head -c 5 "$XML" 2>/dev/null | grep -q '<?xml'; then
    if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
      echo "ERROR: 无法获取 UI XML（serial=$SERIAL）" >&2
      exit 3
    fi
    sleep 1
    continue
  fi

  RESULT="$("$PY" - "$XML" "$PATTERN" "$FIELD" "$EXACT" "$ICASE" "$INDEX" "$LISTMODE" <<'PYEOF'
import re
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
sys.stderr.reconfigure(encoding="utf-8", errors="replace")
path, pattern, field, exact, icase, index, listmode = sys.argv[1:8]
index = int(index)
listmode = int(listmode)
fields = {
    "text": ["text"],
    "desc": ["content-desc"],
    "id": ["resource-id"],
    "any": ["text", "content-desc", "resource-id"],
}.get(field.lower())
if fields is None:
    sys.stderr.write("ERROR: --field 只能是 text|desc|id|any\n")
    sys.exit(2)
try:
    with open(path, encoding="utf-8", errors="replace") as fh:
        raw = fh.read()
    start = raw.find("<?xml")
    if start < 0:
        start = raw.find("<hierarchy")
    end = raw.rfind("</hierarchy>")
    if start < 0 or end < 0:
        raise ValueError("找不到 hierarchy 根节点")
    root = ET.fromstring(raw[start:end + len("</hierarchy>")])
except Exception as exc:  # XML 被截断/损坏
    sys.stderr.write("ERROR: XML 解析失败: %s\n" % exc)
    sys.exit(3)

pat = ("(?i)" if icase else "") + ("^(?:%s)$" % pattern if exact else pattern)
try:
    rx = re.compile(pat)
except re.error as exc:
    sys.stderr.write("ERROR: 正则无效: %s\n" % exc)
    sys.exit(2)

matches = []
for node in root.iter("node"):
    hit = None
    for f in fields:
        val = (node.get(f) or "").strip()
        if val and rx.search(val):
            hit = (f, val)
            break
    if not hit:
        continue
    nums = re.findall(r"-?\d+", node.get("bounds") or "")
    if len(nums) >= 4:
        x1, y1, x2, y2 = (int(v) for v in nums[:4])
    else:
        x1 = y1 = x2 = y2 = 0
    matches.append(
        {
            "field": hit[0],
            "value": hit[1].replace("\n", " ")[:60],
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
            "x1": x1, "y1": y1, "x2": x2, "y2": y2,
            "clickable": node.get("clickable") == "true",
        }
    )

if not matches:
    sys.exit(1)
for i, m in enumerate(matches):
    print(
        "MATCH i=%d cx=%d cy=%d w=%d h=%d clickable=%s field=%s value=%s bounds=[%d,%d][%d,%d]"
        % (
            i, m["cx"], m["cy"], m["x2"] - m["x1"], m["y2"] - m["y1"],
            "yes" if m["clickable"] else "no", m["field"], m["value"],
            m["x1"], m["y1"], m["x2"], m["y2"],
        )
    )
if not listmode:
    if index >= len(matches):
        sys.stderr.write("ERROR: --index %d 越界（共 %d 个匹配）\n" % (index, len(matches)))
        sys.exit(2)
    m = matches[index]
    print("TARGET cx=%d cy=%d value=%s" % (m["cx"], m["cy"], m["value"]))
PYEOF
)"
  rc=$?
  if [ "$rc" = 0 ]; then break; fi
  if [ "$rc" = 2 ]; then printf '%s\n' "$RESULT" >&2; exit 2; fi
  if [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ]; then
    echo "RESULT=NO_MATCH pattern='$PATTERN' field=$FIELD timeout=${TIMEOUT}s serial=$SERIAL"
    exit 1
  fi
  sleep 1
done

printf '%s\n' "$RESULT"
if [ "$LISTMODE" = 1 ]; then
  echo "RESULT=LISTED pattern='$PATTERN' serial=$SERIAL"
  rm -f "$XML"
  exit 0
fi

TARGET="$(printf '%s\n' "$RESULT" | grep '^TARGET ' | head -1)"
CX="$(printf '%s\n' "$TARGET" | awk '{for(i=1;i<=NF;i++){split($i,kv,"="); if(kv[1]=="cx") print kv[2]}}')"
CY="$(printf '%s\n' "$TARGET" | awk '{for(i=1;i<=NF;i++){split($i,kv,"="); if(kv[1]=="cy") print kv[2]}}')"
if [ -z "$CX" ] || [ -z "$CY" ]; then
  echo "ERROR: 未能解析目标坐标" >&2
  exit 3
fi
if [ "$DRY" = 1 ]; then
  echo "RESULT=DRY_RUN target=($CX,$CY) serial=$SERIAL"
  rm -f "$XML"
  exit 0
fi

echo "TAP serial=$SERIAL x=$CX y=$CY"
"$ADB" -s "$SERIAL" shell input tap "$CX" "$CY" >/dev/null 2>&1
sleep "$SETTLE"
echo "RESULT=TAPPED target=($CX,$CY) serial=$SERIAL"
rm -f "$XML"
exit 0
