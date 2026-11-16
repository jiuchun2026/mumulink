# 开源致谢与许可证说明（ATTRIBUTION）

MuMuLink 的实现建立在以下开源项目之上。分发本项目的二进制（APK / 代理压缩包）时，请一并保留本文件与 `licenses/` 下的许可证原文。

---

## 1. Genymobile/scrcpy（核心协议与设备端服务）

- 项目：https://github.com/Genymobile/scrcpy
- 许可证：**Apache License 2.0**
- 版权：Copyright (C) 2018 Genymobile，Copyright (C) 2018-2025 Romain Vimont
- 本项目如何使用：
  1. **直接分发**官方发布的设备端 `scrcpy-server` 5.0.1 二进制（`tools/scrcpy-server.jar`，原文件名 `scrcpy-server-v5.0.1`），代理把它推送到 MuMu 内以 `app_process` 启动；
  2. 复用其公开的 **scrcpy wire protocol**：视频流（codec id / session meta / 帧头 / SPS-PPS）、控制通道消息（触摸、滚动、按键、文本、剪贴板、电源等）、`tunnel_forward` 的 abstract socket 约定；
  3. App 端按该协议独立实现解析与注入（Kotlin），未包含 scrcpy 的 C/Java 客户端代码。
- 依据 Apache-2.0 §4，分发时保留其 `LICENSE`、`NOTICE`（若上游提供）与本说明；未修改上游 jar 本体。

## 2. Miuzarte/ScrcpyForAndroid（Android 客户端参考实现）

- 项目：https://github.com/Miuzarte/ScrcpyForAndroid
- 许可证：**Apache License 2.0**
- 本项目如何使用：作为“在 Android 上做 scrcpy 客户端”的工程参考，包括 MediaCodec 异步解码的线程模型、Surface 渲染与宽高比处理、触摸到控制消息的映射、会话状态机与重连策略等**设计思路**；MuMuLink 的 Kotlin 代码为独立编写（面向本项目自己的中继代理，而非 ADB-Wireless 直连），不含其源码副本。
- 若后续合并其任何源文件，必须在文件头保留其版权声明并更新本节。

## 3. NetrisTV/ws-scrcpy（可行性验证参考，未用于最终实现）

- 项目：https://github.com/NetrisTV/ws-scrcpy
- 许可证：**MIT**
- 本项目如何使用：仅作为“浏览器/局域网串流 MuMu”的早期可行性对照实验（仓库外 `ws-scrcpy/` 目录），最终产品未使用其代码或其定制的 `scrcpy-server` 分支。

## 4. Android 平台与 Jetpack

| 组件 | 许可证 |
|---|---|
| AndroidX Core / Activity / Lifecycle | Apache-2.0 |
| Jetpack Compose（UI / Foundation / Material3 / Tooling） | Apache-2.0 |
| Kotlin Standard Library / kotlinx.coroutines | Apache-2.0 |
| OkHttp 4 | Apache-2.0 |

## 5. MuMu 模拟器

MuMu Player 为网易公司的专有软件，本项目仅通过其公开的 adb 端口与命令行管理工具（`MuMuManager.exe` / 新版 `mumu-cli.exe`）与之交互，未修改、未反编译、未再分发其任何文件。MuMuLink 与网易无隶属关系。

---

## 许可证文件位置

- `licenses/LICENSE-APACHE-2.0.txt` — Apache License 2.0 全文（scrcpy、ScrcpyForAndroid、AndroidX、Compose、OkHttp 适用）
- `licenses/LICENSE-MIT-ws-scrcpy.txt` — ws-scrcpy 的 MIT 许可证
- `licenses/scrcpy-NOTICE.txt` — scrcpy 版权与归属声明
- `LICENSE` — MuMuLink 自身代码的 MIT 许可证
