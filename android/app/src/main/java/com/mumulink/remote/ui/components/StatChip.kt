package com.mumulink.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mumulink.remote.ui.theme.Muted
import com.mumulink.remote.ui.theme.NumericBody
import com.mumulink.remote.ui.theme.OnInk
import com.mumulink.remote.ui.theme.PillShape
import com.mumulink.remote.ui.theme.Primary
import com.mumulink.remote.ui.theme.Space
import com.mumulink.remote.ui.theme.glassBorder

/**
 * 遥测胶囊：小号 mono 数字 + 极简标签。fps / ms / Mbps 三个并排放在 HUD 里。
 * 数字用 NumberTicker，避免 1Hz 刷新时的跳动。
 */
@Composable
fun StatChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = OnInk,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, glassBorder(accent = Primary), PillShape)
            .padding(horizontal = if (compact) Space.s else Space.m, vertical = if (compact) 4.dp else Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(text = label, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Muted)
        NumberTicker(text = value, style = NumericBody, color = accent)
        if (unit != null) {
            Text(
                text = unit,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
    }
}
