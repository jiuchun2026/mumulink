/// MuMuLink 技术规格（单一事实来源 v1，2026-11-14）
/// 任何接口改动必须先改本文件并在群里同步。

## 1. 目标

Android 手机 App「MuMuLink」通过**局域网**远程操作本机 MuMu 模拟器（MuMu Player 15，Android 15，1440x2560@360dpi，adb 只在 127.0.0.1:16384 监听）。要求：

- 操作逻辑接近真机：单指点击/长按/滑动、双指缩放、返回/主页/最近任务/旋转/音量、文本输入。
- 画面适配手机屏幕（宽高比自适应），DPI 可适配。
- 用户可选画质/帧率/码率，带"自适应"模式。
- 现代 UI + 可隐藏悬浮球（调画质、DPI、快捷键）。
- 校园网（Public 配置文件）下可用；只允许已配对设备连接。
- 全部功能必须被自动化 E2E 验证。

## 2. 架构（已锁定）

```
[手机 App MuMuLink]  ──HTTP(27180)──▶  [PC Node 代理 agent.js]  ──adb forward──▶  [MuMu 内 scrcpy-server 5.0.1]
        │  ──TCP 视频(27182)──────────▶  (字节管道, 0.0.0.0 监听)                 │
        │  ──TCP 控制(27183)──────────▶                                          │
        └──UDP 发现(27181 broadcast)──▶
```

理由：MuMu 的 adb 端口仅绑 127.0.0.1，且机器无管理员权限（不能 netsh portproxy / 改防火墙），但 `D:\app\new folder\node.exe` 已有 Public 全端口入站放行规则。App 端不实现 ADB 协议（避开认证/推 jar/shell v2），只做 scrcpy 客户端，最薄最稳。

App 端绝对不做的事：不连 adb、不推文件、不执行 shell。所有设备侧操作走代理 HTTP。

## 3. PC 代理（`pc/`，Node，无第三方依赖，只用内置模块）

### 3.1 文件
- `pc/agent.js` — 主程序
- `pc/config.json` — 配置（端口、adb 路径、jar 路径、device、token）
- `pc/start-agent.cmd` — 双击启动（用 allowlist 的 node.exe）
- `pc/autostart-install.cmd` / `pc/autostart-remove.cmd` — 计划任务自启（schtasks，用户级）
- `pc/README.md` — 使用说明

### 3.2 配置默认值
```json
{
  "httpPort": 27180, "udpPort": 27181, "videoPort": 27182, "controlPort": 27183,
  "adbForwardPort": 28182,
  "adb": "D:/app/Android/Sdk/platform-tools/adb.exe",
  "device": "127.0.0.1:16384",
  "scrcpyJar": "tools/scrcpy-server.jar",
  "scrcpyJarMd5": "55a1ef0a811f1cb0d417084b70e0a630",
  "deviceJarPath": "/sdcard/mumulink/scrcpy.bin",
  "pairingCode": "6 位数字（首次运行随机生成并持久化到 config.json）",
  "token": "32 字节随机 hex（配对成功后写入；空=未配对）",
  "requirePairing": true
}
```

### 3.3 HTTP JSON API（UTF-8；除 /api/hello、/api/pair 外都要 `X-MuMuLink-Token`；错误返回 `{"ok":false,"error":"CODE","message":"中文"}` + 非 200）
- `GET /api/hello` → `{ok:true, magic:"MUMULINK", v:1, name:"客厅电脑", agentVersion:"1.0.0", paired:bool, device:{model,android,width,height,density,connected}, session:{running,quality:{maxSize,maxFps,bitRate}}}`
- `POST /api/pair {code}` → `{ok:true, token}`；配对码错 → 401 `BAD_CODE`。
- `POST /api/session/start {quality:{maxSize,maxFps,bitRate}, display:{mode:"fit"|"match",width,height,density}}` → `{ok:true, deviceW, deviceH, videoPort, controlPort}`（先按 display 设置 wm，再启动 scrcpy；重复调用 = 先停旧会话）
- `POST /api/session/stop` → `{ok:true}`
- `POST /api/session/quality {maxSize,maxFps,bitRate}` → `{ok:true}`（重启会话；App 负责重连）
- `POST /api/display {mode,width,height,density}` → `{ok:true, applied:{width,height,density}}`；mode=fit 时恢复原始 wm size/density。
- `POST /api/display/reset` → `{ok:true}`
- `POST /api/text {text}` → `{ok:true}`（走 scrcpy 控制协议 INJECT_TEXT，>300 字节截断）
- `GET /api/logs?since=<seq>` → `{ok:true, seq, lines:[...]}`（环形日志，供 App 诊断页展示）
- `POST /api/agent/restart` → `202 {ok:true}`（管理端点：detached re-exec 自身后退出；需 token）
- `POST /api/device/restart` → `{ok:true}`（管理端点：重启 MuMu 实例 0，走 `MuMuManager control -v 0 restart`；需 token）
- 静态页 `GET /` → 极简配对页（显示配对码、当前 IP、端口、已连接设备、按钮"重启代理/重启 scrcpy"）。不需要花哨，但必须能用手机浏览器打开完成配对。

