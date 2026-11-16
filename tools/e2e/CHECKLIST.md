# MuMuLink 端到端验收清单（CHECKLIST）

版本：2026-11-14 · 维护：build-lab（E2E lab）
约定：`A` = 主实例（被控端，`127.0.0.1:16384`）；`B` = 测试手机（控制端，实例1，端口以 `MuMuManager info` 为准）。
每条用例的**证据**统一放 `evidence/`，文件名以用例号开头，例如 `C07-A-after.png`。
坐标以**当前截图**为准（A 横屏 2560x1440，B 1920x1080）；App 层优先用 `tap_text.sh` 文本定位，坐标仅作兜底。

自动化状态说明：`✅ 已自动化`（脚本可跑）、`🟡 半自动`（脚本辅助 + 人工确认画面）、`⛔ 阻塞`（依赖 App/代理未实现）。

| ID | 用例 | 自动化状态 |
| --- | --- | --- |
| C00 | 环境与脚本冒烟 | ✅ `ensure_devices.sh` / `run_selftest.sh` / `run.sh --skip-b` |
| C01a | 启动 App + 连接页空态 | ✅ 2026-11-14 12:41 PASS，证据 `A1-B-connect-empty-3.png` |
| C01b | 手动连接面板展开/收起 | 🟡 展开 PASS（`A2-B-panel-expanded-final.png`）；收起未稳定复现（点行中部 900,965 可展开，点文字 90,969 不触发） |
| C01 | 连接（输入 IP+端口 → 出画面） | ✅ 2026-11-14 13:05 PASS：清 token→配对 112233→出画，证据 `C15-C01-stream.png` |
| C02 | 断开 | ✅ PASS（交付包，Lead 实测）：App 前台串流中两次 BACK → 「断开串流？」→ 断开回连接页、代理 `会话已停止`；证据 `F18-back-test.png` / `F20-disconnected.png`；代码 `ui/AppRoot.kt:155` |
| C03 | 画质切换（流畅/均衡/高清/极致/自适应） | ✅ PASS：720@30/1.5M、1080@60/4M、1440@60/8M、1920@60/12M + meta 720x404/1080x606/1440x810/1920x1080，证据 `C03-B-smooth/hd/ultra.png`；自适应开关截图 `C03-B-adaptive.png` |
| C04 | 帧率切换 | 🟡 SKIP：App 本地设置为真源，外部 API 改帧率会被重连覆盖（设计）；需 App 面板逐档用例，证据 `C04-B-fps30/120.png` 仅 API 层 |
| C05 | DPI 适配开关 | ✅ PASS：MATCH→A `wm size=1920x1080 density=280`（Override 生效）；FIT→复原 1440x2560@360；证据 `C05-B-match.png`/`C05-B-fit.png` |
| C06 | 悬浮球 显示/隐藏/拖动 | 🟡 `tap_text.sh` + `swipe.sh` + 截图 |
| C07 | 单击 | ✅ A 侧断言 `assert_change.sh`；经 App 需首装后补 |
| C08 | 长按 | ✅ A 侧断言 `longpress.sh` + `wait_for.sh` |
| C09 | 滑动 | ✅ `verify_touch.sh` / `assert_change.sh` |
| C10 | 双指 | ⛔ 需 `sendevent` 多点注入或 App 内置手势 |
| C11 | 返回键/主页键 | ✅ `key.sh` + `foreground.sh` / `assert_change.sh` |
| C12 | 横竖屏 | ✅ `settings put system user_rotation` + 截图断言 |
| C13 | 断线重连 | ✅ PASS：杀代理→App 约 8s 自动重连并显示「已重新连接」，证据 `C13-B-reconnect-after-proxy-change.png`（换设备同测，原设备恢复未单测） |
| C14 | 错误 IP 提示 | ✅ 2026-11-14 12:40 PASS（`代理拒绝连接（127.0.0.1:27180）`，证据 `C14-B-bad-ip.png`） |
| C15 | 配对码鉴权 | ✅ 未配对提示 PASS（`C15-B-unpaired.png`）+ 配对成功 PASS（`C15-C01-stream.png`）；错误码/限流未覆盖 |

