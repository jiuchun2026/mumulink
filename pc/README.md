# MuMuLink PC Agent

MuMuLink 的 PC 端代理：把 MuMu 模拟器里的 Android 屏幕通过 scrcpy 5.0.1 转发到同一局域网内的手机 App，并提供 HTTP 控制/配对接口。零 npm 依赖，只使用 Node 内置模块。

## 快速开始

1. 确认 MuMu 主实例已启动（adb 串口 `127.0.0.1:16384`）。
2. 双击 `start-agent.cmd`。窗口标题会显示 `MuMuLink Agent - 10.x.x.x:27180`。
3. 手机与电脑连同一个 Wi-Fi，浏览器打开 `http://<窗口标题里的 IP>:27180/`，或在 App 里输入该地址。
4. 页面会显示 6 位配对码；在 App 输入配对码即可拿到 token，之后自动连接视频/控制端口。

默认端口：HTTP 27180、UDP 27181、视频 27182、控制 27183；adb forward 固定用 28182（与 LAN 端口隔离，避免 Windows 下 `127.0.0.1:27182` 被 adb forward 抢占）。

## 文件

| 文件 | 用途 |
| --- | --- |
| `agent.js` | HTTP API、TCP 握手、会话/显示/发现/日志入口 |
| `lib/` | adb、display、scrcpy、config、logger、page、discovery |
| `config.json` | 首次运行自动生成；配对码、token、端口持久化 |
| `start-agent.cmd` | 双击启动，标题显示 IP:端口，崩溃后自动重启 |
| `install-autostart.cmd` / `uninstall-autostart.cmd` | 当前用户登录自启（在 Startup 目录写 `MuMuLinkAgent.cmd`，**不需要管理员**） |
| `autostart-install.cmd` / `autostart-remove.cmd` | SPEC 3.1 兼容别名 |
| `ip.js` | 打印本机主 IPv4 与 HTTP 端口 |

## 配置

`config.json` 关键字段：

```json
{
  "name": "客厅电脑",
  "httpPort": 27180, "udpPort": 27181, "videoPort": 27182, "controlPort": 27183,
  "adbForwardPort": 28182,
  "adb": "D:/app/Android/Sdk/platform-tools/adb.exe",
  "device": "127.0.0.1:16384",
  "scrcpyJar": "tools/scrcpy-server.jar",
  "deviceJarPath": "/sdcard/mumulink/scrcpy.bin",
  "pairingCode": "004944",
  "token": "",
  "requirePairing": true
}
```

- 修改端口后重启代理；五个端口不能重复。
- `token` 为空表示尚未配对；删除配对码可让 App 重新配对（同时清空 token 更安全）。
- 代理每次启动都会把 `tools/scrcpy-server.jar` 推到设备 `/sdcard/mumulink/scrcpy.bin`（md5 校验，不符自动重推）。不要用 `/data/local/tmp`，MuMu 会周期性清空。

## 校园网 / 防火墙 / 多网卡

- **校园网常开客户端隔离**：手机与电脑互相 ping 不通时，App 无法直连。可改用手机热点，或让电脑连手机热点后重启代理（页面会显示新 IP）。
- **Windows 防火墙**：当前 `node.exe` 需要“专用/公用网络”入站放行 27180-27183/TCP 与 27181/UDP。若页面打不开，先在电脑本机 `http://127.0.0.1:27180/` 验证代理，再从手机测。
- **多网卡**：页面和 UDP 广播会列出所有 IPv4；优先用 `10.` / `192.168.` 开头的物理网卡地址。VMware/Hyper-V 的 `192.168.83.1` 等虚拟网卡不能给手机用。
- **换端口**：同时改 `config.json` 的 `httpPort/videoPort/controlPort/udpPort`（保持互不重复），并在防火墙放行新端口。

### 重要：不要用 127.0.0.1 测试视频/控制端口

