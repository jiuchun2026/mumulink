# Third-party notices

本项目（MuMuLink）使用了以下第三方作品。完整许可证原文见 `licenses/`。

## scrcpy (Apache-2.0)

- 项目：https://github.com/Genymobile/scrcpy
- 版权：Copyright (C) 2018 Genymobile；Copyright (C) 2018-2025 Romain Vimont
- 使用方式：**未修改地**分发官方发布的设备端服务 `scrcpy-server` 5.0.1（`tools/scrcpy-server.jar`，
  上游资产名 `scrcpy-server-v5.0.1`）；客户端按本项目独立实现，遵循其公开的线协议。
- 许可证：`licenses/LICENSE-APACHE-2.0.txt`；归属声明：`licenses/scrcpy-NOTICE.txt`

## ScrcpyForAndroid (Apache-2.0) — 设计参考，无代码引入

- 项目：https://github.com/Miuzarte/ScrcpyForAndroid
- 说明：仅参考其 Android MediaCodec 解码与会话状态机的工程思路，未复制源文件。

## ws-scrcpy (MIT) — 早期可行性验证，未用于最终实现

- 项目：https://github.com/NetrisTV/ws-scrcpy
- 许可证：`licenses/LICENSE-MIT-ws-scrcpy.txt`

## Android / Jetpack / Kotlin / OkHttp

AndroidX、Jetpack Compose、Kotlin 标准库与协程、OkHttp 均为 Apache-2.0，
作为依赖通过 Gradle 引入，未修改其源码。

## 商标声明

MuMu 是网易公司的产品与商标。本项目通过其公开的 adb 接口与命令行管理工具与其交互，
与网易无隶属或背书关系。仓库不包含、也不再分发 MuMu 安装包或其任何文件。
