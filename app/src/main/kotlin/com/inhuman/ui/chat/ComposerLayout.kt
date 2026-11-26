package com.inhuman.ui.chat

import com.inhuman.core.design.WindowHeightClass

/**
 * How many lines the composer's field may grow to before it scrolls.
 *
 * The defect this replaces: on a 640x360dp landscape window the composer had
 * no height limit at all, so a three-line draft plus the IME left the 48dp
 * action row -- attach, microphone, context ring, send/stop -- below the
 * screen edge, reachable only by blind tapping ([docs/06](../docs/06-agent-loop-ui.md)
 * recorded it as the short-landscape caveat). Six lines is the phone-portrait
 * limit and stays: a portrait phone has the vertical room, and shaving a line
 * off every sentence typed there would pay for a landscape problem with a
 * portrait cost.
 *
 * The rule is the window's height class, not a fraction of its height. A
 * proportional cap (say 20% of the window) also binds on a 360x800dp portrait
 * phone, where the composer has all the room it needs; the class binds exactly
 * where the matrix says the window is short, which is the same "cap binds on
 * the large/short screen and does nothing elsewhere" shape the rest of
 * `Layout` uses.
 *
 * Pure on purpose: this is a composition decision, but the decision itself is
 * arithmetic, and `ComposerMaxLinesTest` pins the matrix cells. The rendering
 * half was verified on device for the dialog family; the emulator session used
 * here could not inject text at all (`mInputShown=true`, the field `focused`,
 * and neither `input text` nor keyevents arrived), so the composer's own
 * screenshot check is recorded in docs/07 B41 as not-device-driven rather than
 * claimed.
 */
internal fun composerMaxLines(heightClass: WindowHeightClass): Int =
	if (heightClass == WindowHeightClass.Compact) 2 else 6
