package com.mumulink.remote.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * 按压回弹：滑动/点击都带一点点物理感（0.97 → 1.0 spring），配合触感反馈，
 * 让玻璃控件“有厚度”。不使用水波纹，避免模板感。
 */
@Composable
fun rememberPressScale(
    source: MutableInteractionSource,
    pressedScale: Float = 0.97f,
): Float {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 900f),
        label = "pressScale",
    )
    return scale
}

/** 触感反馈档位：轻点用 TextHandleMove，确认/危险操作用 LongPress。 */
@Composable
fun Modifier.tactileClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    haptic: HapticFeedbackType = HapticFeedbackType.TextHandleMove,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val fallback = remember { MutableInteractionSource() }
    val source = interactionSource ?: fallback
    return this.clickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
    ) {
        if (enabled) haptics.performHapticFeedback(haptic)
        onClick()
    }
}

/**
 * 可点击的玻璃容器底座：统一按压缩放 + 触感 + 无涟漪。
 */
@Composable
fun TactileBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    haptic: HapticFeedbackType = HapticFeedbackType.TextHandleMove,
    onClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source, pressedScale)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .tactileClickable(
                onClick = onClick,
                enabled = enabled,
                haptic = haptic,
                onClickLabel = onClickLabel,
                interactionSource = source,
            ),
        content = content,
    )
}

/** 触感：轻点（切换/选择）。 */
@Composable
fun rememberTick(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    }
}

/** 触感：确认 / 危险操作。 */
@Composable
fun rememberThud(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) {
        { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}
