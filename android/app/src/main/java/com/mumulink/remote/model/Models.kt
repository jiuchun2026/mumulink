package com.mumulink.remote.model

data class AgentEndpoint(
    val host: String,
    val httpPort: Int = 27180,
    val videoPort: Int = 27182,
    val controlPort: Int = 27183,
)

data class DeviceInfo(
    val model: String,
    val android: String,
    val width: Int,
    val height: Int,
    val density: Int,
)

data class QualitySpec(
    val maxSize: Int,
    val maxFps: Int,
    val bitRate: Int,
    val label: String = "",
)

data class SessionInfo(
    val running: Boolean,
    val quality: QualitySpec?,
    val deviceW: Int,
    val deviceH: Int,
)

data class AgentHello(
    val name: String,
    val host: String,
    val version: String,
    val paired: Boolean,
    val device: DeviceInfo?,
    val session: SessionInfo?,
)

enum class DisplayMode { FIT, MATCH }

data class DisplaySpec(
    val mode: DisplayMode = DisplayMode.FIT,
    val width: Int = 0,
    val height: Int = 0,
    val density: Int = 0,
)

data class StreamStats(
    val fps: Float = 0f,
    val mbps: Float = 0f,
    val latencyMs: Int = 0,
    val decodedFrames: Long = 0L,
    val droppedFrames: Int = 0,
    val deviceWidth: Int = 0,
    val deviceHeight: Int = 0,
    val decoderName: String = "",
)

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
    val display: DisplaySpec = DisplaySpec(),
    val ballVisible: Boolean = true,
    val stats: StreamStats = StreamStats(),
    val toast: String? = null,
    val autoQuality: Boolean = false,
    val pairedOnce: Boolean = false,
)

enum class RemoteKey { BACK, HOME, APP_SWITCH, POWER, VOLUME_UP, VOLUME_DOWN, ROTATE, MENU }

object QualityPresets {
    val SMOOTH = QualitySpec(maxSize = 720, maxFps = 30, bitRate = 1_500_000, label = "流畅")
    val BALANCED = QualitySpec(maxSize = 1080, maxFps = 60, bitRate = 4_000_000, label = "均衡")
    val HD = QualitySpec(maxSize = 1440, maxFps = 60, bitRate = 8_000_000, label = "高清")
    val ULTRA = QualitySpec(maxSize = 1920, maxFps = 60, bitRate = 12_000_000, label = "极致")
    val ALL = listOf(SMOOTH, BALANCED, HD, ULTRA)
}