---

## 0. 执行面与环境权威性（2026-11-14 12:20 更新，重要）

### 0.1 谁是权威验收面

| 面 | 角色 | 现状 | 断言策略 |
| --- | --- | --- | --- |
| **B（实例1，`127.0.0.1:16416`）** | 控制端/App | App 跑在这里，**权威面** | 绝对断言（文案/节点/截图/像素） |
| A（实例0，`127.0.0.1:16384`） | 被控端 | **有外部并行 agent 用 ws-scrcpy 驱动**（会旋转/改 wm/pkill scrcpy） | 只做**相对断言**（前后变化比例），且必须在证据/报告里标注“A 环境不稳定” |

- A 侧绝对断言（如“按 HOME 必回桌面”）会因外部输入而误判 —— 2026-11-14 12:14 实测 `A_home_input_assert` 失败（changed_ratio=5.6e-05，A 前台当时被外部改成 com.mumu.store）。
- A 侧可用结论只有两类：**有没有变化**（`changed_ratio`）、**变化幅度**；不能断言“变成哪个界面”。

### 0.2 当前 APK 实测基线（12:15:29 装到 B）

- `com.mumulink.remote` versionName 1.0.0 / versionCode 1；APK 19,109,327B mtime 12:14:35 sha256 `f25ed122…bd73defb`
- 首页 ConnectScreen 实测节点（1920x1080 横屏，uiautomator）：15 个，关键文案：
  `MuMuLink` / `局域网串流 · 低延迟` / `扫描`（右上，desc=开始扫描）/ `局域网已就绪` / `点右上角重新扫描` /
  `还没有找到电脑` / `确认电脑端 MuMuLink 已启动…` / `扫描局域网` / `手动连接` / `知道电脑 IP 时用` /
  `在电脑上打开 http://<电脑IP>:27180 完成配对` / `MuMuLink 1.0.0 · 端到端加密局域网直连`
- 点开「手动连接」后：`电脑 IP` / 占位 `192.168.1.20` / EditText `27180` / `配对码（可选）` / `连接`
- 证据：`evidence/C01-B-connect-screen.png`、`C14-B-manual-panel.png`

### 0.3 执行分组（按依赖）

| 组 | 用例 | 说明 |
| --- | --- | --- |
| **G1 现在可跑**（不依赖已连接，B 单页） | C00、首页渲染/空态、扫描开关、手动连接面板展开/收起/输入、C14 错误 IP、返回栈、首启权限弹窗、ConnectScreen 横竖屏锁定 | 需 B 空闲；不需要代理正确 |
| **G2 需连接态**（Lead 联调后） | C01、C02、C03、C04、C05、C06、C07、C08、C09、C10、C11、C12、C15 | 代理在线 + B/A 已配对出画 |
| **G3 需可自由重启代理** | C02 的“画面冻结”断言、C13 断线重连、C15 错误码 | 杀代理进程再拉起；**不要杀 Lead 的 27180 实例**，用 `tools/e2e/config.e2e.json`（27380 段）另起一个 |

### 0.4 App 实装文案速查（写 `tap_text`/`wait_for` 用）

- 连接页：`扫描`、`停止扫描`、`扫描局域网`、`手动连接`、`电脑 IP`、`配对码（可选）`、`连接`、`正在寻找电脑…`、`还没有找到电脑`、`局域网已就绪`
- 串流页 HUD：`画质`、`显示适配`、`串流设置`、`断开连接`、`断开串流？`、`继续串流`、`双指缩放 · 长按直接操作电脑`
- 设置面板：`画质`、`自适应`、`帧率`、`分辨率`、`码率`、`显示适配`、`适配屏幕`、`匹配 DPI`、`悬浮球`、`按键`、`断开连接`
- 悬浮球：`悬浮球已隐藏 · 点此恢复`、`显示悬浮球`、`隐藏`
- 错误/状态文案（断言错误提示用）：`连接失败`、`连接失败：…`、`连接超时`、`代理拒绝连接`、`代理端口 … 拒绝连接`、
  `找不到主机 …`、`请检查 IP/端口是否正确`、`请检查 IP 地址`、`配对码不正确`、`需要配对码`、`代理要求先配对`、
  `请回到连接页重新连接`、`连接中断，正在重连…`、`已重新连接`、`重连第 N 次失败：…`
