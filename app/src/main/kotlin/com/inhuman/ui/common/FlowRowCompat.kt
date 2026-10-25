package com.inhuman.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inhuman.core.design.Layout

/**
 * A wrapping row for choice chips.
 *
 * On a phone three chips do not fit beside each other; a plain Row clips the
 * last one, which is how "DeepSeek" became a sliver in the provider editor.
 */
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
	FlowRow(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		content()
	}
}
