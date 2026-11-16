package com.mumulink.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.glassBorder
import kotlin.math.roundToInt

/**
 * 横向分段落选择器：指示条随索引 spring 滑动，不是生硬的背景切换。
 * 无状态（selectedIndex + onSelect），方便复用和测试。
 */
@Composable
fun <T> SegmentBar(
    options: List<T>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    enabled: Boolean = true,
    accent: Color = Primary,
) {
    if (options.isEmpty()) return
    val count = options.size
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.045f))
            .border(1.dp, glassBorder(accent = accent), PillShape)
            .padding(3.dp)
            .alpha(if (enabled) 1f else 0.42f),
    ) {
        val cellWidth = maxWidth / count
        val density = LocalDensity.current
        val cellPx = with(density) { cellWidth.toPx() }
        val animated by animateFloatAsState(
            targetValue = selectedIndex.coerceIn(0, count - 1).toFloat(),
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
            label = "segmentIndicator",
        )
        Box(
            modifier = Modifier
                .offset { IntOffset((animated * cellPx).roundToInt(), 0) }
                .width(cellWidth)
                .fillMaxHeight()
                .clip(PillShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.16f)),
                    ),
                )
                .border(1.dp, accent.copy(alpha = 0.45f), PillShape),
        )
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, option ->
                val selected = index == selectedIndex.coerceIn(0, count - 1)
                val color by animateColorAsState(
                    targetValue = if (selected) OnInk else Muted,
                    label = "segmentText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .tactileClickable(
                            onClick = { onSelect(index) },
                            enabled = enabled && !selected,
                            onClickLabel = label(option),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
