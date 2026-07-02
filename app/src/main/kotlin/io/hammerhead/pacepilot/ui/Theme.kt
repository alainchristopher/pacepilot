package io.hammerhead.pacepilot.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Single source of truth for PacePilot Compose palette (matches hand-tuned MainActivity values). */
object PacePilotTheme {
    val BG = Color(0xFF050505)
    val CardBg = Color(0xFF111111)
    val CardBorder = Color(0xFF1A1A1A)
    val Primary = Color(0xFFFF6D00)
    val PrimaryDim = Color(0xFFCC5700)
    val PrimaryMuted = Color(0x29FF6D00)
    val PrimaryGhost = Color(0x14FF6D00)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFF9E9E9E)
    val TextTertiary = Color(0xFF6B6B6B)
    val Success = Color(0xFF00E676)
    val SuccessMuted = Color(0x1A00E676)
    val FieldBorder = Color(0xFF242424)
    val FieldBorderFocused = Color(0xFFFF6D00)
    val CardShape = RoundedCornerShape(14.dp)
    val PillShape = RoundedCornerShape(999.dp)
}
