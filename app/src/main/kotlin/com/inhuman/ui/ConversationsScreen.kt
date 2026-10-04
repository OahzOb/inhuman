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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
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
import com.inhuman.core.design.FormFactor
import com.inhuman.core.design.InhumanTheme
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.core.design.currentFormFactor
import com.inhuman.core.design.prefersFabForPrimaryAction

/**
 * The conversation list.
 *
 * Phase 0 renders only the empty state. The list itself, and the persistence
 * behind it, arrive with the database -- a fake in-memory list here would be
 * work thrown away.
 *
 * The layout deliberately differs by form factor rather than scaling: a phone
 * gets a floating action button within thumb reach and a tighter gutter, a
 * tablet gets a top-bar action and a capped text measure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
	onNewConversation: () -> Unit = {},
) {
	val formFactor = currentFormFactor()
	val useFab = prefersFabForPrimaryAction()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(text = "inhuman", style = MaterialTheme.typography.titleMedium) },
				actions = {
					// On a phone the FAB carries this instead, so the top bar
					// keeps only the title.
					if (!useFab) {
						IconButton(onClick = onNewConversation) {
							Icon(Icons.Filled.Add, contentDescription = "New conversation")
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					actionIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		floatingActionButton = {
			if (useFab) {
				FloatingActionButton(
					onClick = onNewConversation,
					containerColor = MaterialTheme.colorScheme.primary,
					contentColor = MaterialTheme.colorScheme.onPrimary,
					shape = RoundedCornerShape(16.dp),
				) {
					Icon(Icons.Filled.Add, contentDescription = "New conversation")
				}
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				// safeDrawing, not just the scaffold's insets: on a phone the
				// gesture bar and the camera cutout both matter, and the empty
				// state is vertically centred so it must not drift under either.
				.windowInsetsPadding(WindowInsets.safeDrawing),
			contentAlignment = Alignment.Center,
		) {
			EmptyState(formFactor = formFactor, onNewConversation = onNewConversation)
		}
	}
}

@Composable
private fun EmptyState(
	formFactor: FormFactor,
	onNewConversation: () -> Unit,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = comfortableGutter())
			// The measure cap is what makes the tablet layout readable rather
			// than merely wide. On a phone this is inert -- 411dp minus gutters
			// is already well under 640dp.
			.widthIn(max = Layout.ReadableMeasure),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Icon(
			imageVector = Icons.Outlined.FolderOpen,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.outline,
			modifier = Modifier.size(if (formFactor == FormFactor.Compact) 40.dp else 48.dp),
		)
		Text(
			text = "No conversations yet",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
			textAlign = TextAlign.Center,
		)
		Text(
			// The one thing worth stating up front, because it is what makes
			// this app different from every other chat client.
			text = "Each conversation gets its own folder. " +
				"The assistant can read and write files inside it, and nowhere else. " +
				"That is enforced in code, not requested in a prompt.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			textAlign = TextAlign.Center,
		)
		// On a phone the FAB already offers this, and two controls for one
		// action is one too many. The button stays on larger screens where the
		// empty state is the natural place to start.
		if (formFactor != FormFactor.Compact) {
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
}

@Preview(name = "Phone (411dp)", showBackground = true, backgroundColor = 0xFF0B0D10, widthDp = 411, heightDp = 900)
@Composable
private fun ConversationsPhonePreview() {
	InhumanTheme(darkTheme = true) { ConversationsScreen() }
}

@Preview(name = "Tablet (1280dp)", showBackground = true, backgroundColor = 0xFF0B0D10, widthDp = 1280, heightDp = 800)
@Composable
private fun ConversationsTabletPreview() {
	InhumanTheme(darkTheme = true) { ConversationsScreen() }
}