### 3.4 TCP 端口握手（视频/控制都一样）
连接后客户端先发一行 UTF-8：`MUMULINK1 <token>\n`（未配对时 token 可为空）。
- 成功：代理回 `MUMULINK1 OK\n`，随后进入裸字节管道。
- 失败：代理回 `MUMULINK1 ERR <CODE>\n` 并关闭。

视频端口：在握手成功后，代理才连接 adb forward 本地端口并开始双向 pipe（方向：device→phone 为主，phone→device 数据丢弃）。
控制端口：必须在视频连接建立后才受理（scrcpy server 按 video→control 顺序 accept）；受理后同样连第二个 forward 连接并双向 pipe。

### 3.5 scrcpy 启动参数（固定；实测通过）
```
adb shell "CLASSPATH=/sdcard/mumulink/scrcpy.bin app_process / com.genymobile.scrcpy.Server 5.0.1 \
  log_level=info video=true audio=false control=true tunnel_forward=true scid=<scidHex> \
  send_device_meta=false send_dummy_byte=false send_stream_meta=true send_frame_meta=true \
  max_size=<q.maxSize> max_fps=<q.maxFps> video_bit_rate=<q.bitRate> \
  stay_awake=true cleanup=true power_on=false clipboard_autosync=false"
```
流程：`adb connect <device>`（幂等）→ 校验/推送 jar（放 `/sdcard/mumulink/scrcpy.bin`，md5 必须等于配置值；**不可**放 /data/local/tmp，那里会被其他进程周期性清空）→ `adb forward tcp:28182 localabstract:scrcpy_<%08x scid>`（**一条** forward 同时承载 video+control 两次连接）→ 启动 shell 进程 → 等 App 连 video 端口并握手 → 连 `127.0.0.1:28182` pipe；再等 App 连 control 端口 → 建**第二条**到 `127.0.0.1:28182` 的连接 pipe。

端口隔离（Windows 实测坑）：adb forward 固定绑 `127.0.0.1:28182`，代理的 LAN 监听是 `0.0.0.0:27182/27183`，两者端口号必须不同——Windows 下 `127.0.0.1:X` 已绑定时 `0.0.0.0:X` 会 EADDRINUSE，且本地连接会优先命中 adb forward 而绕过代理鉴权。禁止让 adb forward 复用 27182/27183。

关键约束（实测）：
- `scid` 为 31 位非负整数（传入用十六进制），abstract socket 名为 `scrcpy_` + 8 位小写十六进制；用它可避免与设备上其它 scrcpy 会话的全局 socket 名冲突（否则 `Address already in use`）。
- server 会先 accept video **再** accept control，两个连接都建立后才开始推流；App 必须先开视频端口再开控制端口。
- 首字节流：4B codec id `0x68323634` → 12B session meta（int32 flags | int32 w | int32 h）→ 每帧 12B 头（int64 ptsAndFlags + int32 size）+ payload。
- 显示旋转/改变 wm size 时，server **中途**会再发一次 session meta（flags bit63=1）+ 新 SPS/PPS + 关键帧，客户端必须重建解码器并按新尺寸重算宽高比。
- `audio=false`：不建音频流；`control=true` 时控制通道必开。

停止会话：kill 掉 adb shell 进程 → `adb forward --remove tcp:<videoPort>` → 恢复 wm（若是 match 模式）。

### 3.6 日志
所有关键步骤 print 到 stdout 并进环形缓冲（供 /api/logs、App 诊断页）。必须包含：adb 版本、设备连接、push、forward、server 启动、连接接受/拒绝、异常退出。

### 3.7 UDP 发现（27181，固定载荷）
代理每 2s 向 `255.255.255.255:27181` 和每个网卡子网广播地址各发一次 UTF-8 JSON；同时监听 27181，收到 App 发来的文本 `MUMULINK_DISCOVER` 时立即单播回同一载荷。
```json
{"magic":"MUMULINK","v":1,"name":"客厅电脑","host":"10.130.90.1","httpPort":27180,"videoPort":27182,"controlPort":27183,"paired":false,"agentVersion":"1.0.0","ips":["10.130.90.1"]}
```
`ips` 为可选字段（本机所有 IPv4）。App 只接受 `magic=="MUMULINK" && v==1`，按 `host` 去重；2s 收不到可发一次 `MUMULINK_DISCOVER` 再等 300ms。


