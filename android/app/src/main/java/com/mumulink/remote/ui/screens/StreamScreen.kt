package com.mumulink.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.LinkState
import com.mumulink.remote.model.StreamStats
import com.mumulink.remote.model.UiState
import com.mumulink.remote.ui.components.EdgeScrim
import com.mumulink.remote.ui.components.IconPill
import com.mumulink.remote.ui.components.StatChip
import com.mumulink.remote.ui.components.StatusDot
import com.mumulink.remote.ui.components.formatFps
import com.mumulink.remote.ui.components.formatMbps
import com.mumulink.remote.ui.components.formatMs
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.NumericSmall
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.Space
import com.mumulink.remote.ui.theme.Tertiary

/**
 * 串流页：全屏黑底 + 视频槽位 + 可自动淡出的 HUD。
 *
 * 完全无状态：HUD 是否可见、设置面板是否打开都由 AppRoot 决定；
 * RemoteVideoSurface 由 `video` 槽位注入，悬浮球由 `overlay` 槽位注入，
 * 这样本文件不依赖 vm，也能单独预览。
 */
@Composable
fun StreamScreen(
    ui: UiState,
    hudVisible: Boolean,
    switching: Boolean,
    onTapSurface: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    video: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 视频层：外层 Box 只做事件观察（Final pass，不消费），
        // 所以双指缩放、长按透传仍然落到 RemoteVideoSurface 上。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .observeTaps(enabled = !hudVisible, onTap = onTapSurface),
        ) {
            video()
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(tween(240)) + slideInVertically(tween(280)) { -it / 24 },
            exit = fadeOut(tween(420)) + slideOutVertically(tween(420)) { -it / 24 },
        ) {
            HudLayer(
                ui = ui,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
            )
        }

        // 切画质时的重连提示：不占满屏，只在中央留一个玻璃胶囊
        AnimatedVisibility(
            visible = switching,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Row(
                modifier = Modifier
                    .clip(PillShape)
                    .background(Color(0xE6171D2B))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), PillShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StatusDot(color = Secondary, size = 6.dp, pulse = true)
                Text(
                    text = "正在切换画质…",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnInk,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            overlay()
        }
    }
}

@Composable
private fun HudLayer(
    ui: UiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val stats: StreamStats = ui.stats
    Box(modifier = Modifier.fillMaxSize()) {
        EdgeScrim(
            modifier = Modifier.align(Alignment.TopCenter),
            top = true,
            height = 168.dp,
            strength = 0.86f,
        )
        EdgeScrim(
            modifier = Modifier.align(Alignment.BottomCenter),
            top = false,
            height = 120.dp,
            strength = 0.55f,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = Space.l)
                .padding(top = Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconPill(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    onClick = onBack,
                    contentDescription = "返回",
                    size = 38.dp,
                )
                Spacer(Modifier.width(Space.m))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ui.device?.model ?: "已连接",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnInk,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        StatusDot(
                            color = when (ui.link) {
                                is LinkState.Streaming -> Secondary
                                is LinkState.Retrying -> Tertiary
                                else -> Primary
                            },
                            size = 5.dp,
                            halo = false,
                        )
                    }
                    Text(
                        text = deviceLine(ui),
                        style = NumericSmall.copy(fontSize = 10.sp),
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconPill(
                    icon = Icons.Outlined.Tune,
                    onClick = onOpenSettings,
                    label = "画质",
                    contentDescription = "打开串流设置",
                    size = 38.dp,
                )
            }

            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatChip(
                    label = "FPS",
                    value = formatFps(stats.fps),
                    accent = OnInk,
                    compact = true,
                )
                StatChip(
                    label = "MS",
                    value = formatMs(stats.latencyMs),
                    accent = if (stats.latencyMs > 60) Tertiary else Secondary,
                    compact = true,
                )
                StatChip(
                    label = "MBPS",
                    value = formatMbps(stats.mbps),
                    accent = Primary,
                    compact = true,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(horizontal = Space.l)
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                text = "双指缩放 · 长按直接操作电脑",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.38f),
            )
            if (stats.droppedFrames > 0) {
                Text(
                    text = "丢帧 ${stats.droppedFrames} · 解码 ${stats.decoderName.ifBlank { "hardware" }}",
                    style = NumericSmall.copy(fontSize = 10.sp),
                    color = Color.White.copy(alpha = 0.28f),
                )
            }
        }

        if (ui.link is LinkState.Retrying) {
            val retry = ui.link as LinkState.Retrying
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 64.dp)
                    .clip(PillShape)
                    .background(Tertiary.copy(alpha = 0.16f))
                    .border(1.dp, Tertiary.copy(alpha = 0.35f), PillShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(color = Tertiary, size = 5.dp, pulse = true)
                Text(
                    text = "第 ${retry.attempt} 次重连 · ${retry.reason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnInk,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * HUD 设备行：优先显示设备“生效”尺寸。
 *
 * - MATCH 模式代理会改设备的 wm size/density，vm 在会话启动后会把 ui.device 刷新成生效值，
 *   这里在此基础上补一个 dpi；FIT 模式物理尺寸就是生效尺寸，只显示宽高。
 * - ui.device 缺失时退回视频流 meta 尺寸，避免空行。
 */
private fun deviceLine(ui: UiState): String {
    val device = ui.device
    val width = device?.width?.takeIf { it > 0 } ?: ui.stats.deviceWidth
    val height = device?.height?.takeIf { it > 0 } ?: ui.stats.deviceHeight
    val dpi = if (ui.display.mode == DisplayMode.MATCH) device?.density?.takeIf { it > 0 } else null
    val parts = buildList {
        device?.android?.takeIf { it.isNotBlank() }?.let { add("Android $it") }
        if (width > 0 && height > 0) add("$width×$height")
        if (dpi != null) add("${dpi}dpi")
    }
    return parts.joinToString(" · ").ifBlank { (ui.link as? LinkState.Connecting)?.step.orEmpty() }
}

private fun Modifier.observeTaps(enabled: Boolean, onTap: () -> Unit): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled) {
        awaitPointerEventScope {
            var downAt: Offset? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                when (event.type) {
                    PointerEventType.Press -> downAt = event.changes.firstOrNull()?.position
                    PointerEventType.Release -> {
                        val start = downAt
                        val end = event.changes.firstOrNull()?.position
                        if (start != null && end != null) {
                            val far = (end - start).getDistance() > viewConfiguration.touchSlop
                            if (!far) onTap()
                        }
                        downAt = null
                    }
                    else -> {}
                }
            }
        }
    }
}

