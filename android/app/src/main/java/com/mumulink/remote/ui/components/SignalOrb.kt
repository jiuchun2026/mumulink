package com.mumulink.remote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mumulink.remote.ui.theme.NumericHero
import com.mumulink.remote.ui.theme.NumericSmall
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.Tertiary

/** 信号球气质：扫描/重复配对偏蓝，已连接偏薄荷青。 */
enum class OrbTone { Idle, Searching, Connecting, Live, Alert }

data class OrbPalette(val core: Color, val ring: Color, val halo: Color)

private fun paletteOf(tone: OrbTone): OrbPalette = when (tone) {
    OrbTone.Idle -> OrbPalette(Color(0xFF3A4A6B), Primary.copy(alpha = 0.55f), Primary)
    OrbTone.Searching -> OrbPalette(Primary, Primary, Primary)
    OrbTone.Connecting -> OrbPalette(Primary, Secondary, Primary)
    OrbTone.Live -> OrbPalette(Secondary, Secondary, Secondary)
    OrbTone.Alert -> OrbPalette(Tertiary, Tertiary, Tertiary)
}

/**
 * 信号球：呼吸核心 + 旋转双环 + 可选环状进度。
 * 全部是 Canvas 自绘，不依赖图片资源，也不需要额外依赖。
 */
@Composable
fun SignalOrb(
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    tone: OrbTone = OrbTone.Searching,
    progress: Float? = null,
    centerText: String? = null,
    centerLabel: String? = null,
) {
    val palette = paletteOf(tone)
    val transition = rememberInfiniteTransition(label = "signalOrb")

    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "breath",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "spin",
    )
    val spinReverse by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(11000, easing = LinearEasing)),
        label = "spinReverse",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val c = Offset(w / 2f, h / 2f)
            val base = w / 2f

            // 呼吸：0→1→0 的柔和正弦
            val b = (kotlin.math.sin(breath * 2f * Math.PI).toFloat() + 1f) / 2f
            val pulse = 0.55f + 0.45f * b

            // 外层光晕
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(palette.halo.copy(alpha = 0.26f * pulse), Color.Transparent),
                    center = c,
                    radius = base,
                ),
                radius = base,
                center = c,
            )

            // 内核：三层同心渐变球，中心最亮
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.85f * pulse),
                        palette.core.copy(alpha = 0.75f * pulse),
                        palette.core.copy(alpha = 0.10f),
                    ),
                    center = Offset(c.x - base * 0.12f, c.y - base * 0.16f),
                    radius = base * 0.62f,
                ),
                radius = base * 0.50f,
                center = c,
            )

            // 呼吸环（随呼吸扩张并淡出）
            drawCircle(
                color = palette.ring.copy(alpha = 0.28f * (1f - b)),
                radius = base * (0.58f + 0.34f * b),
                center = c,
                style = Stroke(width = 1.4.dp.toPx()),
            )

            // 双环：一正一反，缺口让球看起来在自转
            rotate(spin, pivot = c) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, palette.ring.copy(alpha = 0.75f), Color.Transparent),
                        center = c,
                    ),
                    startAngle = 20f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = Offset(c.x - base * 0.80f, c.y - base * 0.80f),
                    size = Size(base * 1.60f, base * 1.60f),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            rotate(spinReverse, pivot = c) {
                drawArc(
                    color = palette.ring.copy(alpha = 0.14f),
                    startAngle = 150f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(c.x - base * 0.94f, c.y - base * 0.94f),
                    size = Size(base * 1.88f, base * 1.88f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
                )
                // 轨道小卫星
                drawCircle(
                    color = palette.ring.copy(alpha = 0.85f),
                    radius = 1.8.dp.toPx(),
                    center = Offset(c.x + base * 0.94f, c.y),
                )
            }

            // 环状进度（连接中）
            if (progress != null) {
                val p = progress.coerceIn(0f, 1f)
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(palette.ring.copy(alpha = 0.0f), palette.ring, Secondary),
                        center = c,
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * p,
                    useCenter = false,
                    topLeft = Offset(c.x - base * 0.68f, c.y - base * 0.68f),
                    size = Size(base * 1.36f, base * 1.36f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }

        if (centerText != null || centerLabel != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (centerText != null) {
                    Text(
                        text = centerText,
                        style = NumericHero.copy(fontSize = 34.sp, letterSpacing = (-1).sp),
                        color = Color.White,
                    )
                }
                if (centerLabel != null) {
                    Text(
                        text = centerLabel,
                        style = NumericSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}
