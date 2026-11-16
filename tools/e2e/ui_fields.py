#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""列出当前界面里的 EditText 及中心坐标（按 XML 顺序）。

用法: python ui_fields.py <serial>
输出: 每行 "<index> <cx> <cy> <text>"
"""
import io
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ADB = r"D:/app/Android/Sdk/platform-tools/adb.exe"


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: ui_fields.py <serial>", file=sys.stderr)
        return 2
    serial = sys.argv[1]
    subprocess.run(
        [ADB, "-s", serial, "shell", "uiautomator", "dump", "/sdcard/window_dump.xml"],
        capture_output=True,
    )
    out = subprocess.run(
        [ADB, "-s", serial, "shell", "cat", "/sdcard/window_dump.xml"],
        capture_output=True,
    ).stdout.decode("utf-8", "replace")
    i = out.find("<?xml")
    j = out.rfind("</hierarchy>")
    if i < 0 or j < 0:
        print("ERROR: no ui xml", file=sys.stderr)
        return 1
    root = ET.fromstring(out[i : j + len("</hierarchy>")])
    idx = 0
    for n in root.iter("node"):
        cls = n.get("class") or ""
        if "EditText" not in cls:
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        x1, y1, x2, y2 = (int(v) for v in m.groups())
        text = (n.get("text") or "").replace(" ", "\\s")
        print("%d %d %d %s" % (idx, (x1 + x2) // 2, (y1 + y2) // 2, text))
        idx += 1
    if idx == 0:
        print("ERROR: no EditText found", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
