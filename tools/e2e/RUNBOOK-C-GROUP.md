# C 组（连接态）执行 Runbook —— B 自环（App 在 B、代理被控端也是 B）

**前置**：Lead 12:58 交回 B；最终 APK 已安装（记录 mtime/sha256 到 `evidence/_state/final-apk.txt`）；27380 段自测代理可用（`tools/e2e/config.test.json`，device=`127.0.0.1:16416`，pairingCode=112233）。

> 自环说明：代理把 B 自己的画面编码给 B 的 App 解码；MATCH/FIT 直接改 B 的 `wm size/density`。
> 每条用例结束都要把状态复原（FIT + 1080x1920 + 60fps + 默认画质 + 悬浮球默认），避免污染下一条。

## 0. 起代理（后台）

```bash
cd /c/Users/user/works/mumu-remote/pc
"node.exe" agent.js --config "/path/to/mumu-remote/tools/e2e/config.test.json"
# 在 agent 工具里用 run_in_background 启动；成功标志：
#   HTTP 已监听 0.0.0.0:27380，TCP 视频 27382 / 控制 27383
#   UDP 发现已监听 0.0.0.0:27381
cd ../tools/e2e && ./agent_api.sh hello     # paired=false（token 空）
./net_sim.sh ports                          # 确认 27380-27383 都在 0.0.0.0
```

## 1. C15 配对成功 → C01 出画（一次做完）

```bash
S=127.0.0.1:16416; ADB=/d/app/Android/Sdk/platform-tools/adb.exe
"$ADB" -s $S shell pm clear com.mumulink.remote     # 清掉旧 token（本轮 C15 失败根因）
"$ADB" -s $S shell am start -n com.mumulink.remote/.MainActivity
sleep 2
./shot.sh B ../../evidence/C01-B-start.png
./tap_text.sh B "手动连接" --exact
"$ADB" -s $S shell input swipe 960 950 960 350 400
"$ADB" -s $S shell input tap 184 600                       # IP 框
"$ADB" -s $S shell input text "10.0.2.2"
"$ADB" -s $S shell input tap 1766 600                      # 端口框清空后输入 27380
"$ADB" -s $S shell input text "27380"
"$ADB" -s $S shell input tap 145 738                       # 配对码框
"$ADB" -s $S shell input text "112233"
./shot.sh B ../../evidence/C01-B-filled.png
./tap_text.sh B "配对并连接" --timeout 15
./wait_for.sh B "画质|断开连接|正在连接|连接中" --timeout 20
sleep 3
./shot.sh B ../../evidence/C01-B-stream.png
./foreground.sh B --package
```

- 断言：`foreground.sh B --package = com.mumulink.remote`；`wait_for "画质"` FOUND（StreamScreen HUD）；App 日志有 `POST /api/pair`（logcat 里 `AgentClient`/`配对`），代理 stdout 有配对成功与 `会话启动：quality=... display=...` + `视频流初始 session meta：WxH`。
- 若失败：先看 `./agent_api.sh hello`（paired 是否 true）、再 logcat `-s MuMuLink`、再代理 stdout。

## 2. C03 画质四档（流畅/均衡/高清/极致/自适应）

HUD 右上 `画质` → 设置 sheet。面板可见文案 `串流设置`、`画质`、`自适应`、`帧率`、`分辨率`、`码率`、`显示适配`。

```bash
./tap_text.sh B "画质" --index 0            # HUD 上的画质按钮（注意面板里也有“画质”标签）
./shot.sh B ../../evidence/C03-B-sheet.png
# 逐档：点文案 → 等 2s → 记录代理日志（切换画质：<size>@<fps> <bps>，重启会话）
./tap_text.sh B "流畅" ; sleep 3 ; ./shot.sh B ../../evidence/C03-B-smooth.png
./tap_text.sh B "均衡" ; sleep 3 ; ./shot.sh B ../../evidence/C03-B-balanced.png
./tap_text.sh B "高清" ; sleep 3 ; ./shot.sh B ../../evidence/C03-B-hd.png
./tap_text.sh B "极致" ; sleep 3 ; ./shot.sh B ../../evidence/C03-B-ultra.png
./tap_text.sh B "自适应"                    # 开关：打开后手动档变暗
./shot.sh B ../../evidence/C03-B-adaptive.png
```

- 断言：每次切档后代理日志出现新的 `切换画质：...，重启会话`，且随后的 `视频流初始 session meta` 尺寸不超过对应档（流畅≤720/均衡≤1080/高清≤1440/极致≤1920）；App 不崩溃、画面恢复；自适应开启后四档按钮置灰（截图对比 + 节点 enabled=false）。
- 恢复：关自适应 → 选 `均衡`。

## 3. C04 帧率 / C05 DPI（FIT/MATCH）