- 权限相关：首启可能弹通知权限；`foreground.sh B --package` 用来区分“App 在前台”/“被系统弹窗顶掉”。

### 0.5 C14 错误 IP 提示（2026-11-14 12:40 实跑 PASS）

```bash
cd tools/e2e
S=127.0.0.1:16416
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell am force-stop com.mumulink.remote
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell am start -n com.mumulink.remote/.MainActivity
./tap_text.sh B "手动连接" --exact          # 面板展开（在屏幕下半部）
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell input swipe 960 950 960 350 400   # 上滑露出字段
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell input tap 184 600                 # 点 IP 框
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell input text "127.0.0.1"           # 端口留 27180（无监听）
/d/app/Android/Sdk/platform-tools/adb.exe -s $S shell input tap 960 847                 # 连接
./wait_for.sh B "连接失败|拒绝连接|找不到主机|无法连接|失败" --timeout 15
./shot.sh B ../../evidence/C14-B-bad-ip.png
```

**实测结果**：`RESULT=FOUND waited=2s`（命中 `拒绝连接`），横幅 **`代理拒绝连接（127.0.0.1:27180）`**，另有 `重试`/`手动连接` 两个恢复入口；App 不崩溃、停留连接页；`最近连接` 新增 `127.0.0.1`。证据 `C14-B-typed-ip.png`、`C14-B-bad-ip.png`。

**判定**：15s 内出现错误文案；错误可读且带恢复入口；App 不崩溃；输入框可再次编辑/重试。

---

## C00 环境与脚本冒烟

**前置**：无（不依赖 APK）。

**步骤**：

```bash
cd tools/e2e
./ensure_devices.sh --tune              # 期望 RESULT=PASS，A/B 都 BOOT=1
./devices.sh                            # 记录 serial/分辨率/前台
./env_guard.sh A --seconds 2 --samples 2   # A 侧采样：RESULT=STABLE 才继续做 A 断言
./shot.sh A evidence/C00-A.png
./shot.sh B evidence/C00-B.png
./run_selftest.sh --skip-install --skip-dual   # 期望 FAIL=0
```

**判定标准**：
- `ensure_devices` 退出码 0，A/B `BOOT=1`；
- 两张 PNG 有效且 >10KB（`shot.sh` 自带校验）；
- `run_selftest` 汇总 `FAIL=0`（SKIP 允许）。

**证据**：`C00-A.png`、`C00-B.png`、`evidence/_logs/selftest-<ts>.log`。

---

## C01 连接

**前置**：代理已启动且监听 `0.0.0.0:27180`；APK 已安装到 B；A 已开机。

**步骤**：

1. 端口体检（记录监听地址）：`./net_sim.sh ports` → 期望 `0.0.0.0:27180/27182/27183`、`UDP 0.0.0.0:27181`；
   若出现 `LOOPBACK_ONLY_WARNING` 立即判 FAIL（App 必然连不上）。
2. 从实例内部确认宿主可达：`./net_sim.sh host B` → `PING 10.0.2.2 REACHABLE`。
3. 启动 App：`./foreground.sh B --package` 应为 `com.mumulink.remote`；否则 `./install_apk.sh --target B --launch`。
4. 在连接页输入宿主地址 `10.0.2.2`（或 `net_sim.sh ip` 返回的局域网 IP）+ 端口 `27180`：
   - 优先 `./tap_text.sh B "<输入框文本/ID>"`，坐标兜底 `./tap.sh B <x> <y>`；
   - 文本输入用 `./type.sh B "10.0.2.2"`（仅 ASCII）。
5. 点"连接"：`./tap_text.sh B "连接"`。
6. 等待：`./wait_for.sh B "已连接|连接成功|断开连接" --timeout 20`。
7. 截图留证：`./shot.sh B evidence/C01-B-connected.png`；`./shot.sh A evidence/C01-A-fullscreen.png`。

