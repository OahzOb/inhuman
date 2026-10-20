package com.inhuman.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's wordmark.
 *
 * Lowercase and monospaced rather than a logo image: it is a working tool, and
 * the letterspacing is enough to make the name feel intentional without adding
 * an asset that has to be redrawn at every size.
 */
@Composable
fun Wordmark(
	modifier: Modifier = Modifier,
	size: Int = 20,
	color: Color = MaterialTheme.colorScheme.onBackground,
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(7.dp),
	) {
		Text(
			text = "inhuman",
			fontFamily = FontFamily.Monospace,
			fontWeight = FontWeight.Bold,
			fontSize = size.sp,
			letterSpacing = 1.2.sp,
			color = color,
		)
		Box(
			modifier = Modifier
				.size(4.dp)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.primary),
		)
	}
}

/** A small uppercase label, for section codes and metadata. */
@Composable
fun Eyebrow(
	text: String,
	modifier: Modifier = Modifier,
	color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
	Text(
		text = text.uppercase(),
		modifier = modifier,
		style = MaterialTheme.typography.labelMedium,
		color = color,
	)
}

/** A one-pixel rule that does not rely on elevation. */
@Composable
fun Hairline(
	modifier: Modifier = Modifier,
	color: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
) {
	Box(
		modifier = modifier
			.fillMaxWidth()
			.height(1.dp)
			.background(color),
	)
}

/** A live/status dot, sized so it reads at a glance without becoming decoration. */
@Composable
fun StatusDot(
	color: Color,
	modifier: Modifier = Modifier,
	size: Dp = 6.dp,
) {
	Box(
		modifier = modifier
			.size(size)
			.clip(CircleShape)
			.background(color),
	)
}

/**
 * A faint measurement grid for otherwise empty canvases.
 *
 * It is deliberately almost invisible: the point is to give a large tablet
 * empty state a sense of place, not to become wallpaper. The lines are drawn
 * from the top-left so they align between the home rail and the chat welcome.
 */
@Composable
fun WorkbenchGrid(
	modifier: Modifier = Modifier,
	step: Dp = 48.dp,
) {
	val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.12f)
	Canvas(modifier) {
		val px = step.toPx()
		var x = 0f
		while (x < size.width) {
			drawLine(line, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1f)
			x += px
		}
		var y = 0f
		while (y < size.height) {
			drawLine(line, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
			y += px
		}
	}
}

/**
 * A section heading in the settings/assistant surfaces.
 *
 * The code is the small mono line above the title. It is useful for scanning:
 * the eye finds `01 / MODEL` faster than it finds a sentence.
 */
@Composable
fun SectionHeading(
	code: String,
	title: String,
	modifier: Modifier = Modifier,
	detail: String? = null,
) {
	Column(
		modifier = modifier.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Eyebrow(code)
		Text(
			text = title,
			style = MaterialTheme.typography.titleLarge,
			color = MaterialTheme.colorScheme.onBackground,
		)
		detail?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

/**
 * A switch with an explicit thumb/track contrast contract.
 *
 * The Material default derives the checked thumb from `onPrimary`. In this
 * palette that is deliberately a dark ink in the dark scheme and warm paper in
 * the light one, so on some surfaces the thumb can read as part of the track.
 * The working rule here is simpler: the thumb is always the app background, the
 * track is the signal accent, and the border is the accent. That is legible in
 * both schemes and stays legible if the accent changes.
 */
@Composable
fun WorkbenchSwitch(
	checked: Boolean,
	onCheckedChange: ((Boolean) -> Unit)?,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	Switch(
		checked = checked,
		onCheckedChange = onCheckedChange,
		modifier = modifier,
		enabled = enabled,
		colors = SwitchDefaults.colors(
			checkedThumbColor = MaterialTheme.colorScheme.background,
			checkedTrackColor = MaterialTheme.colorScheme.primary,
			checkedBorderColor = MaterialTheme.colorScheme.primary,
			uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
			uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
			uncheckedBorderColor = MaterialTheme.colorScheme.outline,
		),
	)
}

/** A compact label/value pair used in the home rail and chat welcome. */
@Composable
fun MetaLine(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier.fillMaxWidth(),
		verticalAlignment = Alignment.Top,
	) {
		Text(
			text = label.uppercase(),
			modifier = Modifier.width(108.dp),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = value,
			modifier = Modifier.weight(1f),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}

/** Breathing room used by the screen shells, not a general-purpose spacer. */
@Composable
fun ScreenTopSpace(height: Dp = 8.dp) {
	Spacer(Modifier.height(height))
}
