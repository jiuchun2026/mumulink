package com.mumulink.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Ink
import com.mumulink.remote.ui.theme.InkGradient
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Radius
import com.mumulink.remote.ui.theme.Secondary
import com.mumulink.remote.ui.theme.Tertiary
import com.mumulink.remote.ui.theme.glassBorder
import com.mumulink.remote.ui.theme.glassFill

/**
 * 玻璃拟态卡片：半透明白叠加 + 1px 渐变描边 + 可选柔和外发光。
 * 外发光用 drawBehind 画在裁剪之前，所以能溢出卡片边界。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = Radius.card,
    glow: Color? = null,
    tint: Color = Color.White,
    accent: Color = Primary,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val body: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
    val base = modifier
        .fillMaxWidth()
        .then(
            if (glow == null) {
                Modifier
            } else {
                Modifier.drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = 0.22f), Color.Transparent),
                            center = center,
                            radius = size.maxDimension * 0.72f,
                        ),
                        radius = size.maxDimension * 0.72f,
                        center = center,
                    )
                }
            },
        )

    if (onClick != null) {
        TactileBox(
            onClick = onClick,
            onClickLabel = onClickLabel,
            pressedScale = 0.985f,
            modifier = base.clip(shape).background(glassFill(tint), shape).border(1.dp, glassBorder(tint, accent), shape),
        ) {
            Column(content = body)
        }
    } else {
        Box(
            modifier = base.clip(shape).background(glassFill(tint), shape).border(1.dp, glassBorder(tint, accent), shape),
        ) {
            Column(content = body)
        }
    }
}

/** 极细分割线，两端淡出。 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
                ),
            ),
    )
}

/**
 * 深空背景：Ink 底色 + 三团极光雾 + 2.5% 点阵 + 边缘压暗。
 * 所有绘制在 Canvas 里完成，滚动列表不会触发它的重组。
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(InkGradient)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 三团极光雾：蓝（左上）/ 薄荷（右下）/ 珊瑚（中上偏右）
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Primary.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(w * 0.10f, h * -0.02f),
                    radius = w * 1.05f,
                ),
                radius = w * 1.05f,
                center = Offset(w * 0.10f, h * -0.02f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Secondary.copy(alpha = 0.11f), Color.Transparent),
                    center = Offset(w * 0.92f, h * 0.86f),
                    radius = w * 0.95f,
                ),
                radius = w * 0.95f,
                center = Offset(w * 0.92f, h * 0.86f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Tertiary.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(w * 0.78f, h * 0.18f),
                    radius = w * 0.55f,
                ),
                radius = w * 0.55f,
                center = Offset(w * 0.78f, h * 0.18f),
            )

            // 点阵：极低对比，只提供“仪器面板”的质感
            val step = 26.dp.toPx()
            val dot = 0.7.dp.toPx()
            val dotColor = Color.White.copy(alpha = 0.030f)
            var y = step * 0.5f
            while (y < h) {
                var x = step * 0.5f
                while (x < w) {
                    drawCircle(color = dotColor, radius = dot, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        content()
    }
}

/** 顶部/底部压暗渐变，让 HUD 文字在任意画面上可读。 */
@Composable
fun EdgeScrim(
    modifier: Modifier = Modifier,
    top: Boolean = true,
    height: Dp = 180.dp,
    strength: Float = 0.75f,
) {
    val colors = listOf(Color.Black.copy(alpha = strength), Color.Transparent)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(
                if (top) Brush.verticalGradient(colors) else Brush.verticalGradient(colors.reversed()),
            ),
    )
}