**判定标准**：
- 20s 内出现"已连接"状态且 B 画面出现 A 的桌面/当前界面（人工确认，与 `C01-A-fullscreen.png` 一致）；
- `foreground.sh B --package` 仍为 `com.mumulink.remote`（不崩溃/不闪退）；
- 代理日志无 "ECONNREFUSED"。

**证据**：`C01-B-connected.png`、`C01-A-fullscreen.png`、`net_sim ports` 输出、代理日志片段。

---

## C02 断开

**前置**：C01 已通过（处于已连接状态）。

**步骤**：

1. 在 B 的连接页/侧栏点"断开"：`./tap_text.sh B "断开"`；
2. `./wait_for.sh B "连接|未连接|输入" --timeout 10`；
3. 断开后验证"画面不再更新"：先 `./shot.sh A evidence/C02-A-home.png`，在 A 上执行
   `./assert_change.sh A "input keyevent KEYCODE_HOME" --label C02-A-home --settle 2`（A 自身变化应 PASS），
   再对比 B 前后两张截图的差异应 < 0.002：
   `"$PY" imgdiff.py C02-B-before.png C02-B-after.png --assert`（用 `probe_state.sh diff B --baseline ... --assert same --threshold 0.002` 更简单）。

**判定标准**：
- 5s 内回到连接页/未连接状态；
- A 操作不再反映到 B（B 两次截图 `changed_ratio < 0.002`）；
- App 不崩溃，再次连接可成功（不要求杀进程）。

**证据**：`C02-B-disconnected.png`、`C02-B-before.png`、`C02-B-after.png`。

---

## C03 画质切换（流畅 / 均衡 / 高清 / 极致 / 自适应）

**前置**：C01 已连接且画面持续刷新。

**步骤**：

1. 打开画质/清晰度菜单：`./tap_text.sh B "画质|清晰度"`；若为下拉/侧栏，用 `./ui_dump.sh B --nodes` 先查节点。
2. 依次选择五档，每档：
   - 等待 3~5s；
   - 截图 `./shot.sh B evidence/C03-B-quality-<name>.png`；
   - 用 `imgdiff.py` 与上一档比较（应只见 UI/画质变化，不应黑屏）。
3. 每档检查代理视频端口仍在推送（`./net_sim.sh ports` 中 27182 保持 LISTENING，若有连接数可看）。
4. 切回"自适应"，20s 观察是否抖动/频繁重连。

**判定标准**：
- 每次切换 5s 内画面恢复，无纯黑屏：截图平均像素亮度 > 10（可用 `"$PY" -c` 统计）；
- 无错误提示（`wait_for B "错误|失败|超时" --not` 在切换后 5s 内不命中）；
- 无崩溃，不回落连接页；
- 自适应档 20s 内不出现连续重连（代理日志无重复 handshake）。

**证据**：`C03-B-quality-flow.png`、`-balanced.png`、`-hd.png`、`-ultra.png`、`-auto.png`、代理日志。

---

## C04 帧率切换

**前置**：C01 已连接；App 需有帧率选项（30/60/自适应 或类似）。

**步骤**：

1. 打开帧率菜单：`./tap_text.sh B "帧率|FPS"`；
2. 选择 30 → 等 3s → `./shot.sh B evidence/C04-B-fps-30.png`；
3. 选择 60 → 等 3s → `./shot.sh B evidence/C04-B-fps-60.png`；
4. 选择自适应 → 等 5s → 截图；
5. 若 App 显示实时 FPS：读取屏幕文本（`./ui_dump.sh B --nodes | grep FPS`），记录 10s 内均值；
   若代理有统计接口/日志，抓取实际编码/发送帧率。

**判定标准**：
- 选项切换后 5s 内不卡死、不黑屏、不断线；
- 60fps 档实测均值 ≥ 45fps（或与 30fps 档有 ≥1.3 倍差异）；30fps 档 25~35fps；
- 自适应在画面静止时降帧、有操作时升帧（有日志则对比，否则记录观察）。

**证据**：`C04-B-fps-*.png`、FPS 文本截图/代理统计片段。

---

## C05 DPI 适配开关

