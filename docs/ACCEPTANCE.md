# MuMuLink 验收报告（Lead 亲自实测）

版本：1.0.0 · 验收时间：2026-11-14 12:30–13:05 · 验收人：Lead（本会话）

## 0. 被测环境

| 角色 | 实体 | 标识 |
| --- | --- | --- |
| 被控端（远端） | MuMu 实例 0「MuMu安卓设备」 | `127.0.0.1:16384`，Xiaomi 2211133C，Android 15，物理 1440×2560@360dpi |
| 手机端（客户端） | MuMu 实例 1（模拟手机，实为独立安卓 VM） | `127.0.0.1:16416`，V2241A / 23113RKC6C，Android 15，1080×1920@280dpi |
| PC 代理 | `pc/agent.js`（Node，`D:\app\New Folder\node.exe`） | 监听 0.0.0.0:27180(HTTP) / 27181(UDP) / 27182(video) / 27183(control) |
| 网络路径 | 客户端用 **10.130.90.1**（本机校园网 LAN 地址）而非 127.0.0.1 连接 | 与真实手机走同一 LAN 栈 |

防火墙取证：`D:\app\New Folder\node.exe` 存在两条入站 Allow 规则（TCP/UDP 各一），Profiles=Public、LocalPort=Any、RemoteIP=Any，无需管理员即可让校园网 Public 档访问。

## 1. 通过项（逐条实测，证据在 evidence/）

> 交付包：`dist/MuMuLink-1.0.0.apk`，19,044,038 B，md5 `27fd790d02be54bd518daf868e0debb5`（含 UDP 发现兼容修复；比 12:47 包仅多 `Discovery.kt` 一行兼容判断）。

