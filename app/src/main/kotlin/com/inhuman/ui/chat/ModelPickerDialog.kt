package com.inhuman.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.design.WorkbenchSwitch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Model and reasoning, for this conversation.
 *
 * ## Why these are per conversation and not per assistant
 *
 * Switching models mid-thread is a decision about *this* thread -- "this answer
 * was too slow" or "this one needs the bigger model" -- and writing it back to
 * the assistant would change every other conversation held with it. The
 * assistant supplies the starting point; what a conversation does after that is
 * its own.
 *
 * ## Why the effort levels come from the model
 *
 * `effort.supported_levels` is per model -- `deepseek-flash` and
 * `deepseek-v4-pro` both advertise low, high and max today, but nothing
 * guarantees they stay in step. A fixed list in the app would let someone pick a
 * level the selected model does not accept, and the failure would surface as an
 * API error that names nothing they did.
 *
 * @param effectiveModel the model actually in use, which is
 *   [currentModel] when it is set and the assistant's otherwise. The dialog
 *   needs this rather than [currentModel] alone: null there means "whatever the
 *   assistant says", and looking up a model by a null id finds nothing -- which
 *   made the effort control claim the model had no levels when it had three.
 */
@Composable
fun ModelPickerDialog(
	chat: ChatViewModel,
	currentModel: String?,
	effectiveModel: String?,
	currentEffort: String?,
	currentThinking: Boolean,
	onDismiss: () -> Unit,
) {
	var models by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
	var loading by remember { mutableStateOf(true) }
	var error by remember { mutableStateOf<String?>(null) }
	var attempt by remember { mutableStateOf(0) }

	// Reloaded every time the dialog opens rather than cached in the screen:
	// this is the moment the information is wanted, and a list fetched an hour
	// ago is a list that may already be wrong.
	//
	// Cached first, refreshed second. The cache is populated when the
	// conversation resolves its model, so the common case has a list before the
	// dialog is drawn; the refetch updates it without making a slow network
	// look like a broken picker. The spinner is only for a genuinely cold list.
	LaunchedEffect(attempt) {
		loading = true
		error = null
		Diag.d("models", "dialog open; reading cache")
		val cached = chat.cachedModels()
		if (cached.isNotEmpty()) {
			models = cached
			loading = false
			Diag.d("models", "dialog cached=${cached.size}; refreshing")
		}
		// The HTTP call has its own shorter timeout; this one covers the cases
		// around it (a blocked key read, a mutex, a JNI call that never
		// returns). A loading state must not be able to outlive the dialog's
		// usefulness, which is what the unbounded spinner was.
		val result = withTimeoutOrNull(25_000) { chat.models(force = true) }
		if (result == null) {
			if (models.isEmpty()) {
				error = "Loading models timed out. Check the connection and try again."
			} else {
				Diag.w("models", "refresh timed out; keeping ${models.size} cached")
			}
			Diag.w("models", "dialog timed out after 25s")
			loading = false
			return@LaunchedEffect
		}
		val refreshed = result.getOrNull()
		if (refreshed != null) {
			models = refreshed
			error = null
		} else {
			// A refresh failure does not erase a list that is already usable.
			// Report it only when there is nothing to choose from.
			if (models.isEmpty()) {
				error = result.exceptionOrNull()?.message ?: "Could not load the model list."
			} else {
				Diag.w(
					"models",
					"refresh failed; keeping ${models.size} cached: " +
						(result.exceptionOrNull()?.message ?: "unknown"),
				)
			}
		}
		Diag.d(
			"models",
			"dialog result ok=${result.isSuccess} models=${models.size} error=${error ?: "-"}",
		)
		loading = false
	}

	val selected = models.firstOrNull { it.id == effectiveModel }
	val levels = selected?.supportedEffortLevels.orEmpty()

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Model") },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = 460.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (loading) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(10.dp),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(
							text = "Loading models…",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				error?.let {
					Text(
						text = it,
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.error,
					)
					TextButton(onClick = { attempt++ }) { Text("Retry") }
				}

				if (!loading) {
					Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
						ModelOption(
							label = "Assistant's default",
							detail = "Whatever the assistant was set up with.",
							selected = currentModel == null,
							onClick = {
								chat.setModel(null)
								onDismiss()
							},
						)
						// Said rather than left as a blank space. An empty list
						// is a real answer from the provider, and a dialog that
						// simply has nothing under the default looks broken --
						// which is how this presented before the failure branch
						// above was made reachable.
						if (models.isEmpty() && error == null) {
							Text(
								text = "The provider listed no models. Check the key in " +
									"settings, or turn on demo mode there.",
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						models.forEach { m ->
							ModelOption(
								// The display name, not the id: "DeepSeek-V4.1-Flash"
								// is what the provider calls it, and "deepseek-flash"
								// is an implementation detail. They differ, so this
								// is not a cosmetic choice.
								label = m.displayName?.takeIf { it.isNotBlank() } ?: m.id,
								detail = buildString {
									m.contextWindow?.let { append("${it / 1024}K context") }
									append(" · ")
									append(if (m.supportsVision) "text and images" else "text only")
									m.defaultEffortLevel?.let { append(" · default effort $it") }
								},
								selected = currentModel == m.id,
								onClick = {
									chat.setModel(m.id)
									onDismiss()
								},
							)
						}
					}

					HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

					// Thinking, which is what the effort level applies to. Off by
					// default everywhere: reasoning tokens are billed, and for
					// most messages there is nothing to reason about.
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = "Thinking",
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
							)
							Text(
								text = "Lets the model reason before answering, at extra cost.",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						WorkbenchSwitch(
							checked = currentThinking,
							onCheckedChange = { chat.setThinking(it) },
						)
					}

					if (currentThinking) {
						Text(
							text = "Reasoning effort",
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						if (levels.isEmpty()) {
							Text(
								text = "This model reports no effort levels, so the provider decides.",
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.outline,
							)
						} else {
							// Exactly what the model advertises, in its order.
							androidx.compose.foundation.layout.FlowRow(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(6.dp),
								verticalArrangement = Arrangement.spacedBy(6.dp),
							) {
								FilterChip(
									selected = currentEffort == null,
									onClick = { chat.setReasoningEffort(null) },
									// The default is named rather than called
									// "default". The API reports
									// `effort.default_level`, so the level that
									// will actually be used is known -- and
									// "model default" alone leaves someone
									// choosing between a named level and an
									// unnamed one, unable to tell whether they
									// just picked it.
									label = {
										Text(
											selected?.defaultEffortLevel
												?.let { "default ($it)" }
												?: "model default",
										)
									},
								)
								levels.forEach { level ->
									FilterChip(
										selected = currentEffort == level,
										onClick = { chat.setReasoningEffort(level) },
										label = { Text(level) },
									)
								}
							}
						}
					}
				}
			}
		},
		confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun ModelOption(
	label: String,
	detail: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	FilterChip(
		selected = selected,
		onClick = onClick,
		label = {
			Column {
				Text(label, style = MaterialTheme.typography.bodyLarge)
				Text(
					text = detail,
					style = MaterialTheme.typography.labelLarge,
					color = if (selected) {
						MaterialTheme.colorScheme.onSecondaryContainer
					} else {
						MaterialTheme.colorScheme.onSurfaceVariant
					},
				)
			}
		},
		modifier = Modifier.fillMaxWidth(),
	)
}
