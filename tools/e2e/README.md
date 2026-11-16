# MuMuLink E2E Lab（tools/e2e）

本目录是 MuMuLink 的端到端测试实验室：一套可复用、可断言的 adb 驱动脚本，用两台 MuMu 实例互相验证
「手机 App 控制 PC 上的模拟器」这条链路。

- **A = 实例 0（主实例，被控端）**：`127.0.0.1:16384`，1440x2560@360dpi，Android 15（MuMu 12 内核）。
- **B = 实例 1（测试手机，控制端）**：adb 端口由 MuMuManager 分配（当前 `127.0.0.1:16416`），1080x1920@280dpi。
- PC 代理端口规划：HTTP `27180`、UDP 发现 `27181`、视频 `27182`、控制 `27183`。
  **代理必须监听 `0.0.0.0`**；只监听 `127.0.0.1` 时实例里的 App 连不上。

> 截图坐标 = 当前旋转后的坐标。两台实例当前都是横屏（A 截图 2560x1440、B 1920x1080），
> 所以 `tap.sh` 的 x/y 直接用截图里的像素坐标即可；`wm size` 报的是自然竖屏尺寸，不要混用。

---

## 0. 前置条件

| 依赖 | 本机路径/版本 |
| --- | --- |
| adb | `/path/to/Android/Sdk\platform-tools\adb.exe` |
| MuMuManager | `C:\Program Files\Netease\MuMuPlayer\nx_main\mumu-cli.exe` |
| Python | `python`（PIL 12.3、numpy 2.5 已装） |
| 7z（可选，拆 APK 用） | `7z` |
| APK 约定路径 | `android/app/build/outputs/apk/debug/app-debug.apk` |

**本目录的脚本绝不运行 gradle**；APK 由 Lead 构建，脚本只检查存在性/mtime/sha256。

首次使用建议先跑：

```bash
cd tools/e2e
./ensure_devices.sh --tune     # 等 A/B 上线、必要时拉起实例、关动画+常亮
./run_selftest.sh              # 脚本集自测（APK 缺失时安装步骤记 SKIP）
```

---

## 1. 一键跑最小 E2E

```bash
bash tools/e2e/run.sh                # 确保在线 → 装 APK → 启动 App → input 操作 → 截图 + 摘要
bash tools/e2e/run.sh --skip-b       # 只有 A 时：环境 + 系统级冒烟
bash tools/e2e/run.sh --skip-install # 已手工装好 App，只想跑交互冒烟
```

产物：

- `evidence/<label>-summary.md`：本次运行摘要（步骤表 + 证据索引 + 结论）
- `evidence/<label>-*.png`：每步截图
- `evidence/_logs/<label>.log`：完整终端日志

退出码 `0 = PASS`（允许 SKIP），`1 = FAIL`，`2 = APK 缺失（只做了环境冒烟，等构建后重跑）`。
`--apk <path>` 可指定 APK；`--skip-install` 表示“App 已手工装好，只跑交互冒烟”。

---

## 2. 脚本清单

所有脚本都支持 `-h`，退出码统一：`0` 成功/PASS、`1` 失败、`2` 参数错误。

### 2.1 设备与环境