**前置**：C01 已连接。

**步骤**：

1. 记录 A 原始参数：`adb -s 127.0.0.1:16384 shell "wm size; wm density" > evidence/C05-A-wm-before.txt`；
2. 在 B 的设置里切换"DPI 适配"开关（`./tap_text.sh B "DPI"`）；
3. 等 3s，截图 `./shot.sh B evidence/C05-B-dpi-on.png`（关闭时再截 `C05-B-dpi-off.png`）；
4. 断言 A 的参数未被修改：重新 `wm size/density` 与 before 比对；
5. 用 `imgdiff.py` 对比开关前后 B 截图（应有可感知的缩放/清晰度变化）。

**判定标准**：
- 开关切换 3s 内生效（B 截图 changed_ratio > 0.01，或画面缩放比按预期变化）；
- A 的 `wm size/density` 与 before 完全一致（DPI 适配只影响渲染，不改被控端）；
- 关闭后恢复原状，无残留裁切/黑边。

**证据**：`C05-A-wm-before.txt`、`C05-A-wm-after.txt`、`C05-B-dpi-on.png`、`C05-B-dpi-off.png`。

---

## C06 悬浮球（显示 / 隐藏 / 拖动）

**前置**：C01 已连接；App 设置里有悬浮球开关。

**步骤**：

1. 显示：`./tap_text.sh B "悬浮球"`（开启）→ 3s → `./shot.sh B evidence/C06-B-ball-on.png`；
2. 定位悬浮球坐标：`./ui_dump.sh B --nodes | grep -i "ball\|悬浮"`，或对比开关前后截图定位新增圆形区域；
3. 拖动：`./swipe.sh B <x1> <y1> <x2> <y2> 600`；
4. 再次截图 `C06-B-ball-moved.png`，断言悬浮球位置变化（坐标差异 > 50px）；
5. 隐藏：再点开关 → 截图 `C06-B-ball-off.png`，断言悬浮球消失（对应区域与 on 图差异显著）；
6. 点击悬浮球：`./tap.sh B <ball_x> <ball_y>` → 断言展开菜单/面板（截图变化 + 无崩溃）。

**判定标准**：
- 开关 3s 内生效（出现/消失）；
- 拖动后位置更新并保持（重新进入 App 仍在原位或按设计复位）；
- 悬浮球不遮挡关键按钮；点击有响应，不误触下层控件。

**证据**：`C06-B-ball-on.png`、`C06-B-ball-moved.png`、`C06-B-ball-off.png`。

---

## C07 单击

**前置**：C01 已连接；A 处于系统设置主界面。

**步骤**：

1. A 准备：`./known_screen.sh A settings --settle 2`；`./shot.sh A evidence/C07-A-before.png`；
2. B 画面上点击 A 的"已连接设备"行（坐标按 B 截图映射；App 支持坐标映射时用 `./tap.sh B <x> <y>`）；
3. 等待 2s，`./wait_for.sh A "已连接的设备|蓝牙|配对的设备" --timeout 8`
   （或断言前台变化：`./foreground.sh A --expect "Settings\\\$ConnectedDeviceDashboardActivity"`）；
4. 断言 A 截图变化：`./probe_state.sh diff A --baseline evidence/C07-A-before.png --assert changed --threshold 0.01`；
5. B 端截图 `C07-B-stream-after.png` 应同步显示新页面。

**判定标准**：
- A 在 3s 内进入对应子页面（文本/前台断言命中）；
- A `changed_ratio ≥ 0.01`；
- B 画面同步更新（B 截图与 A 新界面一致），无坐标偏移（点哪走哪）。

**证据**：`C07-A-before.png`、`C07-A-after.png`、`C07-B-stream-after.png`。

---

## C08 长按

**前置**：C01 已连接；A 处于桌面（`./known_screen.sh A home`）。

**步骤**：

1. B 画面上长按 A 桌面空白处 800ms：`./longpress.sh B <x> <y> 800`；
2. 等待 1s，断言 A 出现桌面编辑/壁纸菜单：`./wait_for.sh A "壁纸|主屏幕设置|桌面设置|小部件" --timeout 8`；
3. 截图 `./shot.sh A evidence/C08-A-longpress.png`；
4. 按返回退出：`./key.sh A KEYCODE_BACK`。

