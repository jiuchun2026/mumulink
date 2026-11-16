package com.mumulink.remote.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Secondary

/**
 * 轻量开关：不用 M3 Switch（默认样式和暗色玻璃不搭），
 * 轨道与圆点都带 spring，按压缩放交给 Tactile。
 */
@Composable
fun MiniToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = Secondary,
) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "miniToggle",
    )
    val track by animateColorAsState(
        targetValue = if (checked) accent.copy(alpha = 0.34f) else Color.White.copy(alpha = 0.07f),
        label = "toggleTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = (18.dp * progress),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "toggleKnob",
    )
    Box(
        modifier = modifier
            .size(44.dp, 26.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(PillShape)
            .background(track)
            .border(1.dp, if (checked) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f), PillShape)
            .tactileClickable(onClick = { onChange(!checked) }, enabled = enabled)
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset)
                .fillMaxHeight()
                .size(20.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Primary.copy(alpha = 0.75f)),
        )
    }
}