## 4. App（`android/`，Kotlin + Compose，minSdk 26）

### 4.1 包结构 & 归属
```
com.mumulink.remote
  MainActivity.kt                     [app-stream]
  model/Models.kt                     [app-stream] 数据类（见 4.2）
  net/AgentClient.kt                  [app-stream] OkHttp：hello/pair/start/stop/quality/display/text/logs
  net/Discovery.kt                    [app-stream] UDP 广播发现（27181），2s 间隔，收集 AgentHello
  net/StreamSocket.kt                 [app-stream] 视频/控制 TCP 连接 + 握手 + 帧解析
  core/VideoDecoder.kt                [app-stream] MediaCodec 异步解码 → Surface
  core/ControlWriter.kt               [app-stream] scrcpy 控制消息序列化
  core/InputMapper.kt                 [app-stream] 触摸/按键 → 控制消息
  core/SessionController.kt           [app-stream] 状态机、重连、自适应画质、统计
  ui/LinkViewModel.kt                 [app-stream] 暴露 UiState/操作（见 4.3）
  ui/video/RemoteVideoSurface.kt      [app-stream] SurfaceView + 触摸手势 → InputMapper
  ui/AppRoot.kt                       [app-ui] 组合根：Connect/Stream 切换、对话框、Toast
  ui/screens/ConnectScreen.kt         [app-ui]
  ui/screens/StreamScreen.kt          [app-ui] 全屏画面 + 自动隐藏 HUD
  ui/screens/SettingsSheet.kt         [app-ui] 画质/帧率/码率/DPI/显示模式
  ui/components/FloatingBall.kt       [app-ui] 可拖拽、可隐藏、展开径向菜单
  ui/components/*.kt                  [app-ui] 状态球/数值条/玻璃卡片/图标按钮
  ui/theme/*                          [app-ui]（已有 Theme.kt 可改）
```
app-stream 保证对外接口与 4.2/4.3 完全一致；app-ui 只依赖这些接口，不改 core/net/model。

### 4.2 数据类（必须逐字一致）
```kotlin
data class AgentEndpoint(val host: String, val httpPort: Int = 27180, val videoPort: Int = 27182, val controlPort: Int = 27183)
data class DeviceInfo(val model: String, val android: String, val width: Int, val height: Int, val density: Int)
data class QualitySpec(val maxSize: Int, val maxFps: Int, val bitRate: Int, val label: String = "")
data class SessionInfo(val running: Boolean, val quality: QualitySpec?, val deviceW: Int, val deviceH: Int)
data class AgentHello(val name: String, val host: String, val version: String, val paired: Boolean, val device: DeviceInfo?, val session: SessionInfo?)
enum class DisplayMode { FIT, MATCH }
data class DisplaySpec(val mode: DisplayMode, val width: Int, val height: Int, val density: Int)
data class StreamStats(val fps: Float = 0f, val mbps: Float = 0f, val latencyMs: Int = 0, val decodedFrames: Long = 0L, val droppedFrames: Int = 0, val deviceWidth: Int = 0, val deviceHeight: Int = 0, val decoderName: String = "")
sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val step: String) : LinkState
    data class Streaming(val stats: StreamStats) : LinkState
    data class Retrying(val attempt: Int, val reason: String) : LinkState
    data class Failed(val message: String, val hint: String = "") : LinkState
}
data class UiState(
    val link: LinkState = LinkState.Idle,
    val agents: List<AgentHello> = emptyList(),
    val scanning: Boolean = false,
    val device: DeviceInfo? = null,
    val quality: QualitySpec = QualityPresets.BALANCED,
    val display: DisplaySpec = DisplaySpec(DisplayMode.FIT, 0, 0, 0),
    val ballVisible: Boolean = true,
    val stats: StreamStats = StreamStats(),
    val toast: String? = null,
    val autoQuality: Boolean = false,
    val pairedOnce: Boolean = false,
)
```

### 4.3 LinkViewModel（app-stream 实现，app-ui 调用）
```kotlin
class LinkViewModel(app: Application) : AndroidViewModel(app) {
    val ui: StateFlow<UiState>
    fun startScan(); fun stopScan()
    fun connect(host: String, pairingCode: String? = null)
    fun disconnect()
    fun setQuality(q: QualitySpec)                 // 立即生效（重启会话+自动重连）
    fun setAutoQuality(enabled: Boolean)
    fun setDisplayMode(mode: DisplayMode)          // 用当前手机屏幕尺寸/密度
    fun sendKey(key: RemoteKey)                    // BACK/HOME/APP_SWITCH/POWER/VOLUME_UP/VOLUME_DOWN/ROTATE/MENU
    fun sendText(text: String)
    fun showBall(); fun hideBall()
    fun consumeToast()
}
enum class RemoteKey { BACK, HOME, APP_SWITCH, POWER, VOLUME_UP, VOLUME_DOWN, ROTATE, MENU }
object QualityPresets {
    val SMOOTH   = QualitySpec(720, 30, 1_500_000, "流畅")
    val BALANCED = QualitySpec(1080, 60, 4_000_000, "均衡")
    val HD       = QualitySpec(1440, 60, 8_000_000, "高清")
    val ULTRA    = QualitySpec(1920, 60, 12_000_000, "极致")
    val ALL = listOf(SMOOTH, BALANCED, HD, ULTRA)
}
```

