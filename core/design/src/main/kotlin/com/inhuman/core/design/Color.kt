package com.inhuman.core.design

import androidx.compose.ui.graphics.Color

/**
 * A deliberately small palette.
 *
 * A chat app is mostly text on a quiet background, so the accent is spent in
 * very few places: the primary action, a selected row, the send control, and a
 * live status dot. The neutrals are warm rather than blue-grey; blue-grey reads
 * as a stock developer tool, while a warm graphite/paper pair gives the same
 * restraint a little more character.
 */

// Dark scheme -- the working default. Graphite with a warm bone foreground.
val Ink900 = Color(0xFF0E0F0E)   // app background
val Ink850 = Color(0xFF151715)   // surface
val Ink800 = Color(0xFF1D201D)   // raised surface
val Ink700 = Color(0xFF30342E)   // dividers, inactive outlines
val Ink500 = Color(0xFF777B70)   // disabled text
val Ink300 = Color(0xFFA9AB9F)   // secondary text
val Ink200 = Color(0xFFC9C6BA)   // body text
val Ink50 = Color(0xFFF2EEE3)    // emphasis

// Signal accent. Burnt orange, not a generic blue. The light scheme uses a
// deeper step so links and the primary button still pass a contrast check on
// warm paper.
val Accent = Color(0xFFFF875B)
val AccentDeep = Color(0xFFB74720)
val AccentDim = Color(0xFF4A2418)

// Semantic, for tool results and errors.
val Warn = Color(0xFFE3B567)
val Danger = Color(0xFFF27B6B)
val DangerDeep = Color(0xFFA83226)
val Ok = Color(0xFFA8C98B)

// Light scheme -- warm paper, ink, and the same signal accent.
val Paper100 = Color(0xFFF3EFE5)   // app background
val Paper200 = Color(0xFFEAE4D8)   // surface
val Paper300 = Color(0xFFD8CFBF)   // raised surface / outline
val PaperInk = Color(0xFF1B1A17)
// Secondary text on Paper100 *and* Paper200. The old value (0xFF6F685D) was
// 4.35:1 on the dialog surface -- below AA for body text -- while passing on
// the page background; every dialog uses the surface. This step is ~5.8:1.
val PaperMuted = Color(0xFF5E574D)
