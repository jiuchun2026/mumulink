package com.mumulink.remote.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mumulink.remote.model.DisplayMode
import com.mumulink.remote.model.QualityPresets
import com.mumulink.remote.model.QualitySpec
import com.mumulink.remote.model.RemoteKey
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.NumericSmall
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.Tertiary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** 超过这个横向速度（px/s）就按“甩出去的方向”吸附，而不是就近吸附。 */
private const val FLING_THRESHOLD = 900f

/**
 * 串流悬浮球（保持无状态：所有远程操作通过回调交给 AppRoot）。
 *
 * - 拖动跟手，松手按速度吸附到最近的左右边缘；越界有阻尼。
 * - 单击展开扇形菜单（画质 / 适配 / 返回 / 主页 / 旋转 / 隐藏）。
 * - 菜单外区域压暗并穿透（点击即收起），球体本身只占 56dp，不挡视频手势。
 * - 长按缩进并隐藏（AppRoot 调 vm.hideBall()）。
 */
@Composable
fun FloatingBallOverlay(
    visible: Boolean,
    connected: Boolean,
    fps: Float,
    latencyMs: Int,
    quality: QualitySpec,
    autoQuality: Boolean,
    displayMode: DisplayMode,
    onSelectQuality: (QualitySpec) -> Unit,
    onAutoQuality: (Boolean) -> Unit,
    onDisplayMode: (DisplayMode) -> Unit,
    onKey: (RemoteKey) -> Unit,
    onHide: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val tracker = remember { VelocityTracker() }
    val ballDp = 56.dp
    val ballPx = with(density) { ballDp.toPx() }
    val marginPx = with(density) { 10.dp.toPx() }
    val minYPx = with(density) { 24.dp.toPx() }
    val fanRadiusPx = with(density) { 106.dp.toPx() }
    val fanItemPx = with(density) { 50.dp.toPx() }
    val panelWidthPx = with(density) { 244.dp.toPx() }
    val panelGapPx = with(density) { 10.dp.toPx() }
    val panelEstimatePx = with(density) { 344.dp.toPx() }

    var expanded by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }
    var vanishing by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }

        fun clampX(x: Float) = x.coerceIn(-ballPx * 0.22f, wPx - ballPx * 0.78f)
        fun clampY(y: Float) = y.coerceIn(minYPx, (hPx - ballPx - marginPx * 2f).coerceAtLeast(minYPx))

        var posX by remember { mutableFloatStateOf(wPx - ballPx - marginPx) }
        var posY by remember { mutableFloatStateOf(hPx * 0.56f) }

        val onRightHalf = posX + ballPx / 2f >= wPx / 2f
        val centerX = posX + ballPx / 2f
        val centerY = posY + ballPx / 2f

        val open = remember { Animatable(0f) }
        LaunchedEffect(expanded) {
            open.animateTo(
                targetValue = if (expanded) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.74f, stiffness = 300f),
            )
        }

        val scrim by animateFloatAsState(
            targetValue = if (expanded || panelOpen) 1f else 0f,
            animationSpec = tween(180),
            label = "ballScrim",
        )
        val vanishScale by animateFloatAsState(
            targetValue = if (vanishing) 0.35f else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 420f),
            label = "vanishScale",
        )
        val vanishAlpha by animateFloatAsState(
            targetValue = if (vanishing) 0f else 1f,
            animationSpec = tween(180),
            label = "vanishAlpha",
        )
        val ballScale by animateFloatAsState(
            targetValue = if (dragging) 1.07f else 1f,
            animationSpec = spring(dampingRatio = 0.55f, stiffness = 520f),
            label = "ballScale",
        )

        LaunchedEffect(vanishing) {
            if (vanishing) {
                delay(210)
                onHide()
            }
        }

        // 折叠菜单时让球体回到屏内，防止旋转后被裁掉
        LaunchedEffect(wPx, hPx) {
            posX = clampX(posX).let { if (it < 0f) marginPx else it }
            posY = clampY(posY)
        }

        val collapse: () -> Unit = {
            expanded = false
            panelOpen = false
        }

        // 1) 压暗层：仅展开时存在，点击任意空白收起
        if (scrim > 0.01f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * scrim))
                    .pointerInput(expanded, panelOpen) {
                        detectTapGestures { collapse() }
                    },
            )
        }

        // 2) 扇形菜单
        if (expanded || open.value > 0.01f) {
            val fanItems = buildFanItems(
                displayMode = displayMode,
                onQuality = {
                    // 扇形立刻收起，避免 6 个气泡和画质面板叠在一起
                    expanded = false
                    panelOpen = true
                },
                onDisplay = {
                    onDisplayMode(if (displayMode == DisplayMode.FIT) DisplayMode.MATCH else DisplayMode.FIT)
                    collapse()
                },
                onBack = {
                    onKey(RemoteKey.BACK)
                    collapse()
                },
                onHome = {
                    onKey(RemoteKey.HOME)
                    collapse()
                },
                onRotate = {
                    onKey(RemoteKey.ROTATE)
                    collapse()
                },
                onHideBall = {
                    expanded = false
                    panelOpen = false
                    vanishing = true
                },
                onDisconnect = {
                    expanded = false
                    panelOpen = false
                    onDisconnect()
                },
            )
            val baseAngle = if (onRightHalf) 180.0 else 0.0
            val step = 28.0
            val start = baseAngle - step * (fanItems.size - 1) / 2.0
            fanItems.forEachIndexed { index, item ->
                val t = ((open.value - index * 0.045f) / 0.78f).coerceIn(0f, 1f)
                if (t <= 0.01f) return@forEachIndexed
                val angle = Math.toRadians(start + step * index)
                val dist = fanRadiusPx * t
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (centerX + cos(angle).toFloat() * dist - fanItemPx / 2f).roundToInt(),
                                (centerY + sin(angle).toFloat() * dist - fanItemPx / 2f).roundToInt(),
                            )
                        }
                        .size(50.dp)
                        .graphicsLayer {
                            scaleX = 0.72f + 0.28f * t
                            scaleY = 0.72f + 0.28f * t
                            alpha = t
                        },
                ) {
                    FanButton(item = item)
                }
            }
        }

        // 3) 悬浮球本体
        Box(
            modifier = Modifier
                .offset { IntOffset(posX.roundToInt(), posY.roundToInt()) }
                .size(ballDp)
                .graphicsLayer {
                    scaleX = vanishScale * ballScale
                    scaleY = vanishScale * ballScale
                    alpha = vanishAlpha
                }
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Primary.copy(alpha = if (connected) 0.30f else 0.20f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.minDimension * 0.95f,
                        ),
                        radius = size.minDimension * 0.95f,
                        center = center,
                    )
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.19f), Color.White.copy(alpha = 0.045f)),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.38f), Primary.copy(alpha = 0.26f)),
                    ),
                    shape = CircleShape,
                )
                .pointerInput(wPx, hPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            expanded = false
                            panelOpen = false
                            tracker.resetTracking()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            posX = clampX(posX + amount.x)
                            posY = clampY(posY + amount.y)
                        },
                        onDragEnd = {
                            dragging = false
                            val velocity = tracker.calculateVelocity()
                            val leftEdge = marginPx
                            val rightEdge = wPx - ballPx - marginPx
                            // 甩出去的方向优先：丢向哪边就吸到哪边，否则就近吸附
                            val targetX = when {
                                velocity.x > FLING_THRESHOLD && posX < rightEdge -> rightEdge
                                velocity.x < -FLING_THRESHOLD && posX > leftEdge -> leftEdge
                                posX + ballPx / 2f < wPx / 2f -> leftEdge
                                else -> rightEdge
                            }
                            scope.launch {
                                launch {
                                    animate(
                                        initialValue = posX,
                                        targetValue = targetX,
                                        initialVelocity = velocity.x.coerceIn(-6000f, 6000f),
                                        animationSpec = spring(dampingRatio = 0.58f, stiffness = 330f),
                                    ) { value, _ -> posX = value }
                                }
                                launch {
                                    animate(
                                        initialValue = posY,
                                        targetValue = clampY(posY),
                                        initialVelocity = velocity.y.coerceIn(-4000f, 4000f),
                                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
                                    ) { value, _ -> posY = value }
                                }
                            }
                        },
                        onDragCancel = { dragging = false },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            when {
                                panelOpen -> panelOpen = false
                                expanded -> expanded = false
                                else -> expanded = true
                            }
                        },
                        onLongPress = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            expanded = false
                            panelOpen = false
                            vanishing = true
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (connected) {
                    NumberTicker(
                        text = formatFps(fps),
                        style = NumericSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = OnInk,
                    )
                    Text(
                        text = if (latencyMs > 0) "${latencyMs}ms" else "fps",
                        style = NumericSmall.copy(fontSize = 8.sp),
                        color = Muted,
                    )
                } else {
                    StatusDot(color = Primary, size = 7.dp, pulse = true)
                }
            }
        }

        // 4) 画质面板（贴边，夹紧在屏内）
        if (panelOpen) {
            val edgePx = with(density) { 8.dp.toPx() }
            val minX = edgePx
            val maxX = (wPx - panelWidthPx - edgePx).coerceAtLeast(minX)
            val panelX = if (onRightHalf) posX - panelWidthPx - panelGapPx else posX + ballPx + panelGapPx
            val panelY = (centerY - panelEstimatePx / 2f).coerceIn(
                with(density) { 12.dp.toPx() },
                (hPx - panelEstimatePx - with(density) { 12.dp.toPx() })
                    .coerceAtLeast(with(density) { 12.dp.toPx() }),
            )
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(panelX.coerceIn(minX, maxX).roundToInt(), panelY.roundToInt())
                    }
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                QualityPanel(
                    width = 244.dp,
                    quality = quality,
                    autoQuality = autoQuality,
                    displayMode = displayMode,
                    onSelectQuality = {
                        onSelectQuality(it)
                        panelOpen = false
                    },
                    onAutoQuality = onAutoQuality,
                    onDisplayMode = onDisplayMode,
                )
            }
        }
    }
}

