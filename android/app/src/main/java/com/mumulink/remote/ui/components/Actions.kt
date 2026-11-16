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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Danger
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary

/** 按钮气质：主操作用极光蓝渐变，次操作用玻璃描边，危险操作只用珊瑚红描边。 */
enum class ActionTone { Primary, Ghost, Danger }

@Composable
fun ActionPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: ActionTone = ActionTone.Primary,
    icon: ImageVector? = null,
) {
    val bg: Brush = when (tone) {
        ActionTone.Primary -> if (enabled) {
            Brush.horizontalGradient(listOf(Primary, Primary.copy(alpha = 0.82f)))
        } else {
            Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.06f)))
        }
        ActionTone.Ghost -> Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.04f)))
        ActionTone.Danger -> Brush.horizontalGradient(listOf(Danger.copy(alpha = 0.16f), Danger.copy(alpha = 0.10f)))
    }
    val border = when (tone) {
        ActionTone.Primary -> null
        ActionTone.Ghost -> Color.White.copy(alpha = 0.10f)
        ActionTone.Danger -> Danger.copy(alpha = 0.42f)
    }
    val content = when (tone) {
        ActionTone.Primary -> if (enabled) Color(0xFF0A1020) else Muted
        ActionTone.Ghost -> OnInk.copy(alpha = 0.9f)
        ActionTone.Danger -> Danger
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(PillShape)
            .background(bg)
            .then(if (border != null) Modifier.border(1.dp, border, PillShape) else Modifier)
            .tactileClickable(onClick = onClick, enabled = enabled, onClickLabel = text)
            .padding(horizontal = if (tone == ActionTone.Primary) 0.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
