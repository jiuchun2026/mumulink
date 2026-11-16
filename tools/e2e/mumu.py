#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""mumu.py — 解析 MuMuManager 的 JSON 输出，供 shell 脚本使用。

用法：
  mumu.py info [index]        # 人读一行（idx name host:port started pid state）
  mumu.py port <index>        # 只打印 127.0.0.1:<adb_port>
  mumu.py started <index>     # 1 / 0
  mumu.py name <index>
  mumu.py json                # 原样输出规范化后的 JSON（UTF-8）

退出码：0 成功；1 实例/端口不存在；2 参数错误。

注意：D:\\app\\python.exe 默认 GBK，本脚本强制 UTF-8 输出，避免中文乱码。
"""
import io
import json
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MUMU_MGR = os.environ.get("MUMU_MGR", r"C:\Program Files\Netease\MuMuPlayer-12.0\shell\MuMuManager.exe")


def load_all():
    try:
        proc = subprocess.run(
            [MUMU_MGR, "info", "-v", "all"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        sys.stderr.write("ERROR: MuMuManager 调用失败: %s\n" % exc)
        sys.exit(1)
    text = proc.stdout.decode("utf-8", "replace")
    if not text.strip():
        text = proc.stderr.decode("utf-8", "replace")
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < start:
        sys.stderr.write("ERROR: 无法解析 MuMuManager 输出（前 200 字节）:\n%s\n" % text[:200])
        sys.exit(1)
    try:
        return json.loads(text[start:end + 1])
    except ValueError as exc:
        sys.stderr.write("ERROR: JSON 解析失败: %s\n" % exc)
        sys.exit(1)


def main(argv):
    if len(argv) < 2:
        sys.stderr.write(__doc__ or "用法: mumu.py <info|port|started|name|json> [index]\n")
        return 2
    cmd = argv[1]
    data = load_all()

    if cmd == "json":
        json.dump(data, sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
        sys.stdout.write("\n")
        return 0

    if len(argv) < 3:
        sys.stderr.write("ERROR: 需要 index 参数（0 或 1）\n")
        return 2
    idx = str(argv[2])
    if idx not in data:
        sys.stderr.write("ERROR: 实例 %s 不存在（现有: %s）\n" % (idx, ", ".join(sorted(data))))
        return 1
    d = data[idx]

    if cmd == "port":
        host = d.get("adb_host_ip") or "127.0.0.1"
        port = d.get("adb_port")
        if not port:
            sys.stderr.write("ERROR: 实例 %s 没有 adb_port（未启动？）\n" % idx)
            return 1
        print("%s:%s" % (host, port))
    elif cmd == "started":
        print("1" if d.get("is_android_started") else "0")
    elif cmd == "name":
        print(d.get("name", ""))
    elif cmd == "info":
        host = d.get("adb_host_ip") or "127.0.0.1"
        port = d.get("adb_port") or "-"
        print(
            "idx=%s name=%s addr=%s:%s started=%s pid=%s state=%s"
            % (
                idx,
                d.get("name", ""),
                host,
                port,
                "1" if d.get("is_android_started") else "0",
                d.get("pid", "-"),
                d.get("player_state", "-"),
            )
        )
    else:
        sys.stderr.write("ERROR: 未知子命令 %s\n" % cmd)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