| 脚本 | 用法 | 说明 |
| --- | --- | --- |
| `env.sh` | `. ./env.sh` | 共享变量与函数（serial 别名、adb 封装、PNG 校验）。其他脚本内部 source。 |
| `mumu.py` | `"$PY" mumu.py info 0` / `port 1` / `started 1` | 解析 `MuMuManager info -v all` 的 JSON（UTF-8 输出）。 |
| `devices.sh` | `./devices.sh [--wait 180] [--json]` | 快照：两实例 adb 状态、端口、分辨率、前台 Activity。 |
| `ensure_devices.sh` | `./ensure_devices.sh [--timeout 120] [--boot-timeout 240] [--no-launch] [--skip-b] [--tune]` | 确保 adb 在线：读 MuMu 权威端口 → `adb disconnect/connect` → 必要时 `control -v <i> launch` → 等 `boot_completed=1`。结果写 `evidence/_state/devices.env`。 |
| `net_sim.sh` | 见 §5 | 网络/断线模拟与代理端口体检。 |
| `install_apk.sh` | `./install_apk.sh [apk] [--target B|A|both] [--no-uninstall] [--launch]` | 校验 zip+Manifest、sha256、uninstall→install -r -t、`pm list`、`versionName`；日志 `evidence/install-<ts>.log`。 |
| `foreground.sh` | `./foreground.sh A --package` / `--expect <regex>` | 读当前前台包名/Activity。 |
| `agent_api.sh` | `./agent_api.sh hello` / `pair 112233` / `quality 720 60 2000000` / `display match 1920 1080 280` / `logs` / `stop` | 直连**自测代理**的 HTTP API（端口取自 `config.test.json`，默认 27380），用于绕过 App 断言代理侧行为（画质参数、MATCH/FIT、日志）。`pair` 成功把 token 写到 `evidence/_state/token.txt`。 |

### 2.2 截图与输入

| 脚本 | 用法 | 说明 |
| --- | --- | --- |
| `shot.sh` | `./shot.sh A evidence/C01-A.png` | `exec-out screencap -p`，校验 PNG 签名与大小，重试 3 次；输出 `SAVED ... size=WxH`。 |
| `tap.sh` | `./tap.sh A 1280 700` | 单击；支持百分比：`./tap.sh A 50% 50%`（按截图尺寸换算）。 |
| `tap_text.sh` | `./tap_text.sh B "连接" [--field text|desc|id|any] [--exact] [--index N] [--list] [--dry-run] [--timeout 10]` | **按 UI 文本定位并点按**（uiautomator），App 层自动化首选。 |
| `swipe.sh` | `./swipe.sh A 1280 1200 1280 300 400` | 滑动（末参数为毫秒）。 |
| `longpress.sh` | `./longpress.sh A 1280 700 800` | 长按（按下-保持-抬起，时长毫秒）。 |
| `key.sh` | `./key.sh A KEYCODE_BACK` / `HOME` | 按键，自动补 `KEYCODE_` 前缀。 |
| `type.sh` | `./type.sh B "192.168.1.10"` | 输入文本（空格转 `%s`；**非 ASCII 需装 ADBKeyboard**，脚本会警告）。 |
| `ui_dump.sh` | `./ui_dump.sh A [--nodes] [--raw] [--out FILE]` | 导出 UI XML（可读节点摘要）。 |
| `known_screen.sh` | `./known_screen.sh A settings|home|display|wifi|about|apps|notifications|quick|calc|clock [--settle 2]` | 把被控端 A 切到已知界面（断言用）。 |

### 2.3 断言与探针

| 脚本 | 用法 | 说明 |
| --- | --- | --- |
| `imgdiff.py` | `"$PY" imgdiff.py before.png after.png [--pixel-threshold 24] [--crop-top 0.04] [--crop-bottom 0.02] [--json]` | 像素差异：`changed_ratio`（某通道差 > 阈值的像素比例）、`mean_abs`、4x4 热点区域。默认裁掉状态栏/导航栏避免时钟误报。 |
| `probe_state.sh` | `baseline [A]` / `diff [A] --baseline F --threshold 0.01 --assert changed|same` / `watch [A] --interval 1 --count 5` | 主实例状态探针：与基线比较，断言"画面有没有变"。 |
| `assert_change.sh` | `./assert_change.sh A "input keyevent KEYCODE_HOME" --label C11-A-home --threshold 0.01 --settle 2` | 执行一条 adb shell 动作并断言屏幕变化；证据 `<label>-before/-after.png`。 |
| `verify_touch.sh` | `./verify_touch.sh [A]` | 组合自检：设置界面滑动 → 断言变化 → 单击 → 回桌面。 |
| `wait_for.sh` | `./wait_for.sh B "已连接" --timeout 15` / `--not` | 等 UI 文本出现/消失（grep uiautomator XML）。 |
| `dual_instance_check.sh` | `./dual_instance_check.sh` | 无 App 时验证双实例通路：A 截图→B 操作→A 变化→B 解码截图。 |
| `env_guard.sh` | `./env_guard.sh A --seconds 2 --samples 2` | 采样前台+截图，判断采样窗口内有没有并发输入/界面变化；输出 `RESULT=STABLE|UNSTABLE`。A 侧断言前先跑，UNSTABLE 则重跑或标注“环境不稳定”。 |
| `run_selftest.sh` | `./run_selftest.sh [--skip-dual] [--skip-install]` | 脚本集回归自测，汇总 PASS/FAIL/SKIP。 |