1. **配对与鉴权**：`GET /api/hello` 200；`POST /api/pair` 错码 401 / 对码返回 32B token；`/api/logs` 无 token 401、带 token 200；配对页 200。证据：`evidence/agent-*.log`、`tools/test-handshake.js` 输出。
2. **UDP 自动发现（已修通）**：客户端扫描时每 2s 探测，代理日志持续出现 `收到发现探测 … 已单播回复`；对 `255.255.255.255:27181` 发 `MUMULINK_DISCOVER` 可收到完整 JSON（magic=MUMULINK / host / videoPort / controlPort / paired）。App 侧曾因只认 `MUMULINK_ANNOUNCE` 而显示 0 台，已改为兼容 `MUMULINK`；修复后 App 首屏实测显示「发现 1 台电脑 · 客厅电脑 10.0.2.2:27180 · v1.0.0 · 已配对」（`evidence/F12-discovery.png`）。
3. **手动连接 → 出画**：App 填 IP=10.130.90.1、端口=27180、配对码=004944 → 代理起会话（scid、max_size/max_fps/bit_rate 按当前档位）→ App 出画。关键日志：`视频流 codec=0x68323634`、`初始 session meta：1920x1080`、`首帧 29 B CONFIG`。证据：`evidence/A21-connect-hud.png`、`A37-connected.png`、`agent-prod.log`。
4. **画面适配**：FIT 模式下 2560×1440 设备画面等比铺满 1920×1080 手机屏；meta 中途变化（旋转 / 改 wm size / 切画质）App 自动重建解码器并重排，实测过 720×404、1080×606、1080×1920、1440×810、1920×1080。
5. **HUD**：显示设备名、Android 版本、生效分辨率与 dpi、FPS/MS/MBPS、返回与「画质」入口；3s 自动淡出、点屏唤回；HUD 隐藏时点击仍转发到远端（点微信图标在 HUD 不在时也能打开）。证据：`A01-hud.png`、`A04-match-hud.png`。
6. **画质/帧率/分辨率/码率可调**：四档预设之外，帧率 30/45/60/90/120、分辨率上限 ≤720/≤1080/≤1440/≤1920、码率 2/4/6/8/12 Mbps 均可单独改；改任一项代理用新参数重启会话（约 1.5s），App 自动重连并继续出画。实测切到 1440×810 / 1080×1920 / 720×404 均出画。证据：`lead-16-hd.png`、`lead-33-1920c.png`、`A21-connect-hud.png`。
7. **自适应**：开启后会话以 720@30 1.5Mbps 起步，面板显示「自适应中」并锁定手动档；关闭恢复手动档。证据：`lead-26-auto.png`。
8. **DPI 适配（MATCH）**：切换后设备 `wm size` 为 `Physical 1440x2560 + Override 1920x1080`、`Physical density 360 + Override density 280`，HUD 显示 `1920×1080 · 280dpi`，画面 1:1 锐利；切回 FIT 后 override 清空、恢复 1440×2560@360。证据：`A04-match-hud.png`、`A31-b1.png`、`lead-30-match.png`。
9. **悬浮球**：点按展开 6 项扇形（隐藏/旋转/主页/返回/适配/画质），点空白收起；长按隐藏，出现「悬浮球已隐藏 · 点此恢复」胶囊，点胶囊恢复；拖拽吸边（app-ui 自测 + Lead 观察）。证据：`lead-11-ball.png`、`lead-35-ballhidden.png`、`lead-37-ballback.png`。
10. **按键注入**：悬浮球 HOME 切回桌面（Lawnchair）、返回上一级、旋转请求（服务端日志 `Device rotation requested: portrait`）、音量/电源/菜单键均通。证据：`A27-home.png`、`agent-*.log`。
11. **触控注入**：单击可命中 —— 经 App 点微信图标后被控端前台变为 `com.tencent.mm/.plugin.account.ui.WelcomeActivity`；点设置项可进入。滑动产生完整 DOWN/MOVE…/UP 序列（一次 600ms 滑动发出 38 条触控消息）并使被控端界面滚动/切换。证据：`A38-tap1.png`（前置）、`F03-after.png`、logcat `Inconsistent event` 排除记录。
12. **断线重连**：直接杀代理 → App 底部红色胶囊「第 N 次重连… 代理拒绝连接（10.130.90.1:27180）」，画面停在最后一帧、FPS 变「—」；重启代理后约 2s 自动恢复出画。重装/杀进程后重开 App，点「最近连接」一键重连成功。证据：`lead-27-offline.png`、`lead-28-retry.png`、`agent-prod.log`。
13. **错误态区分**：代理在线但未配对 →「代理尚未配对，请先打开配对页输入配对码」；代理不在 → 重试计数 + 「请检查手机与电脑是否在同一 Wi-Fi」；配对码错误 → 401 提示。证据：`lead-85-new-apk.png`、`A33-appnow.png`。
14. **部署与守护**：`pc/start-agent.cmd`（崩溃 3s 重启、标题显示 IP:端口）、`pc/install-autostart.cmd` / `uninstall-autostart.cmd`（写当前用户 Startup，免管理员，已实测装/卸）；`pc/ip.js` 打印二维码/地址。
15. **校园网形态**：代理监听 0.0.0.0，防火墙规则允许 Public 入站；客户端以 LAN IP 连接（不是 127.0.0.1）；UDP 广播 + 单播回复可在 LAN 内发现。
16. **构建可复现**：`bash tools/gbuild.sh assembleDebug` 重出 APK，md5 与交付包一致：`27fd790d02be54bd518daf868e0debb5`（19,044,038 B，含发现兼容修复）。

17. **断开串流（C02）**：串流中点系统返回键 → 本地弹出「断开串流？」（继续串流 / 断开），确认「断开」后回到连接页并停止会话；代理日志 `停止会话… 会话已停止`。证据：`evidence/F18-back-test.png`、`F20-disconnected.png`。
18. **首连探索路径**：发现卡片「客厅电脑 10.0.2.2:27180 已配对」→ 点击后若本地无 token 会提示「需要配对码」并给出代理网页地址（不是冒充成功）；用配对码 004944 手动连接成功。

## 2. 已知限制 / 非缺陷

1. **同设备并发注入冲突**：若同一安卓设备上另有一个 scrcpy 服务器（例如别的客户端）在注入触摸，安卓 InputDispatcher 会因共享注入设备 `DeviceId(-1)` 的残留指针拒绝新的 DOWN：`Inconsistent event … Invalid DOWN event - pointers already down for device DeviceId(-1)`。这是安卓输入分发器的行为，不是本 App 缺陷；正常只开 MuMuLink 时不会发生。若遇到，重启被控端或结束其它 scrcpy 会话即可清除。
2. **MuMu x86_64 解码器**：只有 `OMX.qcom.video.decoder.avc`（软解），1080p60 高码率可能掉帧；校园网 Wi-Fi 建议先用「均衡/流畅」。
3. **校园网客户端隔离**：若接入交换机启用了 AP isolation，手机与电脑二层不通，任何局域网方案都不可用；此时请用手机热点或同网段路由器（README「校园网部署要点」）。
4. 本机无法在宿舍实测物理 Wi-Fi 链路，只能验证监听/防火墙/路由与 LAN IP 路径；真实手机首次连接请用 App 内「扫描」或手动输入 PC 的 LAN IP（启动代理时控制台会打印）。

