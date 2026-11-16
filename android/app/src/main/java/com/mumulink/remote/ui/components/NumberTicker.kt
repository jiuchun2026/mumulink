package com.mumulink.remote.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 数字滚动：值变化时旧数字下滑淡出、新数字从上方落入。
 * 只用于 1Hz 级别的遥测（fps / ms / Mbps），不会造成抖动。
 */
@Composable
fun NumberTicker(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(animationSpec = tween(190, delayMillis = 10)) { h -> h / 3 } + fadeIn(tween(190)))
                .togetherWith(slideOutVertically(animationSpec = tween(150)) { h -> -h / 3 } + fadeOut(tween(110)))
        },
        label = "numberTicker",
    ) { value ->
        Text(text = value, style = style, color = color)
    }
}