---

## 3. 截图命名规范

统一格式：`evidence/<step>-<desc>.png`

- `<step>`：用例编号（`C01`…`C15`，见 `CHECKLIST.md`）或运行前缀（`run-20261114-1210`、`smoke-dual`）。
- `<desc>`：小写短描述，用 `-` 连接，例如 `B-connect-ok`、`A-settings-before`、`B-stream-preview`。
- 断言类脚本自动产出 `<label>-before.png` / `<label>-after.png`。
- 每次跑 `run.sh` 生成 `evidence/<label>-summary.md` 与 `evidence/_logs/<label>.log`。
- 中间态/调试产物放 `evidence/_state/`（baseline、UI XML、watch 帧），不要混进正式证据。

示例：

```
evidence/C07-B-tap-ok.png
evidence/C07-A-before.png
evidence/C07-A-after.png
evidence/C11-A-home-before.png
evidence/C11-A-home-after.png
evidence/install-20261114-120812.log
```

---

## 4. 双实例互验方法（A 被控端 ← B 控制端）

目标：证明 **B 上的 App 能拉取 A 的画面**，且 **B 上的点按能改变 A**，最后 **B 截图证明解码正常**。

### 4.1 有 App 时的标准流程

```bash
cd tools/e2e

# 步骤 1：把 A 置于已知界面，截基线
./known_screen.sh A settings --settle 2
./shot.sh A evidence/C07-A-before.png

# 步骤 2：确认 B 上 App 在显示 A 的画面（连接成功）
./shot.sh B evidence/C07-B-stream-preview.png

# 步骤 3：在 B 的 App 画面上点按 A 的 "网络和互联网" 位置
#         （坐标可按 B 的截图目测，或用 App 自己的 UI 文本定位）
./tap.sh B 540 320
sleep 1

# 步骤 4：断言 A 的界面确实变了（像素差异比例）
./probe_state.sh diff A --baseline evidence/C07-A-before.png --assert changed --threshold 0.01

# 步骤 5：B 端截图留证（B 应显示 A 变化后的画面，证明解码链路正常）
./shot.sh B evidence/C07-B-after.png
```

判定：步骤 4 `RESULT=PASS`（A 变化比例 ≥ 阈值）且步骤 5 的 B 截图中能看到与 A 一致的新界面。

### 4.2 没有 App 时的通路自检

`dual_instance_check.sh` 用纯 adb 模拟同一套协议（不需要 APK）：

1. A 回桌面并截图（模拟"拉流首帧"）；
2. 在 A 打开设置并断言画面变化（证明捕获/差异检测有效）；
3. B 回桌面截图（证明 B 可被观察）；
4. 在 B 上拉通知栏并断言变化（证明对 B 的输入/截图有效）；
5. 断言 B 的操作没有影响 A（`--assert same`，阈值 0.002）。

证据：`smoke-dual-1..5*.png`。APK 装好后，把第 2 步换成"在 B 的 App 上点按 → 断言 A 变化"即可。

### 4.3 App 层定位建议

1. `./tap_text.sh B "连接" --list` 先看有哪些可点文本/ID；
2. 稳定后固化坐标或 `resource-id` 选择器（写进 `CHECKLIST.md` 的自动化列）；
3. App 首版安装后，用 `./ui_dump.sh B --nodes > evidence/_state/B-ui.txt` 导出一份节点清单备查。