## 3. 复现命令（验收脚本）

```bash
# 1) 启动代理（或双击 pc/start-agent.cmd）
cd mumu-remote/pc && "D:/app/New Folder/node.exe" agent.js

# 2) 安装 App 到手机/模拟器
adb install -r mumu-remote/android/app/build/outputs/apk/debug/app-debug.apk

# 3) App 侧：扫描或手动连接（默认端口 27180，配对码见 pc/config.json）
```

自动化辅助：`tools/test-agent.js`（零依赖代理自测，27380+ 端口隔离）、`tools/relay-check.js`（HTTP→视频→控制→tap 直连自检）、`tools/test-handshake.js`（TCP 握手门）。

## 3. 真机联调（2026-11-14 15:00，vivo V2536A / Android 16）

- 环境：手机 Wi-Fi `10.130.88.71/22`，PC `10.130.90.1/22`，**同一校园网**；USB 调试已开启。
- 首次诊断：PC→手机 ICMP/TCP 可达（ARP 有记录、RST 正常返回），手机→PC TCP 27180 **超时**；而 `node.exe` 的防火墙规则仅 `Public`，此时 Wi-Fi 被 Windows 判为 `Private` → 入站被拦。根因确认。
- 修复：把 node.exe 复制到 `%LOCALAPPDATA%\MuMuLink\node.exe` 运行，Windows 自动生成 `Private,Public` 入站规则；`start-agent.cmd` 已内置该逻辑。
- 实测结果：
  - 手机浏览器/nc 访问 `http://10.130.90.1:27180/api/hello` → 200 JSON；
  - App 点「最近连接 10.130.90.1」→ 配对 token 生效 → 出画（`evidence/P10-wifi-stream.png`，56 fps，约 1 Mbps）；
  - 经 App 点击远端微信「登录」按钮 → 画面从登录页进入「作为平板使用/作为手机使用」（`evidence/P11-A-after-tap.png`、`P12-A-reg.png`）→ **触控链路在真机 Wi-Fi 下工作**；
  - USB 备用通道同样验证：`adb reverse tcp:27180/27182/27183` 后 App 连 `127.0.0.1:27180` 出画并可点击（`evidence/P07-connect.png`、`P08-touch.png`）。
- 结论：真机（非模拟器）上 视频解码、触控、配对、token 持久化、Wi-Fi 与 USB 双通道 均 PASS。

## 4. v1.1.0 增量验收（2026-11-26，模拟器当手机端）

环境：被控端 MuMu 实例 0（127.0.0.1:16384，1440x2560@360），手机端 MuMu 实例 1（127.0.0.1:16416，1080x1920@280，装 v1.1.0 APK）；代理 `pc/agent.js`（27180/27181/27182/**27184**/27183）。

| 项 | 结果 | 证据 |
| --- | --- | --- |
| 手机端三条连接（video→audio→control） | PASS | 代理 `/api/hello` `running=true videoConnected=true audioConnected=true controlConnected=true` |
| 音频数据量与规格 | PASS | `audioBytes` 1.08 MB / 5.6s ≈ 192 KB/s = 48k×2ch×16bit；logcat `音频播放已启动：48000Hz 立体声，buffer=12312` |
| 悬浮球「画质」面板新增音频/游戏模式 | PASS | `evidence/V03-panel.png`（音频同步 开、游戏模式 关，可点） |
| 游戏模式开 | PASS | 面板开启后 `/api/hello` quality `{1080, 90, 12000000}`，音频仍为 true（`evidence/V04-gamemode.png`） |
| 游戏模式关 | PASS | prefs `game_mode=false`，quality 回 `{1080, 60, 4000000}`，音频保留 |
| 图形化控制台 | PASS | `GET /dashboard` 200 / 23 KB，含概览/会话/设置/诊断四页签；`pc/MuMuLink-GUI.cmd` 拉起代理并用 Edge 应用窗口打开 |
| 音频关闭后画面/触控不受影响 | 设计保证 | 音频失败或关闭只结束音频线程，video/control 独立 |

已知限制：MuMu x86_64 为软解，1080p60 以上高码率可能掉帧；音频为系统混音输出（含所有应用声音）。