private class FanItem(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val action: () -> Unit,
)

private fun buildFanItems(
    displayMode: DisplayMode,
    onQuality: () -> Unit,
    onDisplay: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRotate: () -> Unit,
    onHideBall: () -> Unit,
    onDisconnect: () -> Unit,
): List<FanItem> = listOf(
    FanItem("画质", Icons.Outlined.Tune, Primary, onQuality),
    FanItem(if (displayMode == DisplayMode.FIT) "适配" else "DPI", Icons.Outlined.AspectRatio, Secondary, onDisplay),
    FanItem("返回", Icons.AutoMirrored.Outlined.ArrowBack, OnInk, onBack),
    FanItem("主页", Icons.Outlined.Home, OnInk, onHome),
    FanItem("旋转", Icons.Outlined.ScreenRotation, OnInk, onRotate),
    FanItem("断开", Icons.Outlined.LinkOff, Danger, onDisconnect),
    FanItem("隐藏", Icons.Outlined.VisibilityOff, Tertiary, onHideBall),
)

@Composable
private fun FanButton(item: FanItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .background(Color(0xF2141926))
            .border(
                1.dp,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.20f), item.tint.copy(alpha = 0.26f))),
                CircleShape,
            )
            .tactileClickable(onClick = item.action),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = item.tint,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text(text = item.label, style = NumericSmall.copy(fontSize = 9.sp), color = Muted, maxLines = 1)
        }
    }
}