---

## 5. 断线/重连与网络模拟（net_sim.sh）

模拟器里 `svc wifi disable` 不可靠（QEMU slirp 走虚拟网卡），所以断线主要靠 **杀/起代理进程**：

```bash
cd tools/e2e

# 代理端口体检：应看到 0.0.0.0:27180/27181/27182/27183；若显示 LOOPBACK_ONLY_WARNING 必须改监听地址
./net_sim.sh ports

# 从实例内部探测宿主（App 看到的世界）
./net_sim.sh host B        # ping 10.0.2.2 / 192.168.1.10 + 探测 27180..27183
./net_sim.sh status B      # 飞行模式/路由/宿主可达性

# 断线：杀掉监听 27180 的 node 代理（只允许 node.exe，除非 --force）
./net_sim.sh proxy kill --port 27180

# 重连：用真实启动命令拉起代理（写你的实际命令）
./net_sim.sh proxy start --cmd "node pc/server.js" --port 27180

# 或者一条命令重启
./net_sim.sh proxy restart --cmd "node pc/server.js" --port 27180

# 飞行模式（脚本会实测是否真的切断了宿主，模拟器上可能无效）
./net_sim.sh airplane on B
./net_sim.sh airplane off B
```

配合 CHECKLIST 的 C13：断线后 App 应出现"断开/重连中"提示；代理恢复后应自动重连（不要求重新输入 IP）。

---

## 6. 已知坑（都踩过）

1. **`uiautomator dump` 退出码不可信**：Android 15 上即使 dump 成功也常返回非 0，进程退出时还会 SIGSEGV
   （logcat crash buffer 可见 `Fatal signal 11 ... uiautomator`）。只以内容是否以 `<?xml` 开头为准；
   `wait_for.sh`/`tap_text.sh` 已处理。`/dev/tty` 输出来尾部会混入 `UI hierchary dumped to:` 文字，
   `tap_text.sh` 已改为文件式 dump + XML 截取清洗。
2. **scrcpy server 在跑时不要做 adb input**：在同一实例上 `adb shell input` 会挂死，随后整个 adb transport
   变 offline。测试前确认没有 `app_process ... com.genymobile.scrcpy.Server` 进程。
3. **B 的 adb 掉线恢复顺序**：`adb disconnect` → `adb connect` → 仍失败则 `MuMuManager control -v 1 restart`，
   `ensure_devices.sh` 覆盖前两步。
4. **本机 shell 是 WinuxCmd 模拟环境**：`sed -nE 's/.../\1/p'` 报 `unknown flag in s command`，
   `[[ =~ ]]` 会语法错误。脚本里统一用 awk/printf/参数展开/Python，不要引入 GNU sed/正则扩展。
5. **MSYS 路径转换**：脚本已 `export MSYS_NO_PATHCONV=1`，传文件给 native exe 时用 `e2e_win_path`（cygpath -w）。
6. **旋转坐标**：A 当前横屏，`wm size` 是自然竖屏 1440x2560，但截图/点击坐标是 2560x1440；以截图为准。
7. **状态栏时钟**：整屏比例断言会受时钟/动画影响，`imgdiff.py` 默认裁掉顶部 4%、底部 2%。
8. **APK 构建**：脚本绝不跑 gradle；APK 未出现时 `install_apk.sh` 退出 1、`run.sh` 只做环境冒烟并退出码 2（不是静默 SKIP）。
9. **B 重启后端口**：`ensure_devices.sh` 每次从 `MuMuManager info` 读权威 `adb_port`，不写死 16416。
10. **动画干扰**：`ensure_devices.sh --tune` 会关三档动画、设常亮；新实例重建后要重跑。
11. **A 有外部并发输入**：实例 0 上还有另一组并行 agent 用 ws-scrcpy 驱动（会旋转/改 wm/pkill）。A 侧只能做相对断言（前后 changed_ratio），不能断言“变成哪个界面”；报告里要标注“A 环境不稳定”。B（实例1）才是权威验收面。
12. **`net_sim.sh proxy start` 会留后台进程**：脚本本身 ≤20s 返回，但被拉起的 node 代理是后台子进程；在 agent 工具环境里该调用可能表现为“一直不返回”（工具按进程树等待）。要用 `run_in_background` 或单独终端；`proxy kill` 会按端口杀，杀不干净时加 `--force`。
13. **代理端口所有权**：Lead 的联调代理占 27180–27183，**不要杀/重启它**。自测断线重连用 `tools/e2e/config.e2e.json`（27380–27383、配对码 112233），命令：
    `./net_sim.sh proxy start --port 27380 --dir ../pc --cmd '"node.exe" agent.js --config /path/to/mumu-remote/tools/e2e/config.e2e.json"'`

