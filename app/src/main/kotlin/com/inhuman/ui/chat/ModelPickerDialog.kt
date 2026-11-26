package com.inhuman.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.inhuman.core.ai.model.ModelCapabilities
import com.inhuman.core.ai.model.ModelCapability
import com.inhuman.core.ai.model.ModelTagCodec
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.design.WorkbenchSwitch
import com.inhuman.core.design.dialogBodyMaxHeight
import com.inhuman.R
import com.inhuman.core.design.Layout
import kotlinx.coroutines.launch
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
	currentProviderId: String,
	effectiveModel: String?,
	currentEffort: String?,
	currentThinking: Boolean,
	/**
	 * True while a turn streams: switching provider is refused then, so
	 * the chips say that instead of looking dead. The refusal used to be
	 * written to the error row behind this dialog, where nobody saw it.
	 */
	streaming: Boolean,
	onDismiss: () -> Unit,
) {
	var models by remember { mutableStateOf<List<RemoteModel>>(emptyList()) }
	var providers by remember { mutableStateOf<List<ProviderProfileEntity>>(emptyList()) }
	var loading by remember { mutableStateOf(true) }
	var error by remember { mutableStateOf<String?>(null) }
	var attempt by remember { mutableStateOf(0) }
	val context = LocalContext.current
	val scope = rememberCoroutineScope()

	/**
	 * Tags the user has given the current provider's models, by model id.
	 *
	 * Empty is "nothing tagged", which is what the guess in `ModelCapabilities`
	 * is for; a tagged model replaces the guess.
	 */
	var tags by remember { mutableStateOf<Map<String, Set<ModelCapability>>>(emptyMap()) }
	/** Whether the models that are not for chatting are expanded. Hidden by default. */
	var showOtherModels by remember { mutableStateOf(false) }
	/** The model whose tags are being edited, if any. */
	var tagging by remember { mutableStateOf<RemoteModel?>(null) }

	LaunchedEffect(attempt) {
		providers = runCatching { chat.providerProfiles() }.getOrDefault(emptyList())
	}

	// Reloaded every time the dialog opens rather than cached in the screen:
	// this is the moment the information is wanted, and a list fetched an hour
	// ago is a list that may already be wrong.
	//
	// Cached first, refreshed second. The cache is populated when the
	// conversation resolves its model, so the common case has a list before the
	// dialog is drawn; the refetch updates it without making a slow network
	// look like a broken picker. The spinner is only for a genuinely cold list.
	LaunchedEffect(attempt, currentProviderId) {
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
				error = context.getString(R.string.model_picker_timeout)
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
				error = result.exceptionOrNull()?.message
					?: context.getString(R.string.model_picker_load_failed)
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

	// The stored tags belong to the provider, so they are re-read whenever the
	// provider chips change as well as on open.
	LaunchedEffect(providers, currentProviderId) {
		tags = ModelTagCodec.decode(
			providers.firstOrNull { it.id == currentProviderId }?.modelTags.orEmpty(),
		)
	}

	fun capabilitiesOf(model: RemoteModel): Set<ModelCapability> =
		ModelCapabilities.effective(model.id, tags[model.id])

	val chatModels = models.filter { ModelCapabilities.isChatModel(capabilitiesOf(it)) }
	val otherModels = models.filterNot { ModelCapabilities.isChatModel(capabilitiesOf(it)) }

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.model_picker_title)) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.heightIn(max = dialogBodyMaxHeight(460.dp))
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				if (loading) {
					Row(
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
					) {
						CircularProgressIndicator(strokeWidth = 2.dp)
						Text(
							text = stringResource(R.string.model_picker_loading),
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
					TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.chat_retry)) }
				}

				if (providers.isNotEmpty()) {
					Text(
						text = stringResource(R.string.model_picker_provider),
						style = MaterialTheme.typography.labelLarge,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					androidx.compose.foundation.layout.FlowRow(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
						verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
					) {
						providers.forEach { provider ->
							val hasKey = chat.hasProviderKey(provider.id)
							FilterChip(
								selected = currentProviderId == provider.id,
								enabled = !streaming,
								onClick = { chat.setProvider(provider.id) },
								label = {
									Text(
										if (hasKey) {
											provider.name
										} else {
											provider.name + " · " +
												stringResource(R.string.model_picker_provider_no_key)
										},
									)
								},
							)
						}
					}
					Text(
						text = if (streaming) {
							stringResource(R.string.model_picker_provider_streaming)
						} else {
							stringResource(R.string.model_picker_provider_hint)
						},
						style = MaterialTheme.typography.labelLarge,
						color = if (streaming) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
					HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				}

				if (!loading) {
					Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage)) {
						ModelOption(
							label = stringResource(R.string.model_picker_provider_default),
							detail = stringResource(R.string.model_picker_provider_default_detail),
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
								text = stringResource(R.string.model_picker_no_models),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						chatModels.forEach { m ->
							val caps = capabilitiesOf(m)
							val suffix = capabilitySuffix(caps)
							// Resolved before the builder: stringResource is a
							// composable call, and keeping it out of buildString
							// makes the read obvious to the next editor.
							val contextText = m.contextWindow?.let {
								stringResource(R.string.model_picker_context, it / 1024)
							}
							val visionText = stringResource(
								if (m.supportsVision) {
									R.string.model_picker_text_and_images
								} else {
									R.string.model_picker_text_only
								},
							)
							val effortText = m.defaultEffortLevel?.let {
								stringResource(R.string.model_picker_default_effort, it)
							}
							ModelOption(
								// The display name, not the id: "DeepSeek-V4.1-Flash"
								// is what the provider calls it, and "deepseek-flash"
								// is an implementation detail. They differ, so this
								// is not a cosmetic choice.
								label = m.displayName?.takeIf { it.isNotBlank() } ?: m.id,
								detail = buildString {
									contextText?.let { append(it) }
									append(" · ")
									append(visionText)
									append(suffix)
									effortText?.let { append(" · "); append(it) }
								},
								selected = currentModel == m.id,
								onClick = {
									chat.setModel(m.id)
									onDismiss()
								},
								onEditTags = { tagging = m },
							)
						}

						// A provider's list is everything the account can call,
						// not a list of chat models. The ones that are visibly
						// something else are folded away rather than dropped: a
						// wrong guess must not make a model unreachable, and the
						// fold is where the tag editor is found.
						if (otherModels.isNotEmpty()) {
							TextButton(onClick = { showOtherModels = !showOtherModels }) {
								Text(
									stringResource(
										if (showOtherModels) {
											R.string.model_picker_hide_other
										} else {
											R.string.model_picker_show_other
										},
										otherModels.size,
									),
								)
							}
							Text(
								text = stringResource(R.string.model_picker_other_hint),
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							if (showOtherModels) {
								otherModels.forEach { m ->
									val suffix = capabilitySuffix(capabilitiesOf(m))
									ModelOption(
										label = m.displayName?.takeIf { it.isNotBlank() } ?: m.id,
										detail = stringResource(R.string.model_picker_not_chat) + suffix,
										selected = currentModel == m.id,
										onClick = {
											chat.setModel(m.id)
											onDismiss()
										},
										onEditTags = { tagging = m },
									)
								}
							}
						}
					}

					HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

					// Thinking, which is what the effort level applies to. Off by
					// default everywhere: reasoning tokens are billed, and for
					// most messages there is nothing to reason about.
					Row(
						modifier = Modifier
							.fillMaxWidth()
							// The whole row is the switch. A label that does not
							// toggle is the usual reason a settings row feels
							// broken on a phone, and it leaves TalkBack with a
							// small unlabelled target.
							.toggleable(
								value = currentThinking,
								role = Role.Switch,
								onValueChange = { chat.setThinking(it) },
							),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = stringResource(R.string.model_picker_thinking),
								style = MaterialTheme.typography.bodyLarge,
								color = MaterialTheme.colorScheme.onSurface,
							)
							Text(
								text = stringResource(R.string.model_picker_thinking_detail),
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
						WorkbenchSwitch(
							checked = currentThinking,
							onCheckedChange = null,
						)
					}

					if (currentThinking) {
						Text(
							text = stringResource(R.string.model_picker_effort),
							style = MaterialTheme.typography.labelLarge,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						if (levels.isEmpty()) {
							Text(
								text = stringResource(R.string.model_picker_effort_none),
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						} else {
							// Exactly what the model advertises, in its order.
							androidx.compose.foundation.layout.FlowRow(
								modifier = Modifier.fillMaxWidth(),
								horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
								verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
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
										val defaultLevel = selected?.defaultEffortLevel
										Text(
											if (defaultLevel != null) {
												stringResource(
													R.string.model_picker_effort_default_named,
													defaultLevel,
												)
											} else {
												stringResource(R.string.model_picker_effort_default)
											},
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
		confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.model_picker_done)) } },
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)

	// Beside the picker rather than inside it: both are dialogs, and nesting one
	// inside the other's content slot makes the second one size itself to the
	// first.
	tagging?.let { model ->
		ModelTagDialog(
			model = model,
			current = capabilitiesOf(model),
			onDismiss = { tagging = null },
			onSave = { capabilities ->
				tagging = null
				scope.launch {
					chat.setModelTags(currentProviderId, model.id, capabilities)
					// A tag the user just set is shown immediately; the row it
					// moves between (chat models vs the fold) follows from it.
					tags = if (capabilities.isEmpty()) {
						tags - model.id
					} else {
						tags + (model.id to capabilities)
					}
				}
			},
		)
	}
}

@Composable
private fun ModelOption(
	label: String,
	detail: String,
	selected: Boolean,
	onClick: () -> Unit,
	onEditTags: (() -> Unit)? = null,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
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
			modifier = Modifier.weight(1f),
		)
		onEditTags?.let { onEdit ->
			IconButton(onClick = onEdit) {
				Icon(
					imageVector = Icons.AutoMirrored.Outlined.Label,
					contentDescription = stringResource(R.string.model_picker_edit_tags),
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.size(18.dp),
				)
			}
		}
	}
}

/**
 * The capability words for a model, as a suffix to its detail line.
 *
 * Empty for a plain text model: the common case is not worth a label, and a
 * line of "· 文本" on every row is noise that makes the two rows which *are*
 * something else harder to spot.
 */
@Composable
private fun capabilitySuffix(capabilities: Set<ModelCapability>): String {
	val extra = capabilities.filterNot { it == ModelCapability.Text }
	if (extra.isEmpty()) return ""
	// `map` is inline, so the composable call is allowed there; `joinToString`
	// with a transform is not, which is why this is two steps.
	val words = extra.map { stringResource(it.labelResource()) }
	return " · " + words.joinToString(" · ")
}

private fun ModelCapability.labelResource(): Int = when (this) {
	ModelCapability.Text -> R.string.model_capability_text
	ModelCapability.Image -> R.string.model_capability_image
	ModelCapability.Video -> R.string.model_capability_video
	ModelCapability.Audio -> R.string.model_capability_audio
	ModelCapability.Tools -> R.string.model_capability_tools
}

/**
 * Which words apply to this model.
 *
 * A tick list rather than a dropdown because the tags compose (a model can do
 * text and tools) and because the guess has to stay visible: the hint says that
 * these replace it, so someone who untags everything knows they just told the
 * app "no opinion".
 */
@Composable
private fun ModelTagDialog(
	model: RemoteModel,
	current: Set<ModelCapability>,
	onDismiss: () -> Unit,
	onSave: (Set<ModelCapability>) -> Unit,
) {
	var draft by remember(model.id) { mutableStateOf(current) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(model.displayName?.takeIf { it.isNotBlank() } ?: model.id) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
				Text(
					text = stringResource(R.string.model_picker_tag_hint),
					style = MaterialTheme.typography.labelLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				ModelCapability.entries.forEach { capability ->
					val on = capability in draft
					FilterChip(
						selected = on,
						onClick = {
							draft = if (on) draft - capability else draft + capability
						},
						label = { Text(stringResource(capability.labelResource())) },
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.common_save)) }
		},
		dismissButton = {
			Row(horizontalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
				TextButton(onClick = { onSave(emptySet()) }) {
					Text(stringResource(R.string.model_picker_tag_clear))
				}
				TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
			}
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}