### 4.4 视频管线
- 视频 socket 首字节流：codec id(4B, 期望 0x68323634) → session meta(12B: flags,width,height) → 循环 [ptsAndFlags(8B) + size(4B) + payload]。
- CONFIG 帧 → `MediaFormat` 的 csd-0（Annex-B，含 SPS/PPS）；KEY 帧正常送。
- MediaCodec 异步模式，输出到 `Surface`（SurfaceView）。渲染时保持设备宽高比（FIT 时 letterbox；MATCH 时基本贴合）。
- 统计：解码 fps、输入码率、接收队列积压（用 ppm 近似延迟）、丢帧数。每 1s 更新到 UiState.stats。
- 自适应：默认关闭；开启后每 3s 评估：`decodedFps < maxFps*0.8` 或积压 > 500ms → 降一档（ULTRA→HD→BALANCED→SMOOTH）；连续 15s 达标且 RTT 稳定 → 升一档。切换时通过 `setQuality` 触发会话重启。

### 4.5 触控/按键（InputMapper）
- 单指：DOWN(0)/MOVE(2)/UP(1)；长按由设备端自然产生（无需计时）。
- 双指：第二指 ACTION_POINTER_DOWN(5)/ACTION_POINTER_UP(6)，各自 pointerId。
- 坐标换算：`x = (touchX - videoRect.left) / videoRect.width * deviceW`，y 同理；pressure 固定 1.0（0xFFFF），buttons=1（按下）/0（抬起），actionButton 同。
- 双指缩放：直接发原始多指事件由设备端处理（不做 App 侧缩放合成）。
- 按键：BACK(4:action down+up)、HOME(KEYCODE_HOME=3)、APP_SWITCH(187)、POWER(26)、VOLUME(24/25)、ROTATE(TYPE_ROTATE_DEVICE=11)。
- 文本：`sendText` 优先走 HTTP `/api/text`（>300B 分批）；控制 socket 也可发 INJECT_TEXT。

### 4.6 悬浮球
- 边缘可拖拽（吸附左右）、半透明、单击展开径向菜单（画质 4 档 + 自适应、DPI 适配开关、返回/主页/旋转、隐藏）。
- 长按直接隐藏；隐藏后从屏幕边缘滑入区域唤出（或设置页开关）。
- 流媒体时自动半透明；显示实时 fps/ms 小字。

### 4.7 连接与重连
- 首页：UDP 扫描 3s，列表显示发现的代理（名称/IP/延迟）；手动 IP 输入；配对码输入框（代理网页显示）。
- 连接流程：hello → 需要则 pair → session/start（带当前画质+显示模式）→ 连视频 → 连控制 → Streaming。
- 会话重启（画质/DPI 变更）：显示"正在切换清晰度"遮罩，自动重连最多 6 次（0.4s 起指数退避）。
- 错误：BAD_TOKEN → 提示重新配对；CONNECT_TIMEOUT → 检查同一 Wi-Fi/防火墙；NO_DEVICE → 模拟器未启动。

## 5. 关键数值
- 默认画质：BALANCED；默认显示模式：FIT；默认悬浮球显示。
- TCP_NODELAY 全部打开；视频 socket 接收缓冲 1MB。
- 心跳：控制 socket 每 3s 发一次 0 字节？**不发**（scrcpy 协议无心跳，避免污染）；用 socket 存活检测（写入失败/读到 EOF）。
- App 后台：`WAKE_LOCK` 保屏常亮；切后台暂停解码但保持会话 10s，超时断开。

## 6. 测试与交付
- E2E：MuMu 实例 0 = 被控端；MuMu 实例 1 = "手机"（装 APK）。build-lab 负责脚本与证据；Lead 负责最终逐项验收并亲自跑一遍。
- 证据：`evidence/<序号>-<功能>.png` + `REPORT.md` 记录每项结论。
- 交付：`mumu-remote/dist/MuMuLink-1.0.0.apk`、`pc/` 启动脚本、`README.md`（安装/连接/排障/校园网说明）、`docs/ATTRIBUTION.md`（scrcpy Apache-2.0、ScrcpyForAndroid Apache-2.0 参考）。