---

## 7. 证据与报告

- 每次验收：`evidence/REPORT-<date>.md`，模板见 `evidence/REPORT-template.md`。
- 每条 CHECKLIST 用例的证据命名以用例号开头（`C09-...`），报告里只引用文件名。
- `run.sh` 自动生成的 `evidence/<label>-summary.md` 可直接作为报告"步骤结果"表的数据来源。
- 给 Lead 的验收报告：`evidence/E2E-REPORT.md`（每条用例=命令+截图+断言值；FAIL/SKIP 必须写原因）。
  **最终包与旧包必须分列**：报告表头标注包 sha256/mtime；用旧包跑出的结论不得写进“最终包”列。
- 自测代理配置：`tools/e2e/config.test.json`（HTTP 27380 / UDP 27381 / video 27382 / control 27383，device=127.0.0.1:16416，pairingCode=112233）。
  起法：`cd pc && "node.exe" agent.js --config "/path/to/mumu-remote/tools/e2e/config.test.json"`
  （在 agent 工具里用 `run_in_background`，否则会因后台 node 子进程看起来“一直不返回”）。

## 6. C 组新增（2026-11-14，最终包实测使用）

| 文件 | 作用 |
| --- | --- |
| `RUNBOOK-C-GROUP.md` | C15/C01/C03/C04/C05/C06/C13 的连接态测试手册（命令、断言、证据名、判定口径），按“先配对环境再逐步断言”的顺序写 |
| `ui_fields.py` | 从 uiautomator dump 里提取全部 EditText 的中心坐标：`"$PY" ui_fields.py <serial>` → `<idx> <cx> <cy> <text>`；用于连接面板逐字段填写（比坐标硬编码稳） |
| `case_pair_stream.sh` | 一键：pm clear → 启动 App → 展开手动连接 → 填 IP/端口/配对码 → 配对并连接 → 断言 StreamScreen；`--host 10.0.2.2 --port 27380 --code 112233`，`--no-clear` 保留 token |
| `config.test.json` | 27380 段自测代理配置（device 默认 B=127.0.0.1:16416，可改 A=16384；pairingCode 112233） |
| `agent_api.sh` | 代理 HTTP API 封装：`hello` / `pair <code>` / `quality <size> <fps> <bps>` / `display fit|match <w> <h> <dpi>` / `logs` / `stop`；token 缓存在 `evidence/_state/token.txt` |

**token 提取**：`pair` 成功后读取 `tools/e2e/config.test.json` 的 `token` 字段（64 hex），或直接 `"$PY" -c "import json,io;print(json.load(io.open('tools/e2e/config.test.json',encoding='utf-8'))['token'])"`。

**连接态测试要点**：
- 控 A（`device=127.0.0.1:16384`）而不是自环——自环时 App 本地点按经 scrcpy 回注会二次触发，画质 sheet 等非幂等控件会被抵消。
- App 重连时会用本地保存画质重新 `startSession`，会覆盖 `/api/session/quality` 的设置（见 `evidence/E2E-REPORT.md` §4.3）。
- BACK 键在串流页可能被转发到被控端，C02 本地断开确认框在最终包上未观察到。