代理的 adb forward 会占用本机 `127.0.0.1:28182`，但 LAN 视频/控制监听在 `0.0.0.0:27182/27183`。Windows 上从**本机**连 `127.0.0.1:27182` 可能被其他 forward/规则抢占；自测请用 LAN IP（如 `10.130.90.1:27182`）。手机走 LAN IP，不受影响。

## 自测

```bat
node tools\test-agent.js
```

测试会使用独立临时配置与 27380+ 端口段，不会动 `pc/config.json`；结束时校验设备 `wm size/density` 恢复、清理自己的 forward。输出 PASS/FAIL 汇总，失败非零退出。详见 `docs/TEST-REPORT.md`。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| 页面打不开 | 先本机 `127.0.0.1:27180`；再看防火墙；确认 `start-agent.cmd` 窗口没有报 EADDRINUSE |
| 配对码错误 | 看页面顶部配对码，或删除 `config.json` 里的 `pairingCode`/`token` 后重启 |
| 会话启动失败 `PORT_IN_USE` | 27182/27183 或被别的程序占用；改端口或关闭占用进程 |
| `SCRCPY_BUSY` | 设备上有同 scid 的 server 残留；代理会自动 `pkill` 自己的 scid，稍等重试 |
| 视频连上无画面 | 控制通道必须先连；App 会先 video 后 control。自测确认顺序 |
| 连不上设备 | `adb -s 127.0.0.1:16384 get-state`；必要时 `adb connect 127.0.0.1:16384`，不要重启整个 adb server |
| MATCH 后黑屏/尺寸不对 | 点“恢复显示 FIT”；代理退出时也会自动恢复原始 `wm size/density` |

## HTTP API 摘要

除 `/api/hello`、`/api/pair` 外均需 `X-MuMuLink-Token`（或 `Authorization: Bearer`）。错误体：`{"ok":false,"error":"CODE","message":"中文","hint":"可选"}`。

`GET /api/hello`、`POST /api/pair`、`POST /api/session/start|stop|quality`、`POST /api/display|display/reset`、`POST /api/text`、`GET /api/logs?since=`、`POST /api/agent/restart`、`POST /api/device/restart`、`GET /` 配对页。

TCP 握手：客户端发 `MUMULINK1 <token>\n`，成功回 `MUMULINK1 OK\n` 后为裸 H.264 管道（video）或 scrcpy 控制协议（control）；失败只回 `MUMULINK1 ERR <CODE>\n` 并关闭。

## 校园网连不上？先看防火墙的“网络配置文件”

现象：手机和电脑在同一网段（都能 ping 通/ARP 有记录），但手机访问 `http://<PC IP>:27180` 超时。
原因：Windows 防火墙规则是**按程序路径 + 网络配置文件**生效的。`D:\app\new folder\node.exe` 的放行规则只在 **Public（公用）** 生效，而校园网 Wi-Fi 常被 Windows 判为 **Private（专用）**，于是入站被拦。

解决（无需管理员）：
1. 直接运行本目录的 `start-agent.cmd`。它会优先使用 `%LOCALAPPDATA%\MuMuLink\node.exe`，没有就把系统 node 复制到那里再运行。
2. 新路径第一次监听时 Windows 会为新程序建立“专用+公用”放行规则（若弹出防火墙提示，点“允许访问”）。
3. 验证：`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe -Command "Get-NetFirewallRule -Direction Inbound -Action Allow | ? { ($_|Get-NetFirewallApplicationFilter).Program -like '*MuMuLink*' }"` 应能看到 Private, Public 两条。

备用通道（完全绕过防火墙）：手机 USB 连接并开启 USB 调试后：
```
adb reverse tcp:27180 tcp:27180
adb reverse tcp:27182 tcp:27182
adb reverse tcp:27183 tcp:27183
```
App 里手动连接 `127.0.0.1:27180` 即可（视频/控制也走 USB）。