**判定标准**：
- 长按被识别为长按而不是单击（A 出现上下文菜单/桌面编辑，而非打开 App）；
- 按住时长阈值合理（≥500ms 触发；<300ms 不触发可另测）；
- B 侧无卡顿，A 无 ANR。

**证据**：`C08-A-longpress.png`。

---

## C09 滑动

**前置**：C01 已连接；A 处于设置主界面。

**步骤**：

1. `./known_screen.sh A settings --settle 2`；`./shot.sh A evidence/C09-A-before.png`；
2. B 画面上滑动（从下往上）：`./swipe.sh B <x> <y1> <x> <y2> 400`；
3. 等 1s，`./shot.sh A evidence/C09-A-after.png`；
4. 断言 A 变化：`./probe_state.sh diff A --baseline evidence/C09-A-before.png --assert changed --threshold 0.01`；
5. 反向滑动恢复，重复断言。

**判定标准**：
- A 列表确实滚动（前后截图差异 ≥ 0.01，且顶部条目文本变化）；
- 惯性/跟手无明显延迟（人工观感：拖动时画面跟手，松手后有惯性）；
- 不触发误点击（滑动结束不进入任何子页面：`foreground.sh A` 仍为 Settings）。

**证据**：`C09-A-before.png`、`C09-A-after.png`。

---

## C10 双指（多指手势）

**前置**：C01 已连接；A 打开支持缩放的界面（浏览器/相册，或 MuMu 桌面支持双指缩放时）。

**步骤**（需多点注入能力，当前 adb 默认只能单指）：

1. A 打开可缩放页面（例如 `am start -a android.intent.action.VIEW -d https://example.com`，需 A 有浏览器）；
2. B 上双指捏合（App 若支持多点转发；否则用 A 侧 `sendevent` 注入或人工）；
3. 断言 A 画面缩放比例变化（前后截图内容边界变化 > 0.01）且无 ANR。

**判定标准**：
- 双指缩放/旋转被正确转发（A 画面按手势缩放）；
- 无双指误判为单击/滑动；
- 操作后 App 与 A 均无 ANR/崩溃。

**现状**：⛔ 阻塞——adb 无内置多点手势，需要 App 支持双指转发（或 A 侧 root + `sendevent` 脚本，待补）。

---

## C11 返回键 / 主页键

**前置**：C01 已连接；A 处于设置子页面（C07 之后）。

**步骤**：

1. 记录当前前台：`./foreground.sh A`；
2. B 上触发"返回"（App 工具栏返回按钮，或 `./key.sh B KEYCODE_BACK` 若 App 转发）：
   断言 A 回到上一级：`./assert_change.sh A "input keyevent KEYCODE_BACK" --label C11-A-back --settle 2`；
3. B 上触发"主页"：断言 A 回桌面：`./assert_change.sh A "input keyevent KEYCODE_HOME" --label C11-A-home --settle 2`；
4. 检查 B 画面同步且不退出 App。

**判定标准**：
- 返回键在 A 上逐级返回，主页键回桌面；
- B 的 App 自身 UI 不被误退出（前台仍为 `com.mumulink.remote`）；
- A 侧变化比例 ≥ 0.01，B 画面 2s 内同步。

**证据**：`C11-A-back-before/after.png`、`C11-A-home-before/after.png`。

---

## C12 横竖屏

**前置**：C01 已连接；A 当前横屏。

**步骤**：

1. 记录 A 尺寸：`adb -s 127.0.0.1:16384 shell "wm size"`；截图 `C12-A-land.png`；
2. 强制 A 竖屏：`adb -s 127.0.0.1:16384 shell "settings put system accelerometer_rotation 0; settings put system user_rotation 0"`；
3. 等 3s，截图 A `C12-A-portrait.png`、B `C12-B-portrait.png`；
4. 恢复横屏 `user_rotation 1`，再截图确认复原；
5. 断言：A 截图宽高互换（如 2560x1440 → 1440x2560），B 画面随之旋转且无拉伸/黑边。

