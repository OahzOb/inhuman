package com.inhuman.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inhuman.R

/** One back affordance, so six screens cannot drift into three glyphs. */
@Composable
fun WorkbenchBackButton(onClick: () -> Unit) {
	IconButton(onClick = onClick) {
		Icon(
			Icons.AutoMirrored.Filled.ArrowBack,
			contentDescription = stringResource(R.string.common_back),
			tint = MaterialTheme.colorScheme.onBackground,
			modifier = Modifier.size(20.dp),
		)
	}
}
