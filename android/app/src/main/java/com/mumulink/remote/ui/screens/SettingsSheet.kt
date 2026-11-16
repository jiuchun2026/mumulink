package com.mumulink.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.QualityPresets
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.RemoteKey
import com.mumulink.remote.model.UiState
import com.mumulink.remote.ui.components.GlassDivider
import com.mumulink.remote.ui.components.IconPill
import com.mumulink.remote.ui.components.MiniToggle
import com.mumulink.remote.ui.components.SegmentBar
import com.mumulink.remote.ui.components.formatResolution
import com.mumulink.remote.ui.components.qualityLabel
import com.mumulink.remote.ui.components.rememberThud
import com.mumulink.remote.ui.components.tactileClickable
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.NumericSmall
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.SheetShape
import com.mumulink.remote.ui.theme.Space
import com.mumulink.remote.ui.theme.Surface1

private val FpsOptions = listOf(30, 45, 60, 90, 120)
private val SizeOptions = listOf(720, 1080, 1440, 1920)
private val BitrateOptions = listOf(2, 4, 6, 8, 12)

/**
 * 串流设置面板：画质 / 帧率 / 分辨率 / 码率 / 显示适配 / 悬浮球 / 按键 / 断开。
 * 每次改动立刻回调（vm.setQuality 内部重连），所以面板不维护本地草稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    ui: UiState,
    onDismiss: () -> Unit,
    onQuality: (QualitySpec) -> Unit,
    onAutoQuality: (Boolean) -> Unit,
    onFps: (Int) -> Unit,
    onMaxSize: (Int) -> Unit,
    onBitrate: (Int) -> Unit,
    onDisplayMode: (DisplayMode) -> Unit,
    onKey: (RemoteKey) -> Unit,
    onToggleBall: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val thud = rememberThud()
    val quality = ui.quality
    val auto = ui.autoQuality

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = Surface1,
        contentColor = OnInk,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = Space.m, bottom = Space.s)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(PillShape)
                    .background(Color.White.copy(alpha = 0.18f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Space.xl)
                .padding(bottom = Space.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "串流设置", style = MaterialTheme.typography.headlineSmall, color = OnInk)
                    Text(
                        text = if (ui.link is com.mumulink.remote.model.LinkState.Streaming) {
                            "改动即时生效，画质切换会短暂重连"
                        } else {
                            "等待串流恢复"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                IconPill(
                    icon = Icons.Outlined.Close,
                    onClick = onDismiss,
                    contentDescription = "关闭设置",
                    size = 36.dp,
                )
            }

            Spacer(Modifier.height(Space.l))
            SectionLabel("画质", trailing = if (auto) "自适应中" else qualityLabel(quality))
            SegmentBar(
                options = QualityPresets.ALL,
                selectedIndex = QualityPresets.ALL.indexOfFirst { it.maxSize == quality.maxSize }
                    .coerceAtLeast(0),
                onSelect = { index -> onQuality(QualityPresets.ALL[index]) },
                label = { it.label.ifBlank { qualityLabel(it) } },
                enabled = !auto,
                accent = Primary,
            )
            Spacer(Modifier.height(Space.s))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "自适应", style = MaterialTheme.typography.labelLarge, color = OnInk)
                    Text(
                        text = "按实时网络与解码压力自动调节，手动档位将被锁定",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Spacer(Modifier.width(Space.m))
                MiniToggle(checked = auto, onChange = onAutoQuality, accent = Secondary)
            }

            Spacer(Modifier.height(Space.l))
            SectionLabel("帧率", trailing = "${quality.maxFps}fps")
            SegmentBar(
                options = FpsOptions,
                selectedIndex = FpsOptions.indexOfFirst { it == quality.maxFps }.coerceAtLeast(0),
                onSelect = { index -> onFps(FpsOptions[index]) },
                label = { "${it}" },
                enabled = !auto,
                accent = Secondary,
            )

            Spacer(Modifier.height(Space.l))
            SectionLabel("分辨率上限", trailing = formatResolution(quality.maxSize))
            SegmentBar(
                options = SizeOptions,
                selectedIndex = SizeOptions.indexOfFirst { it == quality.maxSize }.coerceAtLeast(0),
                onSelect = { index -> onMaxSize(SizeOptions[index]) },
                label = { formatResolution(it) },
                enabled = !auto,
                accent = Secondary,
            )

            Spacer(Modifier.height(Space.l))
            SectionLabel("码率", trailing = "${quality.bitRate / 1_000_000} Mbps")
            SegmentBar(
                options = BitrateOptions,
                selectedIndex = BitrateOptions.indexOfFirst { it == quality.bitRate / 1_000_000 }
                    .coerceAtLeast(0),
                onSelect = { index -> onBitrate(BitrateOptions[index] * 1_000_000) },
                label = { "${it}M" },
                enabled = !auto,
                accent = Secondary,
            )

            Spacer(Modifier.height(Space.l))
            SectionLabel("显示适配", trailing = if (ui.display.mode == DisplayMode.MATCH) "MATCH" else "FIT")
            SegmentBar(
                options = listOf(DisplayMode.FIT, DisplayMode.MATCH),
                selectedIndex = if (ui.display.mode == DisplayMode.FIT) 0 else 1,
                onSelect = { index -> onDisplayMode(if (index == 0) DisplayMode.FIT else DisplayMode.MATCH) },
                label = { if (it == DisplayMode.FIT) "适配屏幕" else "匹配 DPI" },
                accent = Primary,
            )
            Spacer(Modifier.height(Space.s))
            Text(
                text = if (ui.display.mode == DisplayMode.FIT) {
                    "等比缩放铺满屏幕，画面边缘可能留黑，触控坐标由服务端换算"
                } else {
                    "按电脑分辨率 1:1 渲染，画面更锐利，但触控落点需要校准"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )

            Spacer(Modifier.height(Space.l))
            GlassDivider()
            Spacer(Modifier.height(Space.m))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "悬浮球", style = MaterialTheme.typography.labelLarge, color = OnInk)
                    Text(
                        text = "串流时显示 fps，长按可收起",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                MiniToggle(checked = ui.ballVisible, onChange = onToggleBall)
            }

            Spacer(Modifier.height(Space.l))
            SectionLabel("按键", trailing = null)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                KeyButton("返回", Icons.AutoMirrored.Outlined.ArrowBack) { onKey(RemoteKey.BACK) }
                KeyButton("主页", Icons.Outlined.Home) { onKey(RemoteKey.HOME) }
                KeyButton("最近", Icons.Outlined.CropSquare) { onKey(RemoteKey.APP_SWITCH) }
                KeyButton("旋转", Icons.Outlined.ScreenRotation) { onKey(RemoteKey.ROTATE) }
            }
            Spacer(Modifier.height(Space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                KeyButton("音量-", Icons.AutoMirrored.Outlined.VolumeDown) { onKey(RemoteKey.VOLUME_DOWN) }
                KeyButton("音量+", Icons.AutoMirrored.Outlined.VolumeUp) { onKey(RemoteKey.VOLUME_UP) }
                KeyButton("电源", Icons.Outlined.PowerSettingsNew) { onKey(RemoteKey.POWER) }
            }

            Spacer(Modifier.height(Space.xl))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(PillShape)
                    .background(Danger.copy(alpha = 0.12f))
                    .tactileClickable(
                        onClick = {
                            thud()
                            onDisconnect()
                        },
                        onClickLabel = "断开连接",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "断开连接",
                    style = MaterialTheme.typography.labelLarge,
                    color = Danger,
                )
            }

            Spacer(Modifier.height(Space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (ui.link is com.mumulink.remote.model.LinkState.Streaming) Secondary else Muted),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = buildString {
                        append(ui.device?.let { "${it.model} · Android ${it.android}" } ?: "未连接设备")
                        if (ui.stats.latencyMs > 0) append(" · ${ui.stats.latencyMs}ms")
                        if (ui.stats.decoderName.isNotBlank()) append(" · ${ui.stats.decoderName}")
                    },
                    style = NumericSmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(text = trailing, style = NumericSmall, color = Primary)
        }
    }
}

@Composable
private fun KeyButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.05f))
            .tactileClickable(onClick = onClick, onClickLabel = label)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = OnInk.copy(alpha = 0.85f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = OnInk.copy(alpha = 0.9f))
        }
    }
}