**判定标准**：
- A 3s 内完成旋转（`shot.sh` 输出的 size 宽高互换）；
- B 画面同步旋转，比例正确（无黑边 > 2% 或明显拉伸）；
- App 不断线、不崩溃；旋转期间无花屏（连续两帧可解码）。

**证据**：`C12-A-land.png`、`C12-A-portrait.png`、`C12-B-portrait.png`。

---

## C13 断线重连

**前置**：C01 已连接；代理启动命令已知（`net_sim.sh proxy start --cmd "..."`）。

**步骤**：

1. 记录 A 当前画面：`./shot.sh A evidence/C13-A-before.png`；记录 B 已连接截图 `C13-B-connected.png`；
2. 断线：`./net_sim.sh proxy kill --port 27180`；
3. 5s 内 B 应出现断线提示：`./wait_for.sh B "断开|重连|连接失败|网络" --timeout 10`；截图 `C13-B-disconnected.png`；
4. 恢复：`./net_sim.sh proxy start --cmd "<真实启动命令>" --port 27180`；
5. 等 20s，断言自动重连：`./wait_for.sh B "已连接|连接成功" --timeout 25`；截图 `C13-B-reconnected.png`；
6. 断言 A 未受影响：`./probe_state.sh diff A --baseline evidence/C13-A-before.png --assert same --threshold 0.02`（允许时钟/动画小差异）。

**判定标准**：
- 断线后 10s 内出现明确提示（不是静默卡死）；
- 代理恢复后 25s 内自动重连成功，**无需重新输入 IP/配对码**；
- 重连后画面正常（非黑屏/花屏）；
- A 端状态未被破坏。

**证据**：`C13-A-before.png`、`C13-B-connected.png`、`C13-B-disconnected.png`、`C13-B-reconnected.png`、代理日志。

---

## C14 错误 IP 提示

**前置**：App 在连接页；B 可正常输入。

**步骤**：

1. 输入不可达地址 `192.0.2.1`（TEST-NET-1，保留不可路由）+ 端口 `27180`：
   `./tap_text.sh B "<地址输入框>"` → `./type.sh B "192.0.2.1"`；
2. 点连接，计时；
3. `./wait_for.sh B "失败|超时|无法连接|请检查" --timeout 20`；
4. 截图 `C14-B-error.png`；确认可返回修改：`./tap_text.sh B "返回|重试|重新输入"`；
5. 再用错误端口（如 `1`）重复一次。

**判定标准**：
- 15s 内出现明确错误提示（不能无限转圈 >30s）；
- 提示文案能指导用户（检查 IP/端口/网络）；
- 可返回修改并再次连接；App 不崩溃。

**证据**：`C14-B-error.png`、`C14-B-retry.png`。

---

## C15 配对码鉴权

**前置**：PC 代理已实现配对码（⛔ 依赖 pc/ 代码，当前未实现）。

**步骤**：

1. 代理启动后显示/打印配对码（6 位数字或二维码），记录到 `C15-pairing-code.txt`；
2. B 输入错误配对码 → 断言 5s 内被拒绝并提示："配对码错误|鉴权失败"；
3. B 输入正确配对码 → 断言连接成功（C01 流程）；
4. 重连场景：重启 App 后用旧配对码尝试，按设计应拒绝或需重新配对；
5. 抓包/日志确认未配对时控制/视频端口不可用。

**判定标准**：
- 错误配对码 5s 内拒绝，不泄露内部信息；
- 正确配对码连接成功；
- 未配对无法建立控制/视频会话（不是"先连上再校验"）。

**证据**：`C15-pairing-code.txt`、`C15-B-wrong-code.png`、`C15-B-pair-ok.png`、代理鉴权日志。

---

## 附：每轮验收固定动作

```bash
cd tools/e2e
./ensure_devices.sh --tune
./run_selftest.sh --skip-dual        # APK 存在时会自动装 B
bash ./run.sh                        # 一键冒烟 + 摘要
```

报告：复制 `evidence/REPORT-template.md` → `evidence/REPORT-<date>.md`，按用例表填结果并引用证据文件名。
