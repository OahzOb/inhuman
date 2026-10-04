package com.inhuman.core.design

import androidx.compose.ui.graphics.Color

/**
 * A deliberately small palette.
 *
 * A chat app is mostly text on a near-black background, so the accent colour is
 * spent in very few places: the send button, a selected conversation, and the
 * streaming indicator. Everything else is a neutral step.
 */

// Dark scheme -- the app's default, because a chat app is read at night.
val Ink900 = Color(0xFF0B0D10)   // app background
val Ink850 = Color(0xFF111418)   // message surface
val Ink800 = Color(0xFF16191E)   // raised surface (composer, sheets)
val Ink700 = Color(0xFF232830)   // dividers, inactive outlines
val Ink500 = Color(0xFF6B7280)   // secondary text
val Ink200 = Color(0xFFB7BDC7)   // primary text, softened
val Ink50 = Color(0xFFF2F4F7)    // primary text, emphasis

// Accent. One hue, used sparingly.
val Accent = Color(0xFF7AA2F7)
val AccentDim = Color(0xFF3D5A8A)

// Semantic, for tool results and errors.
val Warn = Color(0xFFE0AF68)
val Danger = Color(0xFFF7768E)
val Ok = Color(0xFF9ECE6A)

// Light scheme -- present but not the focus; the dark palette is the design.
val Paper100 = Color(0xFFFBFBFD)
val Paper200 = Color(0xFFF1F2F6)
val Paper300 = Color(0xFFE3E5EB)
val PaperInk = Color(0xFF1B1F24)