/** 贴边画质面板：4 档预设 + 自适应 + DPI 适配。 */
@Composable
private fun QualityPanel(
    width: androidx.compose.ui.unit.Dp,
    quality: QualitySpec,
    autoQuality: Boolean,
    displayMode: DisplayMode,
    onSelectQuality: (QualitySpec) -> Unit,
    onAutoQuality: (Boolean) -> Unit,
    onDisplayMode: (DisplayMode) -> Unit,
) {
    GlassCard(
        modifier = Modifier.width(width),
        corner = 22.dp,
        glow = Primary,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "画质", style = MaterialTheme.typography.titleMedium, color = OnInk)
            Spacer(Modifier.weight(1f))
            Text(text = "切换会短暂重连", style = NumericSmall.copy(fontSize = 9.sp), color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        QualityPresets.ALL.forEach { preset ->
            val selected = preset == quality
            val enabled = !autoQuality
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Primary.copy(alpha = 0.12f) else Color.Transparent)
                    .tactileClickable(
                        onClick = { onSelectQuality(preset) },
                        enabled = enabled,
                        onClickLabel = preset.label,
                    )
                    .alpha(if (enabled) 1f else 0.42f)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = preset.label.ifBlank { qualityLabel(preset) },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) Primary else OnInk,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatResolution(preset.maxSize)} · ${preset.bitRate / 1_000_000}M",
                    style = NumericSmall.copy(fontSize = 9.sp),
                    color = Muted,
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        GlassDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "自适应", style = MaterialTheme.typography.labelLarge, color = OnInk)
                Text(
                    text = if (autoQuality) "按网络调节分辨率与码率" else "固定档位，不自动降级",
                    style = NumericSmall.copy(fontSize = 9.sp),
                    color = Muted,
                )
            }
            MiniToggle(checked = autoQuality, onChange = onAutoQuality)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (displayMode == DisplayMode.MATCH) "匹配 DPI" else "适配屏幕",
                    style = MaterialTheme.typography.labelLarge,
                    color = OnInk,
                )
                Text(
                    text = if (displayMode == DisplayMode.MATCH) "按电脑分辨率渲染" else "等比铺满，触控偏移小",
                    style = NumericSmall.copy(fontSize = 9.sp),
                    color = Muted,
                )
            }
            MiniToggle(
                checked = displayMode == DisplayMode.MATCH,
                onChange = { onDisplayMode(if (displayMode == DisplayMode.FIT) DisplayMode.MATCH else DisplayMode.FIT) },
                accent = Secondary,
            )
        }
    }
}

/** 悬浮球隐藏后，从设置或 HUD 里重新唤出用的小提示卡片。 */
@Composable
fun BallHiddenHint(modifier: Modifier = Modifier, onShow: () -> Unit) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), PillShape)
            .tactileClickable(onClick = onShow, onClickLabel = "显示悬浮球")
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(color = Primary, size = 5.dp, halo = false)
        Text(text = "悬浮球已隐藏 · 点此恢复", style = NumericSmall.copy(fontSize = 10.sp), color = OnInk, textAlign = TextAlign.Center)
    }
}
