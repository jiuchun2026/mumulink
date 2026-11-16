package com.mumulink.remote.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * MuMuLink design tokens.
 *
 * Spacing follows a strict 4 / 8 / 12 / 16 / 24 rhythm, radii are 24–28dp for
 * containers and full pills for controls. Everything else (colour, type) lives
 * in Theme.kt so the palette stays in one place.
 */
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object Radius {
    /** 24dp — the default glass card. */
    val card = 24.dp

    /** 28dp — sheets and hero panels. */
    val sheet = 28.dp

    /** 20dp — nested panels. */
    val panel = 20.dp

    /** 16dp — input fields. */
    val field = 16.dp

    /** Full pill. */
    val pill = 999.dp
}

val CardShape = RoundedCornerShape(Radius.card)
val SheetShape = RoundedCornerShape(Radius.sheet)
val PanelShape = RoundedCornerShape(Radius.panel)
val FieldShape = RoundedCornerShape(Radius.field)
val PillShape = RoundedCornerShape(Radius.pill)

/** Vertical translucent glass fill (4–6% white over the ink background). */
fun glassFill(tint: Color = Color.White): Brush = Brush.verticalGradient(
    listOf(tint.copy(alpha = 0.072f), tint.copy(alpha = 0.026f)),
)

/** 1px border gradient: bright at the top-left, fading through the card. */
fun glassBorder(tint: Color = Color.White, accent: Color = Primary): Brush = Brush.linearGradient(
    listOf(
        tint.copy(alpha = 0.24f),
        tint.copy(alpha = 0.05f),
        accent.copy(alpha = 0.16f),
    ),
)

/** Aurora haze used behind brand headers and the signal orb. */
fun auroraGlow(color: Color, alpha: Float = 0.20f): Brush = Brush.radialGradient(
    listOf(color.copy(alpha = alpha), Color.Transparent),
)

/** Accent gradient for progress and the primary action. */
val AccentBrush: Brush = Brush.linearGradient(listOf(Primary, Secondary))

/** Ink background gradient, slightly lifted at the top so glass has something to sit on. */
val InkGradient: Brush = Brush.verticalGradient(
    listOf(Color(0xFF10151F), Ink, Color(0xFF080A10)),
)

/** Hairline divider that fades at both ends. */
val HairlineBrush: Brush = Brush.horizontalGradient(
    listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
)
