package com.mumulink.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.glassBorder

/**
 * 图标药丸：HUD 右上、列表行尾、设置行都用它。
 * 无文字时是圆形，有文字时是药丸；选中态用极光蓝低饱和填充，而不是高亮色块。
 */
@Composable
fun IconPill(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    contentDescription: String? = null,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Primary,
    size: Dp = 40.dp,
) {
    val shape = if (label == null) CircleShape else PillShape
    val tint = when {
        danger -> Danger
        selected -> accent
        else -> OnInk
    }
    val fill = when {
        danger -> Danger.copy(alpha = 0.14f)
        selected -> accent.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.055f)
    }
    Box(
        modifier = modifier
            .height(size)
            .clip(shape)
            .background(fill)
            .border(1.dp, glassBorder(accent = if (danger) Danger else accent), shape)
            .tactileClickable(onClick = onClick, enabled = enabled, onClickLabel = contentDescription)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = if (label == null) 0.dp else 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(if (size <= 34.dp) 16.dp else 18.dp),
            )
            if (label != null) {
                Spacer(Modifier.width(7.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = tint)
            }
        }
    }
}

/**
 * 空态：柔光圆环 + 图标 + 标题 + 说明，可选一个操作。
 * 用在“没找到电脑”“连接失败”两处。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector,
    accent: Color = Primary,
    action: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f)),
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = OnInk)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
