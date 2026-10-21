package com.inhuman.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderReasoning
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.SectionHeading
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WorkbenchSwitch
import com.inhuman.core.design.comfortableGutter
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.ui.chat.ProviderDraft
import com.inhuman.ui.chat.SettingsViewModel
import java.text.DateFormat
import java.util.Date
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.PaidBadge
import com.inhuman.ui.common.PaidToolsNotice

/**
 * Provider configuration.
 *
 * One field today. The shape is deliberately not a general "providers" list:
 * writing that machinery for a single provider would be guessing at what the
 * second one needs, and the guess is usually wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
	viewModel: SettingsViewModel,
	onBack: () -> Unit,
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	var showHostKeysDialog by remember { mutableStateOf(false) }
	var backupMode by remember { mutableStateOf<BackupMode?>(null) }
	var backupUri by remember { mutableStateOf<Uri?>(null) }
	var showBackupPassphrase by remember { mutableStateOf(false) }
	var showUndoConfirm by remember { mutableStateOf(false) }
	var backupPassphrase by remember { mutableStateOf("") }
	var backupPassphraseConfirm by remember { mutableStateOf("") }

	val exportLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/octet-stream"),
	) { uri ->
		if (uri != null) {
			backupMode = BackupMode.Export
			backupUri = uri
			backupPassphrase = ""
			backupPassphraseConfirm = ""
			showBackupPassphrase = true
		}
	}
	val importLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument(),
	) { uri ->
		if (uri != null) {
			backupMode = BackupMode.Import
			backupUri = uri
			backupPassphrase = ""
			backupPassphraseConfirm = ""
			showBackupPassphrase = true
		}
	}

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
					navigationIcon = {
						IconButton(onClick = onBack) {
							Icon(
								Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = "Back",
								modifier = Modifier.size(20.dp),
							)
						}
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
						titleContentColor = MaterialTheme.colorScheme.onBackground,
						navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
					),
				)
				Hairline()
			}
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = comfortableGutter(), vertical = 12.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Column(
				modifier = Modifier
					// Constrain first, fill second. `fillMaxWidth()` is greedy and
					// consumes the incoming maximum, so with these swapped the cap
					// was inert and the field stretched across 1280dp.
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				SectionHeading(
					code = "01 / Models",
					title = "Providers",
					detail = "Each assistant chooses a provider. Keys are stored with the " +
						"Android Keystore and sent only to that provider.",
				)
				state.providers.forEach { provider ->
					ProviderRow(
						name = provider.name,
						baseUrl = provider.baseUrl,
						hasKey = viewModel.hasProviderKey(provider.id),
						onClick = { viewModel.editProvider(provider) },
					)
					Hairline()
				}
				TextButton(onClick = viewModel::addProvider) {
					Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text("Add provider", style = MaterialTheme.typography.labelLarge)
				}
				state.providerStatus?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodyMedium,
						color = if (state.providerStatusIsError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.primary
						},
					)
				}
			}

			Hairline(Modifier.padding(vertical = 14.dp))

			Column(
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				SectionHeading(
					code = "02 / Web",
					title = "Web tools",
					detail = "web_search and web_fetch use their own keys, not the chat " +
						"provider's. Pick a backend for each; calls are billed by the service " +
						"you choose.",
				)
				Eyebrow("Search backend")
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilterChip(
						selected = state.webSearchBackend == "deepseek",
						onClick = { viewModel.setWebSearchBackend("deepseek") },
						label = { Text("DeepSeek") },
					)
					FilterChip(
						selected = state.webSearchBackend == "exa",
						onClick = { viewModel.setWebSearchBackend("exa") },
						label = { Text("Exa") },
					)
				}
				Eyebrow("Fetch backend")
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilterChip(
						selected = state.webFetchBackend == "deepseek",
						onClick = { viewModel.setWebFetchBackend("deepseek") },
						label = { Text("DeepSeek") },
					)
					FilterChip(
						selected = state.webFetchBackend == "exa",
						onClick = { viewModel.setWebFetchBackend("exa") },
						label = { Text("Exa") },
					)
				}
				OutlinedTextField(
					value = state.webDeepSeekKeyInput,
					onValueChange = viewModel::onWebDeepSeekKeyChange,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text("DeepSeek web key (sk-…)") },
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (state.hasWebDeepSeekKey) "DeepSeek web key saved" else
						"DeepSeek web key not set",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = state.webExaKeyInput,
					onValueChange = viewModel::onWebExaKeyChange,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text("Exa API key") },
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (state.hasWebExaKey) "Exa key saved" else "Exa key not set",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				val missingSearch = state.webSearchBackend == "exa" && !state.hasWebExaKey ||
					state.webSearchBackend == "deepseek" && !state.hasWebDeepSeekKey
				val missingFetch = state.webFetchBackend == "exa" && !state.hasWebExaKey ||
					state.webFetchBackend == "deepseek" && !state.hasWebDeepSeekKey
				if (missingSearch || missingFetch) {
					Text(
						text = "A selected backend has no key, so its tool stays unavailable.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				Button(
					onClick = viewModel::saveWebTools,
					shape = MaterialTheme.shapes.small,
				) { Text("Save web keys") }
				Text(
					text = "There is no test button: validating these keys would spend a real search.",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				state.webStatus?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodyMedium,
						color = if (state.webStatusIsError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.primary
						},
					)
				}
			}

			HorizontalDivider(
				modifier = Modifier.padding(vertical = 8.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)

			// Demo mode.
			//
			// This exists because the streaming path is the part most likely to
			// break and the part an API key gate keeps out of reach. Scripted
			// replies make the whole pipeline exercisable with no key, no
			// network and no bill.
			Column(
				modifier = Modifier
					// Constrain first, fill second -- see the note on the field
					// above; the order is the whole difference.
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = "Demo mode",
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							// This string was describing an approval prompt that
							// no longer appears, and it is read by a user rather
							// than by a compiler -- the kind of claim that goes
							// stale silently. Deletion does not ask by default
							// (docs/09-approvals.md); a rule can be configured to
							// make it, and the sentence says so rather than
							// promising a dialog nobody will see.
							text = "Replies are scripted on the device, and the file " +
								"tools run for real. Nothing is sent anywhere and no key " +
								"is needed. With tools switched on it creates a file and " +
								"then deletes it, so the tool loop runs end to end " +
								"without an account.",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.demoMode,
						onCheckedChange = viewModel::setDemoMode,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
			}

			HorizontalDivider(
				modifier = Modifier.padding(vertical = 8.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)

			// SSH tool. Off by default, and the copy says what turning it on
			// does *not* do: it does not authorize a call by itself.
			Column(
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(4.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = "SSH tool",
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = "Off by default. When on, an assistant can be given " +
								"ssh_exec. It does not configure any remote machine: the " +
								"agent uses whatever host and credentials the user gives it. " +
								"The first SSH call in each conversation shows a consent " +
								"warning; after that, normal tool policy applies. The agent " +
								"can then run any command on the remote machine, which may " +
								"be irreversible. SSH tool arguments are redacted before " +
								"the transcript is stored, but text you type is still stored.",
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.sshToolEnabled,
						onCheckedChange = viewModel::setSshToolEnabled,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
				// TOFU is fail-closed, so a legitimately changed host key needs a
				// way out that is not "clear app data".
				TextButton(
					onClick = { showHostKeysDialog = true },
					enabled = state.knownHosts.isNotEmpty(),
				) {
					Text(
						if (state.knownHosts.isEmpty()) {
							"No pinned host keys yet"
						} else {
							"Pinned host keys (${state.knownHosts.size})"
						},
					)
				}
			}

			Column(
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				SectionHeading(
					code = "03 / Turn limits",
					title = "Tool calls per turn",
					detail = "A global budget for every conversation. One round can contain " +
						"several tool calls, so this limits model/tool round trips, not the " +
						"number of calls inside a round.",
				)
				FlowRowCompat {
					listOf(4, 8, 16, 24, 32, 64).forEach { rounds ->
						FilterChip(
							selected = state.toolRoundLimit == rounds,
							onClick = { viewModel.setToolRoundLimit(rounds) },
							label = { Text("$rounds") },
						)
					}
				}
				Text(
					text = "Current: ${state.toolRoundLimit} rounds per turn. " +
						"Raising this does not change which tools are allowed.",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			Hairline(Modifier.widthIn(max = Layout.ReadableMeasure))

			SectionHeading(
				code = "04 / Backup",
				title = "Backup",
				detail = "An encrypted file of every conversation, workspace file, " +
					"assistant and policy. API keys are deliberately not included.",
				modifier = Modifier.widthIn(max = Layout.ReadableMeasure),
			)
			FlowRowCompat {
				OutlinedButton(
					onClick = { exportLauncher.launch("inhuman-backup") },
					enabled = !state.backupBusy,
					shape = MaterialTheme.shapes.small,
				) { Text("Export backup") }
				OutlinedButton(
					onClick = { importLauncher.launch(arrayOf("*/*")) },
					enabled = !state.backupBusy,
					shape = MaterialTheme.shapes.small,
				) { Text("Import backup") }
				if (state.backupCanUndo) {
					OutlinedButton(
						onClick = { showUndoConfirm = true },
						enabled = !state.backupBusy,
						shape = MaterialTheme.shapes.small,
					) { Text("Undo last import") }
				}
			}
			Text(
				text = "Export asks for a passphrase and writes one encrypted file. " +
					"Import first shows what the file contains. The current data is " +
					"replaced only after a restart, and the previous copy is kept for " +
					"one Undo.",
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			state.backupStatus?.let { status ->
				Text(
					text = status,
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
					style = MaterialTheme.typography.bodyMedium,
					color = if (state.backupStatusIsError) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.primary
					},
				)
			}
			if (state.backupNeedsRestart) {
				Button(
					onClick = { Process.killProcess(Process.myPid()) },
					shape = MaterialTheme.shapes.small,
				) { Text("Restart now") }
			}
			if (state.backupBusy) {
				Text(
					text = "Working…",
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Hairline(Modifier.widthIn(max = Layout.ReadableMeasure))

			state.status?.let { status ->
				Text(
					text = status,
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
					style = MaterialTheme.typography.bodyMedium,
					color = if (state.statusIsError) {
						MaterialTheme.colorScheme.error
					} else {
						MaterialTheme.colorScheme.primary
					},
				)
			}

			ApprovalRulesSection(
				rules = state.approvalRules,
				onRuleChange = viewModel::setApprovalRule,
			)

			DiagnosticsSection()
		}
	}

	if (showHostKeysDialog) {
		AlertDialog(
			onDismissRequest = { showHostKeysDialog = false },
			title = { Text("Pinned host keys") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(
						"First-use fingerprints. Nothing here is configured by the app; " +
							"a changed key fails closed until the old one is forgotten.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Column(
						modifier = Modifier
							.heightIn(max = 280.dp)
							.verticalScroll(rememberScrollState()),
						verticalArrangement = Arrangement.spacedBy(8.dp),
					) {
						state.knownHosts.forEach { host ->
							Column {
								Text(host.host, style = MaterialTheme.typography.labelLarge)
								Text(
									host.fingerprint,
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
					state.knownHostsStatus?.let { message ->
						Text(
							message,
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { viewModel.forgetKnownHosts() }) { Text("Forget all") }
			},
			dismissButton = {
				TextButton(onClick = { showHostKeysDialog = false }) { Text("Close") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showBackupPassphrase && backupMode != null && backupUri != null) {
		val mode = backupMode!!
		val canSubmit = backupPassphrase.length >= 8 &&
			(mode == BackupMode.Import || backupPassphrase == backupPassphraseConfirm)
		AlertDialog(
			onDismissRequest = {
				showBackupPassphrase = false
				backupPassphrase = ""
				backupPassphraseConfirm = ""
			},
			title = {
				Text(if (mode == BackupMode.Export) "Encrypt backup" else "Inspect backup")
			},
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
					Text(
						text = if (mode == BackupMode.Export) {
							"Choose a passphrase of at least 8 characters. The file " +
								"contains conversations and workspace files; API keys are " +
								"not included. The passphrase cannot be recovered."
						} else {
							"Enter the passphrase used when this backup was created. " +
								"The file is read and summarized first; nothing is replaced " +
								"until you confirm and restart."
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					OutlinedTextField(
						value = backupPassphrase,
						onValueChange = { backupPassphrase = it },
						label = { Text("Passphrase") },
						singleLine = true,
						visualTransformation = PasswordVisualTransformation(),
						modifier = Modifier.fillMaxWidth(),
					)
					if (mode == BackupMode.Export) {
						OutlinedTextField(
							value = backupPassphraseConfirm,
							onValueChange = { backupPassphraseConfirm = it },
							label = { Text("Confirm passphrase") },
							singleLine = true,
							visualTransformation = PasswordVisualTransformation(),
							modifier = Modifier.fillMaxWidth(),
							isError = backupPassphraseConfirm.isNotEmpty() &&
								backupPassphraseConfirm != backupPassphrase,
						)
					}
				}
			},
			confirmButton = {
				Button(
					onClick = {
						val uri = backupUri ?: return@Button
						val passphrase = backupPassphrase.toCharArray()
						showBackupPassphrase = false
						backupPassphrase = ""
						backupPassphraseConfirm = ""
						if (mode == BackupMode.Export) {
							viewModel.exportBackup(uri, passphrase)
						} else {
							viewModel.previewImport(uri, passphrase)
						}
					},
					enabled = canSubmit,
					shape = MaterialTheme.shapes.small,
				) { Text(if (mode == BackupMode.Export) "Export" else "Inspect") }
			},
			dismissButton = {
				TextButton(
					onClick = {
						showBackupPassphrase = false
						backupPassphrase = ""
						backupPassphraseConfirm = ""
					},
				) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	state.backupPreview?.let { preview ->
		AlertDialog(
			onDismissRequest = viewModel::cancelImport,
			title = { Text("Import this backup?") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text(
						text = "${preview.conversations} " +
							(if (preview.conversations == 1) "conversation, " else "conversations, ") +
							"${preview.messages} messages, ${preview.workspaces} " +
							(if (preview.workspaces == 1) "workspace." else "workspaces."),
						style = MaterialTheme.typography.bodyLarge,
					)
					preview.createdAt?.let { createdAt ->
						Text(
							text = "Exported " + DateFormat.getDateTimeInstance(
								DateFormat.MEDIUM,
								DateFormat.SHORT,
							).format(Date(createdAt)),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Text(
						text = "Database version ${preview.databaseVersion}. " +
							"Nothing changes until the app restarts. The current data is " +
							"kept for one Undo; API keys are never replaced.",
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
			confirmButton = {
				Button(onClick = viewModel::confirmImport) { Text("Import") }
			},
			dismissButton = {
				TextButton(onClick = viewModel::cancelImport) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showUndoConfirm) {
		AlertDialog(
			onDismissRequest = { showUndoConfirm = false },
			title = { Text("Undo the last import?") },
			text = {
				Text(
					"The data that was present before the last import is still on " +
						"this device. Restoring it also needs a restart.",
				)
			},
			confirmButton = {
				Button(
					onClick = {
						showUndoConfirm = false
						viewModel.undoLastImport()
					},
				) { Text("Undo import") }
			},
			dismissButton = {
				TextButton(onClick = { showUndoConfirm = false }) { Text("Cancel") }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	state.editingProvider?.let { draft ->
		ProviderEditorDialog(
			draft = draft,
			onUpdate = viewModel::updateProviderDraft,
			onSave = viewModel::saveProvider,
			onTest = viewModel::testProvider,
			onDelete = viewModel::deleteProvider,
			onDismiss = viewModel::closeProviderEditor,
		)
	}
}

private enum class BackupMode { Export, Import }

@Composable
private fun ProviderRow(
	name: String,
	baseUrl: String,
	hasKey: Boolean,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 11.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		StatusDot(
			if (hasKey) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
			size = 7.dp,
		)
		Spacer(Modifier.width(12.dp))
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(3.dp),
		) {
			Text(
				text = name,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onBackground,
			)
			Text(
				text = baseUrl,
				style = MaterialTheme.typography.labelSmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
			)
		}
		Eyebrow(
			text = if (hasKey) "Key" else "No key",
			color = if (hasKey) {
				MaterialTheme.colorScheme.onSurfaceVariant
			} else {
				MaterialTheme.colorScheme.error
			},
		)
		Icon(
			Icons.AutoMirrored.Filled.KeyboardArrowRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(18.dp),
		)
	}
}

@Composable
private fun ProviderEditorDialog(
	draft: ProviderDraft,
	onUpdate: ((ProviderDraft) -> ProviderDraft) -> Unit,
	onSave: () -> Unit,
	onTest: () -> Unit,
	onDelete: () -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (draft.isNew) "Add provider" else draft.name) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = 520.dp)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				OutlinedTextField(
					value = draft.name,
					onValueChange = { value -> onUpdate { it.copy(name = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text("Name") },
					singleLine = true,
				)
				FlowRowCompat {
					FilterChip(
						selected = draft.kind == ProviderKinds.DEEPSEEK,
						onClick = {
							onUpdate {
								it.copy(
									kind = ProviderKinds.DEEPSEEK,
									baseUrl = if (it.isNew) "https://api.deepseek.com" else it.baseUrl,
									reasoningMode = ProviderReasoning.DEEPSEEK,
								)
							}
						},
						label = { Text("DeepSeek") },
					)
					FilterChip(
						selected = draft.kind == ProviderKinds.OPENAI_COMPATIBLE,
						onClick = {
							onUpdate {
								it.copy(
									kind = ProviderKinds.OPENAI_COMPATIBLE,
									baseUrl = if (it.isNew) "https://api.openai.com/v1" else it.baseUrl,
									reasoningMode = ProviderReasoning.NONE,
								)
							}
						},
						label = { Text("OpenAI-compatible") },
					)
				}
				OutlinedTextField(
					value = draft.baseUrl,
					onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text("Base URL") },
					supportingText = { Text("Include /v1 when the provider expects it.") },
					singleLine = true,
				)
				OutlinedTextField(
					value = draft.keyInput,
					onValueChange = { value ->
						onUpdate { it.copy(keyInput = value, hasKey = value.isNotBlank()) }
					},
					modifier = Modifier.fillMaxWidth(),
					label = { Text("API key") },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (draft.hasKey) "A key is stored for this provider." else
						"No key stored yet.",
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text("Reasoning fields", style = MaterialTheme.typography.labelLarge)
				FlowRowCompat {
					FilterChip(
						selected = draft.reasoningMode == ProviderReasoning.NONE,
						onClick = { onUpdate { it.copy(reasoningMode = ProviderReasoning.NONE) } },
						label = { Text("None") },
					)
					FilterChip(
						selected = draft.reasoningMode == ProviderReasoning.EFFORT,
						onClick = { onUpdate { it.copy(reasoningMode = ProviderReasoning.EFFORT) } },
						label = { Text("reasoning_effort") },
					)
					FilterChip(
						selected = draft.reasoningMode == ProviderReasoning.DEEPSEEK,
						onClick = { onUpdate { it.copy(reasoningMode = ProviderReasoning.DEEPSEEK) } },
						label = { Text("DeepSeek") },
					)
				}
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text("Request stream usage", style = MaterialTheme.typography.bodyMedium)
					WorkbenchSwitch(
						checked = draft.includeStreamUsage,
						onCheckedChange = { checked ->
							onUpdate { it.copy(includeStreamUsage = checked) }
						},
					)
				}
				OutlinedTextField(
					value = draft.manualModels,
					onValueChange = { value -> onUpdate { it.copy(manualModels = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text("Fallback model ids") },
					supportingText = {
						Text("One per line, used only if /models is unavailable.")
					},
					minLines = 2,
				)
				draft.status?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodySmall,
						color = if (draft.statusIsError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.primary
						},
					)
				}
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					TextButton(onClick = onTest, enabled = !draft.testing) {
						Text(if (draft.testing) "Checking…" else "Test connection")
					}
					if (!draft.isBuiltIn && !draft.isNew) {
						TextButton(onClick = onDelete) { Text("Delete") }
					}
				}
			}
		},
		confirmButton = {
			Button(onClick = onSave, shape = MaterialTheme.shapes.small) { Text("Save") }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
		containerColor = MaterialTheme.colorScheme.surface,
		titleContentColor = MaterialTheme.colorScheme.onSurface,
		textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	)
}

/**
 * The flight recorder, and the two things to do with it.
 *
 * Sharing is the point: a bug report is only diagnosable if the record can get
 * from this device to whoever reads it, and asking a user to run `adb` is not
 * that. Clearing is offered because the log is not immutable and a user may
 * want it gone -- but it is confirmed, because the one moment the record is
 * valuable is the moment just before someone decides it is in the way.
 */
@Composable
private fun DiagnosticsSection() {
	val context = LocalContext.current
	var bytes by remember { mutableStateOf(AppDiagnostics.sizeBytes()) }
	var confirmingClear by remember { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = "Diagnostics",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			// The promise matters as much as the feature: this is the
			// user's data, and a log is exactly where content leaks by
			// accident. The implementation keeps it to shapes, lengths and
			// identifiers; the sentence tells the user that.
			text = "A local log records turns, tool calls, HTTP failures and " +
				"crashes, so a problem can be explained without reproducing it. " +
				"It stays on this device unless you share it. Message text, file " +
				"contents and the API key are not written to it.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = "${formatLogSize(bytes)} — ${AppDiagnostics.currentFile()?.name ?: "no file yet"}",
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(
				onClick = { shareDiagnostics(context, bytes) },
				enabled = bytes > 0,
			) {
				Text("Share log")
			}
			TextButton(
				onClick = { confirmingClear = true },
				enabled = bytes > 0,
			) {
				Text("Clear")
			}
		}
	}

	if (confirmingClear) {
		AlertDialog(
			onDismissRequest = { confirmingClear = false },
			title = { Text("Clear the diagnostic log?") },
			text = {
				Text(
					"The record of what happened will be deleted. Share it first " +
						"if you are about to report a problem.",
				)
			},
			confirmButton = {
				TextButton(
					onClick = {
						AppDiagnostics.clear()
						bytes = AppDiagnostics.sizeBytes()
						confirmingClear = false
					},
				) {
					Text("Clear")
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmingClear = false }) {
					Text("Cancel")
				}
			},
		)
	}
}

private fun shareDiagnostics(context: Context, bytes: Long) {
	val file = AppDiagnostics.currentFile() ?: return
	Diag.i("diag", "share requested bytes=$bytes file=${file.name}")
	val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
	val send = Intent(Intent.ACTION_SEND).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_STREAM, uri)
		putExtra(Intent.EXTRA_SUBJECT, "inhuman diagnostics")
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	context.startActivity(Intent.createChooser(send, "Share log"))
}

private fun formatLogSize(bytes: Long): String = when {
	bytes <= 0L -> "no log yet"
	bytes < 1024L -> "$bytes B"
	bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
	else -> "${bytes / (1024L * 1024L)} MB"
}

/**
 * Which tool calls ask first, by default.
 *
 * ## Why the default is "run it" and why this screen still exists
 *
 * The agent can only see and change a conversation's own directory, and what the
 * user brings in is a **copy** -- the original never moves and is never in the
 * directory. So nothing inside is irreversible and nothing needs confirming by
 * default. But "by default" is not "always right for everyone": a user who wants
 * a second look before a file is replaced should get one, and this is where they
 * say so.
 *
 * The wording deliberately avoids "permission". Nothing here grants anything --
 * every tool is already reachable, and the strictest setting only takes a
 * capability away. Calling it a permission would suggest the opposite direction
 * is available, and `docs/08-port-philosophy.md` is where that is ruled out.
 */
@Composable
private fun ApprovalRulesSection(
	rules: Map<String, ToolPolicy>,
	onRuleChange: (String, ToolPolicy) -> Unit,
) {
	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		Text(
			text = "Before a tool runs",
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onBackground,
		)
		Text(
			text = "Local tools run without asking, because everything they reach " +
				"is a copy inside this conversation's own folder. SSH is different: " +
				"its first call in each conversation asks for consent, and after that " +
				"the policy below applies. Tighten any tool here if you would rather " +
				"be asked.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		PaidToolsNotice(modifier = Modifier.padding(vertical = 2.dp))

		for (entry in ToolCatalog.all) {
			val current = rules[entry.name] ?: ToolPolicy.Free
			ApprovalRuleRow(
				name = entry.name,
				blurb = entry.blurb,
				changesFiles = entry.mutates,
				paid = entry.paid,
				policy = current,
				onSelect = { onRuleChange(entry.name, it) },
			)
		}
	}
}

/**
 * One tool, and what it should do before running.
 *
 * A row of chips rather than a dropdown: there are four states and the whole set
 * fits, so the user can see that `Deny` exists without opening anything. A
 * dropdown would hide the two states they are least likely to think of --
 * refusing outright, and hiding a file from the agent entirely.
 *
 * `changesFiles` is shown because it is what the decision is actually about. A
 * read-only tool asking for approval protects nothing; the one that replaces a
 * file is where a second look has a cost worth paying.
 */
@Composable
private fun ApprovalRuleRow(
	name: String,
	blurb: String,
	changesFiles: Boolean,
	paid: Boolean,
	policy: ToolPolicy,
	onSelect: (ToolPolicy) -> Unit,
) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			Text(
				text = name,
				style = MaterialTheme.typography.bodyLarge,
				color = MaterialTheme.colorScheme.onBackground,
			)
			// Beside the name, where a reader scanning the list cannot miss it.
			if (paid) PaidBadge()
		}
		Text(
			text = if (changesFiles) "$blurb · changes files" else blurb,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			for (option in ToolPolicy.entries) {
				FilterChip(
					selected = option == policy,
					onClick = { onSelect(option) },
					label = { Text(option.label()) },
				)
			}
		}
	}
}

/**
 * The word a user reads, which is not the enum's name.
 *
 * Returns a `String` rather than wrapping `Text` so it stays an ordinary
 * function: a `@Composable` helper that only chooses a word is one the caller
 * cannot use anywhere else, for no benefit.
 */
private fun ToolPolicy.label(): String = when (this) {
	ToolPolicy.Free -> "run it"
	ToolPolicy.Ask -> "ask first"
	ToolPolicy.Deny -> "refuse"
	ToolPolicy.Blind -> "hide"
}
