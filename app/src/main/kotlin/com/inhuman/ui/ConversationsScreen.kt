package com.inhuman.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.inhuman.core.design.InhumanTheme

/**
 * The conversation list.
 *
 * Phase 0 renders only the empty state. The list itself, and the persistence
 * behind it, arrive in the phase that adds the database -- writing a fake
 * in-memory list here would be work thrown away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
	onNewConversation: () -> Unit = {},
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Text(
						text = "inhuman",
						style = MaterialTheme.typography.titleMedium,
					)
				},
				actions = {
					IconButton(onClick = onNewConversation) {
						Icon(
							imageVector = Icons.Filled.Add,
							contentDescription = "New conversation",
						)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					actionIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing),
			contentAlignment = Alignment.Center,
		) {
			EmptyState(onNewConversation = onNewConversation)
		}
	}
}

@Composable
private fun EmptyState(onNewConversation: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 32.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Outlined.FolderOpen,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.outline,
			modifier = Modifier.size(48.dp),
		)
		Text(
			text = "No conversations yet",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			// The one thing worth stating up front, because it is what makes
			// this app different from every other chat client: the assistant
			// gets a real folder per conversation, and it cannot leave it.
			text = "Each conversation gets its own folder. " +
				"The assistant can read and write files inside it, " +
				"and nowhere else — this is enforced in code, not requested in a prompt.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		FilledTonalButton(
			onClick = onNewConversation,
			modifier = Modifier.padding(top = 8.dp),
			shape = RoundedCornerShape(10.dp),
			contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
		) {
			Text("Start a conversation")
		}
	}
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0D10)
@Composable
private fun ConversationsEmptyPreview() {
	InhumanTheme(darkTheme = true) {
		ConversationsScreen()
	}
}
