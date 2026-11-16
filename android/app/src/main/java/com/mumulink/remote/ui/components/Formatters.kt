package com.mumulink.remote.ui.components

import com.mumulink.remote.model.QualityPresets
import com.mumulink.remote.model.QualitySpec
import java.util.Locale
import kotlin.math.roundToInt

/** fps 整数显示，一位小数只在 120fps 档位时有意义，这里统一取整。 */
fun formatFps(fps: Float): String = if (fps <= 0f) "—" else fps.roundToInt().toString()

/** 延迟：>999ms 显示 1 位小数，否则整数毫秒。 */
fun formatMs(ms: Int): String = if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000f) else "$ms"

/** 码率：一位小数。 */
fun formatMbps(mbps: Float): String = if (mbps <= 0f) "—" else String.format(Locale.US, "%.1f", mbps)

/** 分辨率上限简写：maxSize 是最长边，加上 “≤” 避免和像素口径混淆。 */
fun formatResolution(maxSize: Int): String = "≤$maxSize"

/** 命中预设用预设标签，否则组合出 "1080P · 90fps"。 */
fun qualityLabel(q: QualitySpec): String {
    val preset = QualityPresets.ALL.firstOrNull { it == q }
    return preset?.label?.takeIf { it.isNotBlank() }
        ?: "${formatResolution(q.maxSize)} · ${q.maxFps}fps"
}

/** 码率（bps → Mbps 文案）。 */
fun formatBitrate(bitRate: Int): String =
    String.format(Locale.US, "%.1f Mbps", bitRate / 1_000_000f)

/** 主机名/IP 截断，避免列表里挤爆排布。 */
fun shortHost(host: String): String = if (host.length <= 22) host else host.take(21) + "…"
