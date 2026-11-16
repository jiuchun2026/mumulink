package com.mumulink.remote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.Success

/**
 * 状态点：可带呼吸光晕。用于“已连接 / 扫描中 / 需配对”。
 * 动画量在 draw 里读取，只触发重绘不触发重组。
 */
@Composable
fun StatusDot(
    modifier: Modifier = Modifier,
    color: Color = Success,
    size: Dp = 6.dp,
    pulse: Boolean = false,
    halo: Boolean = true,
) {
    val transition = rememberInfiniteTransition(label = "statusDot")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "statusPhase",
    )
    val dotPx = size
    Canvas(modifier = modifier.size(size * 2.4f)) {
        val r = dotPx.toPx() / 2f
        val t = if (pulse) phase else 1f
        if (halo) {
            drawCircle(
                color = color.copy(alpha = 0.30f * (1f - t) + 0.06f),
                radius = r * (1f + 1.35f * t),
                center = center,
            )
        }
        drawCircle(color = color.copy(alpha = 0.9f), radius = r, center = center)
    }
}

/** 静止小圆点（不呼吸），用于列表/图例。 */
@Composable
fun StaticDot(
    modifier: Modifier = Modifier,
    color: Color = Muted,
    size: Dp = 5.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        drawCircle(color = color, radius = this.size.minDimension / 2f, center = center)
    }
}
