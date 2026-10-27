package com.inhuman.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.inhuman.R
import com.inhuman.core.ai.capability.ProviderCapabilities
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.ui.chat.MediaCapability
import com.inhuman.ui.chat.MediaMode
import com.inhuman.ui.chat.MediaToolDraft
import com.inhuman.ui.common.FlowRowCompat

/**
 * One media tool's backend dialog.
 *
 * Three modes rather than a single form: "the assistant's provider" needs no
 * fields, "a configured provider" needs a profile and model, and "separate API"
 * needs a key and endpoint. Showing all three at once made a long form where the
 * fields that did not apply looked required.
 */
@Composable
internal fun MediaToolDialog(
	draft: MediaToolDraft,
	providers: List<ProviderProfileEntity>,
	onMode: (MediaMode) -> Unit,
	onProvider: (String) -> Unit,
	onModel: (String) -> Unit,
	onModelPicker: (Boolean) -> Unit,
	onBaseUrl: (String) -> Unit,
	onApiKey: (String) -> Unit,
	onSave: () -> Unit,
	onClose: () -> Unit,
) {
	val eligibleProviders = providers.filter {
		ProviderCapabilities.supports(it.kind, draft.capability.providerCapability)
	}
	AlertDialog(
		onDismissRequest = onClose,
		title = {
			Text(
				stringResource(
					R.string.settings_media_dialog_title,
					draft.capability.titleText(),
				),
			)
		},
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				FlowRowCompat {
					MediaMode.entries.forEach { mode ->
						FilterChip(
							selected = draft.mode == mode,
							onClick = { onMode(mode) },
							label = { Text(mode.label()) },
						)
					}
				}

				when (draft.mode) {
					MediaMode.Assistant -> Text(
						text = stringResource(R.string.settings_media_assistant_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					MediaMode.Provider -> {
						if (eligibleProviders.isEmpty()) {
							Text(
								text = stringResource(R.string.settings_media_no_provider),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.error,
							)
						} else {
							Text(
								text = stringResource(R.string.settings_media_provider),
								style = MaterialTheme.typography.labelLarge,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
							FlowRowCompat {
								eligibleProviders.forEach { provider ->
									FilterChip(
										selected = draft.providerId == provider.id,
										onClick = { onProvider(provider.id) },
										label = { Text(provider.name) },
									)
								}
							}
							if (draft.loadingModels) {
								Text(
									text = stringResource(R.string.settings_media_models_loading),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
							draft.modelError?.let { message ->
								Text(
									text = stringResource(
										R.string.settings_media_models_failed,
										message,
									),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.error,
								)
							}
							if (!draft.loadingModels && draft.modelError == null &&
								draft.models.isEmpty()
							) {
								Text(
									text = stringResource(R.string.settings_media_models_none),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
					MediaMode.Direct -> {
						Text(
							text = stringResource(R.string.settings_media_direct_note),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						OutlinedTextField(
							value = draft.baseUrl,
							onValueChange = onBaseUrl,
							modifier = Modifier.fillMaxWidth(),
							singleLine = true,
							label = { Text(stringResource(R.string.settings_media_base_url)) },
							placeholder = {
								Text(stringResource(R.string.settings_media_base_url_hint))
							},
						)
						OutlinedTextField(
							value = draft.apiKeyInput,
							onValueChange = onApiKey,
							modifier = Modifier.fillMaxWidth(),
							singleLine = true,
							label = { Text(stringResource(R.string.settings_media_api_key)) },
							visualTransformation = PasswordVisualTransformation(),
						)
						Text(
							text = stringResource(
								if (draft.hasKey) {
									R.string.settings_media_key_saved
								} else {
									R.string.settings_media_key_missing
								},
							),
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}

				OutlinedTextField(
					value = draft.model,
					onValueChange = onModel,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text(stringResource(R.string.settings_media_model_id)) },
					placeholder = {
						Text(
							stringResource(
								R.string.settings_media_model_default,
								draft.capability.defaultModel,
							),
						)
					},
				)
				Text(
					text = stringResource(
						R.string.settings_media_model_default,
						draft.capability.defaultModel,
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (draft.mode == MediaMode.Provider && draft.models.isNotEmpty()) {
					Box {
						OutlinedButton(onClick = { onModelPicker(true) }) {
							Text(stringResource(R.string.settings_media_choose_model))
						}
						DropdownMenu(
							expanded = draft.showModelPicker,
							onDismissRequest = { onModelPicker(false) },
						) {
							draft.models.forEach { model ->
								DropdownMenuItem(
									text = { Text(model.id) },
									onClick = {
										onModel(model.id)
										onModelPicker(false)
									},
								)
							}
						}
					}
				}

				draft.error?.let { message ->
					Text(
						text = message,
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = onSave) {
				Text(stringResource(R.string.common_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onClose) {
				Text(stringResource(R.string.common_cancel))
			}
		},
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

@Composable
private fun MediaCapability.titleText(): String = when (this) {
	MediaCapability.Image -> stringResource(R.string.settings_media_image)
	MediaCapability.Video -> stringResource(R.string.settings_media_video)
	MediaCapability.Speech -> stringResource(R.string.settings_media_speech)
	MediaCapability.Transcription -> stringResource(R.string.settings_media_transcribe)
	MediaCapability.Decision -> stringResource(R.string.settings_media_decision)
}

@Composable
private fun MediaMode.label(): String = when (this) {
	MediaMode.Assistant -> stringResource(R.string.settings_media_mode_assistant)
	MediaMode.Provider -> stringResource(R.string.settings_media_mode_provider)
	MediaMode.Direct -> stringResource(R.string.settings_media_mode_direct)
}