```bash
# 帧率：面板内点 30/45/60/90/120 → 代理日志 maxFps 变化；HUD FPS 数字变化
./tap_text.sh B "30"  ; sleep 3 ; ./shot.sh B ../../evidence/C04-B-fps30.png
./tap_text.sh B "120" ; sleep 3 ; ./shot.sh B ../../evidence/C04-B-fps120.png
./agent_api.sh logs --since <seq>            # 断言 maxFps=30 / 120
# DPI：
./tap_text.sh B "匹配 DPI" ; sleep 4
"$ADB" -s $S shell wm size                  # 断言 Override 生效（MATCH 用 App 的 displayMetrics）
"$ADB" -s $S shell wm density
./shot.sh B ../../evidence/C05-B-match.png
./agent_api.sh logs        # 断言出现「显示模式 MATCH：wm size=... density=...（Override 生效）」
./tap_text.sh B "适配屏幕" ; sleep 3
"$ADB" -s $S shell wm size                  # 断言回到 1080x1920
./shot.sh B ../../evidence/C05-B-fit.png
```

- 判据：MATCH 后 `wm size` = App 上报的 1920x1080（App 横屏 displayMetrics），FIT 后恢复 1080x1920；两向日志成对出现；画面比例变化可见（截图对比 mean_abs > 阈值）。
- 注意：MATCH 时 B 被改 wm，App 自身也会重排；若 App 崩溃或 MATCH 失败回滚，记 FAIL 并 `./agent_api.sh display fit` 复原。

## 4. C06 悬浮球（显示/隐藏/拖动/扇形）

```bash
# 设置面板里打开“悬浮球”开关 → 右侧偏下 56dp 玻璃球
./tap_text.sh B "悬浮球"                    # 开关
./shot.sh B ../../evidence/C06-B-ball-show.png
# 拖动：从球心向下拖 200px，断言球位置变化（截图 diff）
./swipe.sh B 1830 700 1830 900 500
./shot.sh B ../../evidence/C06-B-ball-drag.png
# 单击：展开 6 项扇形（画质/适配/返回/主页/旋转/隐藏）
./tap.sh B 1830 900 ; sleep 1
./shot.sh B ../../evidence/C06-B-ball-fan.png
./ui_dump.sh B --nodes | grep -E "画质|适配|返回|主页|旋转|隐藏"
# 长按：缩进后隐藏 → 右下胶囊
./longpress.sh B 1830 900 900 ; sleep 2
./shot.sh B ../../evidence/C06-B-ball-hidden.png
./wait_for.sh B "悬浮球已隐藏" --timeout 5
./tap_text.sh B "悬浮球已隐藏"              # 恢复
```

- 判据：球可见且不与 HUD 重叠；拖动后位置变化 ≥50px；扇形 6 项文本齐全；长按 1s 内隐藏且出现恢复胶囊；点胶囊恢复。

## 5. C07-C09 输入转发（自环下用 B 自身 UI 验证）

```bash
# 串流中：点远端的“设置”图标（B 桌面坐标）→ 断言 B 前台变 com.android.settings
./shot.sh B ../../evidence/C07-B-before.png
./tap.sh B 530 900 ; sleep 2
./foreground.sh B --package                 # 断言 com.android.settings
./shot.sh B ../../evidence/C07-B-after.png
# 滑动：Settings 列表上滑 → 截图 diff > 2%
./swipe.sh B 960 800 960 300 400 ; sleep 1
./shot.sh B ../../evidence/C09-B-scroll.png
# 长按：桌面长按（需先回桌面）→ 弹出壁纸/小组件菜单
"$ADB" -s $S shell input keyevent KEYCODE_HOME ; sleep 1
./longpress.sh B 960 500 900 ; sleep 1
./shot.sh B ../../evidence/C08-B-longpress.png
```

- 判据：点击/滑动/长按在**被控端**产生预期变化（前台/滚动/菜单），且串流画面同步（B 上截图更新）；无坐标偏移（点 (x,y) 后目标元素确实被选中）。
- 自环注意：B 的 wm 可能被 MATCH 改过，坐标必须按 `wm size` 当前值换算；恢复 FIT 再测。

## 6. C13 断线重连（用 27380，不碰 27180）

```bash
./shot.sh B ../../evidence/C13-B-before.png
./net_sim.sh proxy kill --port 27380        # 杀自测代理
./wait_for.sh B "连接中断|正在重连|重连第" --timeout 10
./shot.sh B ../../evidence/C13-B-reconnecting.png
# 重启代理（后台，同 §0），等自动恢复
cd ../pc && "node.exe" agent.js --config ".../config.test.json" &   # run_in_background
cd ../tools/e2e
./wait_for.sh B "已重新连接|画质" --timeout 30
sleep 3
./shot.sh B ../../evidence/C13-B-recovered.png
```

- 判据：杀代理后 10s 内出现重连提示；代理重启后 30s 内恢复出画（HUD MBPS>0/画面变化）；重连次数提示与代理日志一致；不需人工重连。

## 7. 收尾

```bash
./agent_api.sh display fit                  # 复原 wm
./agent_api.sh quality 1080 60 4000000      # 复原画质
./net_sim.sh proxy kill --port 27380        # 停自测代理
./devices.sh                                # 记录收尾状态
```

最终写入 `evidence/E2E-REPORT.md`：包 sha256/mtime、逐条结果、证据文件名。
