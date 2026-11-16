#!/usr/bin/env bash
# ui_dump.sh — 导出当前 UI 层级 XML（uiautomator dump），供 wait_for / 断言使用。
#
# 用法：
#   ./ui_dump.sh <serial|A|B> [--out FILE] [--nodes] [--raw]
#   --nodes  只打印可读节点摘要（text / content-desc / resource-id / bounds）
#   --raw    打印原始 XML 到 stdout（不写本地文件）
#
# 退出码：0 = 成功拿到 XML；1 = 失败。

set -u
. "$(cd "$(dirname "$0")" && pwd)/env.sh"

OUT=""
NODES=0
RAW=0
POS=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:-}"; shift 2 ;;
    --nodes) NODES=1; shift ;;
    --raw) RAW=1; shift ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) POS+=("$1"); shift ;;
  esac
done
[ "${#POS[@]}" -lt 1 ] && { sed -n '2,11p' "$0"; exit 2; }

SERIAL="$(e2e_resolve_serial "${POS[0]}")"
e2e_require_device "$SERIAL" || exit 1
e2e_mkdirs
[ -n "$OUT" ] || OUT="$STATE_DIR/ui-$(e2e_ts).xml"

REMOTE="/sdcard/window_dump.xml"
attempt=0
while [ "$attempt" -lt 3 ]; do
  attempt=$((attempt + 1))
  # 删除旧文件，避免 dump 失败时读到上一次的内容。
  "$ADB" -s "$SERIAL" shell rm -f "$REMOTE" >/dev/null 2>&1
  dout="$("$ADB" -s "$SERIAL" shell uiautomator dump "$REMOTE" 2>&1 | tr -d '\r')"
  "$ADB" -s "$SERIAL" shell cat "$REMOTE" > "$OUT" 2>/dev/null
  if head -c 5 "$OUT" 2>/dev/null | grep -q '<?xml'; then
    break
  fi
  if printf '%s' "$dout" | grep -q 'could not get idle state'; then
    echo "WARN: uiautomator dump 未 idle（attempt $attempt），重试..." >&2
  else
    echo "WARN: dump 失败: $dout（attempt $attempt）" >&2
  fi
  sleep 1
done

if ! head -c 5 "$OUT" 2>/dev/null | grep -q '<?xml'; then
  echo "ERROR: 无法获取 UI XML（serial=$SERIAL）" >&2
  exit 1
fi

if [ "$RAW" = 1 ]; then cat "$OUT"; exit 0; fi

echo "DUMP $OUT serial=$SERIAL bytes=$(wc -c < "$OUT" | tr -d ' ')"
if [ "$NODES" = 1 ]; then
  "$PY" - "$OUT" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
p = sys.argv[1]
root = ET.parse(p).getroot()
rows = []
for n in root.iter('node'):
    text = (n.get('text') or '').strip()
    desc = (n.get('content-desc') or '').strip()
    rid = (n.get('resource-id') or '').strip()
    if not (text or desc or rid):
        continue
    rows.append((n.get('class','').split('.')[-1], text, desc, rid, n.get('bounds',''),
                 'clickable' if n.get('clickable') == 'true' else ''))
print("NODES=%d" % len(rows))
for cls, text, desc, rid, bounds, click in rows:
    print("  %-16s text=%-24s desc=%-20s id=%-40s %s %s" % (
        cls, text[:24], desc[:20], rid[-40:], bounds, click))
PYEOF
fi
