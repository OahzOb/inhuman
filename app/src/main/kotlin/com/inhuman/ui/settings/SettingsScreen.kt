package com.inhuman.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.net.Uri
import android.os.LocaleList
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.AppCapabilities
import com.inhuman.ui.common.formatBytes
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import androidx.compose.foundation.text.selection.SelectionContainer
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.SectionHeading
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WideDialog
import com.inhuman.core.design.WorkbenchSwitch
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.backup.BACKUP_REMINDER_PERIODS
import com.inhuman.core.ai.provider.KnownProvider
import com.inhuman.core.ai.provider.ProviderCatalog
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.updates.UpdateInfo
import com.inhuman.ui.chat.ProviderDraft
import com.inhuman.ui.chat.SettingsViewModel
import com.inhuman.ui.chat.MediaCapability
import com.inhuman.ui.chat.MediaMode
import com.inhuman.ui.chat.MediaToolConfig
import java.text.DateFormat
import java.util.Date
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.toolBlurb
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
	onLayoutPreferenceChange: (com.inhuman.core.design.LayoutPreference) -> Unit = {},
	/** Open with the backup section expanded, for the home reminder's action. */
	startBackup: Boolean = false,
	onBackupStarted: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	LaunchedEffect(startBackup) {
		if (startBackup) onBackupStarted()
	}
	// The system owns the per-app locale. An empty list means the default,
	// which InhumanApp has already set to Chinese on first launch.
	val localeManager = context.getSystemService(LocaleManager::class.java)
	val appLanguage = localeManager?.applicationLocales
		?.takeUnless { it.isEmpty }
		?.get(0)
		?.language
		?: "zh"
	// The view model outlives this screen, but a turn can finish while Settings
	// is closed. Re-read on entry rather than trusting the last visit's numbers.
	LaunchedEffect(Unit) { viewModel.refreshUsage() }
	var showHostKeysDialog by remember { mutableStateOf(false) }
	var backupMode by remember { mutableStateOf<BackupMode?>(null) }
	var backupUri by remember { mutableStateOf<Uri?>(null) }
	var showBackupPassphrase by remember { mutableStateOf(false) }
	var showUndoConfirm by remember { mutableStateOf(false) }
	var showNotices by remember { mutableStateOf(false) }
	var backupPassphrase by remember { mutableStateOf("") }
	var backupPassphraseConfirm by remember { mutableStateOf("") }
	var showProviderPicker by remember { mutableStateOf(false) }

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
					title = { Text(stringResource(R.string.common_settings), style = MaterialTheme.typography.titleLarge) },
					navigationIcon = {
						IconButton(onClick = onBack) {
							Icon(
								Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = stringResource(R.string.common_back),
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
				// Settings is edge-to-edge; adjustResize does not shrink it.
				// Without this the IME covers the URL field at the bottom of
				// Local (found on the phone, reproduced on the emulator).
				.imePadding()
				.verticalScroll(rememberScrollState())
				.padding(horizontal = comfortableGutter(), vertical = 12.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
		) {
			Column(
				modifier = Modifier
					// Constrain first, fill second. `fillMaxWidth()` is greedy and
					// consumes the incoming maximum, so with these swapped the cap
					// was inert and the field stretched across 1280dp.
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
			) {
				SectionHeading(
					code = stringResource(R.string.settings_section_models),
					title = stringResource(R.string.settings_providers_title),
					detail = stringResource(R.string.settings_providers_detail),
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
				TextButton(onClick = { showProviderPicker = true }) {
					Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text(stringResource(R.string.settings_add_provider), style = MaterialTheme.typography.labelLarge)
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

			SettingsFold(
				code = stringResource(R.string.settings_section_web),
				title = stringResource(R.string.settings_web_title),
				summary = stringResource(R.string.settings_web_summary),
			) {
			Column(
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = stringResource(R.string.settings_web_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Eyebrow(stringResource(R.string.settings_search_backend))
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
				Eyebrow(stringResource(R.string.settings_fetch_backend))
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
					label = { Text(stringResource(R.string.settings_deepseek_web_key)) },
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (state.hasWebDeepSeekKey) {
						stringResource(R.string.settings_deepseek_web_key_saved)
					} else {
						stringResource(R.string.settings_deepseek_web_key_missing)
					},
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = state.webExaKeyInput,
					onValueChange = viewModel::onWebExaKeyChange,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text(stringResource(R.string.settings_exa_key)) },
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (state.hasWebExaKey) {
						stringResource(R.string.settings_exa_key_saved)
					} else {
						stringResource(R.string.settings_exa_key_missing)
					},
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				val missingSearch = state.webSearchBackend == "exa" && !state.hasWebExaKey ||
					state.webSearchBackend == "deepseek" && !state.hasWebDeepSeekKey
				val missingFetch = state.webFetchBackend == "exa" && !state.hasWebExaKey ||
					state.webFetchBackend == "deepseek" && !state.hasWebDeepSeekKey
				if (missingSearch || missingFetch) {
					Text(
						text = stringResource(R.string.settings_web_missing_key),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				Button(
					onClick = viewModel::saveWebTools,
					shape = MaterialTheme.shapes.small,
				) { Text(stringResource(R.string.settings_save_web_keys)) }
				Text(
					text = stringResource(R.string.settings_web_no_test),
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

				Hairline(Modifier.padding(vertical = 10.dp))
				Eyebrow(stringResource(R.string.settings_media_title))
				Text(
					text = stringResource(R.string.settings_media_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				MediaCapability.entries.forEach { capability ->
					MediaBackendRow(
						title = capability.title(),
						summary = mediaSummary(
							capability = capability,
							config = state.mediaConfigs[capability] ?: MediaToolConfig(),
							providers = state.providers,
						),
						onClick = { viewModel.openMedia(capability) },
					)
				}
				state.mediaStatus?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodyMedium,
						color = if (state.mediaStatusIsError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.primary
						},
					)
				}
			}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_runtime),
				title = stringResource(R.string.settings_runtime_title),
				summary = stringResource(R.string.settings_runtime_summary),
			) {
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
				verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_demo_title),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							// This string used to describe an approval prompt that
							// no longer appears, and it is read by a user rather
							// than by a compiler -- the kind of claim that goes
							// stale silently. Deletion does not ask by default
							// (docs/09-approvals.md); a rule can be configured to
							// make it, and the sentence says so rather than
							// promising a dialog nobody will see.
							text = stringResource(R.string.settings_demo_body),
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
				verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_ssh_title),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.settings_ssh_body),
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
							stringResource(R.string.settings_host_keys_none)
						} else {
							stringResource(R.string.settings_host_keys_count, state.knownHosts.size)
						},
					)
				}

				HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
				Text(
					text = stringResource(R.string.settings_ssh_limits_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = stringResource(R.string.settings_ssh_limits_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Eyebrow(stringResource(R.string.settings_ssh_command_timeout))
				FlowRowCompat {
					listOf(30, 60, 120, 300, 600).forEach { seconds ->
						FilterChip(
							selected = state.sshLimits.commandTimeoutCeilingSeconds == seconds,
							onClick = { viewModel.setSshCommandTimeoutSeconds(seconds) },
							label = { Text("${seconds}s") },
						)
					}
				}
				Eyebrow(stringResource(R.string.settings_ssh_output_cap))
				FlowRowCompat {
					listOf(5_000, 20_000, 50_000, 100_000).forEach { bytes ->
						FilterChip(
							selected = state.sshLimits.outputCeilingBytes == bytes,
							onClick = { viewModel.setSshOutputBytes(bytes) },
							label = { Text("${bytes / 1000}k") },
						)
					}
				}
				Eyebrow(stringResource(R.string.settings_ssh_list_cap))
				FlowRowCompat {
					listOf(100, 500, 1_000, 2_000).forEach { entries ->
						FilterChip(
							selected = state.sshLimits.listCeilingEntries == entries,
							onClick = { viewModel.setSshListEntries(entries) },
							label = { Text(entries.toString()) },
						)
					}
				}
				Eyebrow(stringResource(R.string.settings_ssh_read_cap))
				FlowRowCompat {
					listOf(16, 64, 128, 256).forEach { kib ->
						val bytes = kib * 1024
						FilterChip(
							selected = state.sshLimits.readCeilingBytes == bytes.toLong(),
							onClick = { viewModel.setSshReadBytes(bytes) },
							label = { Text("${kib}K") },
						)
					}
				}
			}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_limits),
				title = stringResource(R.string.settings_limits_title),
				summary = stringResource(
					R.string.settings_limits_summary,
					state.toolRoundLimit,
					state.toolCallLimit,
				),
			) {
				Text(
					text = stringResource(R.string.settings_limits_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Eyebrow(stringResource(R.string.settings_rounds_per_turn))
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
					text = stringResource(R.string.settings_rounds_current, state.toolRoundLimit),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Eyebrow(stringResource(R.string.settings_calls_label))
				FlowRowCompat {
					listOf(1, 4, 8, 16, 32, 64, 128, 256).forEach { calls ->
						FilterChip(
							selected = state.toolCallLimit == calls,
							onClick = { viewModel.setToolCallLimit(calls) },
							label = { Text("$calls") },
						)
					}
				}
				Text(
					text = stringResource(R.string.settings_calls_current, state.toolCallLimit),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			UsageSection(
				report = state.usage,
				period = state.usagePeriod,
				failed = state.usageFailed,
				onPeriodChange = viewModel::setUsagePeriod,
				onRetry = viewModel::refreshUsage,
			)

			SettingsFold(
				code = stringResource(R.string.settings_section_backup),
				title = stringResource(R.string.settings_backup_title),
				summary = stringResource(R.string.settings_backup_summary),
				initiallyExpanded = startBackup,
			) {
			Text(
				text = stringResource(R.string.settings_backup_body),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			FlowRowCompat {
				OutlinedButton(
					onClick = { exportLauncher.launch("inhuman-backup") },
					enabled = !state.backupBusy,
					shape = MaterialTheme.shapes.small,
				) { Text(stringResource(R.string.settings_export_backup)) }
				OutlinedButton(
					onClick = { importLauncher.launch(arrayOf("*/*")) },
					enabled = !state.backupBusy,
					shape = MaterialTheme.shapes.small,
				) { Text(stringResource(R.string.settings_import_backup)) }
				if (state.backupCanUndo) {
					OutlinedButton(
						onClick = { showUndoConfirm = true },
						enabled = !state.backupBusy,
						shape = MaterialTheme.shapes.small,
					) { Text(stringResource(R.string.settings_undo_import)) }
				}
			}
			Text(
				text = stringResource(R.string.settings_backup_explain),
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)

			HorizontalDivider(
				modifier = Modifier.padding(vertical = 6.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)
			Eyebrow(stringResource(R.string.settings_backup_reminder))
			Text(
				text = if (state.backupLastExportAt <= 0L) {
					stringResource(R.string.settings_backup_never)
				} else {
					stringResource(
						R.string.settings_backup_last,
						DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
							.format(Date(state.backupLastExportAt)),
					)
				},
				style = MaterialTheme.typography.bodyMedium,
				color = MaterialTheme.colorScheme.onSurface,
			)
			FlowRowCompat {
				BACKUP_REMINDER_PERIODS.forEach { days ->
					FilterChip(
						selected = state.backupReminderDays == days,
						onClick = { viewModel.setBackupReminderDays(days) },
						label = {
							Text(
								if (days == 0) {
									stringResource(R.string.settings_backup_reminder_off)
								} else {
									stringResource(R.string.settings_backup_reminder_days, days)
								},
							)
						},
					)
				}
			}
			Text(
				text = stringResource(R.string.settings_backup_reminder_note),
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
				) { Text(stringResource(R.string.settings_restart_now)) }
			}
			if (state.backupBusy) {
				Text(
					text = stringResource(R.string.settings_working),
					style = MaterialTheme.typography.labelMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			}

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

			SettingsFold(
				code = stringResource(R.string.settings_section_approvals),
				title = stringResource(R.string.settings_approvals_title),
				summary = if (state.approvalRules.isEmpty()) {
					stringResource(R.string.settings_approvals_none)
				} else {
					pluralStringResource(
						R.plurals.settings_approvals_count,
						state.approvalRules.size,
						state.approvalRules.size,
					)
				},
			) {
				ApprovalRulesSection(
					rules = state.approvalRules,
					sshToolEnabled = state.sshToolEnabled,
					onRuleChange = viewModel::setApprovalRule,
				)
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_diagnostics),
				title = stringResource(R.string.settings_diagnostics_title),
				summary = stringResource(R.string.settings_diagnostics_summary),
			) {
				DiagnosticsSection(
					uploading = state.logUploading,
					status = state.logUploadStatus,
					statusIsError = state.logUploadStatusIsError,
					onUploadLog = viewModel::uploadLog,
				)
				OutlinedButton(
					onClick = { showNotices = true },
					shape = MaterialTheme.shapes.small,
				) {
					Text(stringResource(R.string.settings_third_party_notices))
				}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_local),
				title = stringResource(R.string.settings_local_title),
				summary = stringResource(R.string.settings_local_summary),
			) {
				LocalNetworkSection(
					serverUrl = state.localServerUrl,
					currentVersionName = state.currentVersionName,
					currentVersionCode = state.currentVersionCode,
					checking = state.updateChecking,
					downloading = state.updateDownloading,
					progress = state.updateProgress,
					info = state.updateInfo,
					readyToInstall = state.updateReadyToInstall,
					status = state.updateStatus,
					statusIsError = state.updateStatusIsError,
					onServerUrlChange = viewModel::onLocalServerUrlChange,
					onCheck = viewModel::checkForUpdates,
					onDownload = viewModel::downloadUpdate,
					onInstall = viewModel::installUpdate,
				)
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_display),
				title = stringResource(R.string.settings_display_title),
				summary = when (state.layoutPreference) {
					com.inhuman.core.design.LayoutPreference.Auto ->
						stringResource(R.string.settings_layout_auto)
					com.inhuman.core.design.LayoutPreference.Compact ->
						stringResource(R.string.settings_layout_compact)
					com.inhuman.core.design.LayoutPreference.Comfortable ->
						stringResource(R.string.settings_layout_comfortable)
				},
			) {
				Text(
					text = stringResource(R.string.settings_layout_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					com.inhuman.core.design.LayoutPreference.entries.forEach { preference ->
						FilterChip(
							selected = state.layoutPreference == preference,
							onClick = {
								viewModel.setLayoutPreference(preference)
								onLayoutPreferenceChange(preference)
							},
							label = { Text(preference.name) },
						)
					}
				}
				HorizontalDivider(
					modifier = Modifier.padding(vertical = 8.dp),
					color = MaterialTheme.colorScheme.outlineVariant,
				)
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_advice_title),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.settings_advice_body),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.adviceEnabled,
						onCheckedChange = viewModel::setAdviceEnabled,
						modifier = Modifier.padding(start = 12.dp),
					)
				}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_language),
				title = stringResource(R.string.settings_language_title),
				summary = if (appLanguage == "en") {
					stringResource(R.string.settings_language_english)
				} else {
					stringResource(R.string.settings_language_chinese)
				},
			) {
				Text(
					text = stringResource(R.string.settings_language_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					FilterChip(
						selected = appLanguage != "en",
						onClick = { setAppLanguage(context, "zh-CN") },
						label = { Text(stringResource(R.string.settings_language_chinese)) },
					)
					FilterChip(
						selected = appLanguage == "en",
						onClick = { setAppLanguage(context, "en") },
						label = { Text(stringResource(R.string.settings_language_english)) },
					)
				}
			}
		}
	}

	if (showHostKeysDialog) {
		AlertDialog(
			onDismissRequest = { showHostKeysDialog = false },
			title = { Text(stringResource(R.string.settings_host_keys_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
					Text(
						stringResource(R.string.settings_host_keys_body),
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
				TextButton(onClick = { viewModel.forgetKnownHosts() }) { Text(stringResource(R.string.settings_forget_all)) }
			},
			dismissButton = {
				TextButton(onClick = { showHostKeysDialog = false }) { Text(stringResource(R.string.settings_close)) }
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
				Text(
					stringResource(
						if (mode == BackupMode.Export) {
							R.string.settings_encrypt_backup
						} else {
							R.string.settings_inspect_backup
						},
					),
				)
			},
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages)) {
					Text(
						text = if (mode == BackupMode.Export) {
							stringResource(R.string.settings_export_passphrase_body)
						} else {
							stringResource(R.string.settings_import_passphrase_body)
						},
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					OutlinedTextField(
						value = backupPassphrase,
						onValueChange = { backupPassphrase = it },
						label = { Text(stringResource(R.string.settings_passphrase)) },
						singleLine = true,
						visualTransformation = PasswordVisualTransformation(),
						modifier = Modifier.fillMaxWidth(),
					)
					if (mode == BackupMode.Export) {
						OutlinedTextField(
							value = backupPassphraseConfirm,
							onValueChange = { backupPassphraseConfirm = it },
							label = { Text(stringResource(R.string.settings_confirm_passphrase)) },
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
				) {
					Text(
						stringResource(
							if (mode == BackupMode.Export) {
								R.string.settings_export
							} else {
								R.string.settings_inspect
							},
						),
					)
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						showBackupPassphrase = false
						backupPassphrase = ""
						backupPassphraseConfirm = ""
					},
				) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	state.backupPreview?.let { preview ->
		AlertDialog(
			onDismissRequest = viewModel::cancelImport,
			title = { Text(stringResource(R.string.settings_import_title)) },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					val separator = stringResource(R.string.settings_list_separator)
					val summary = pluralStringResource(
						R.plurals.settings_backup_conversations,
						preview.conversations,
						preview.conversations,
					) + separator + pluralStringResource(
						R.plurals.settings_backup_messages,
						preview.messages,
						preview.messages,
					) + separator + pluralStringResource(
						R.plurals.settings_backup_workspaces,
						preview.workspaces,
						preview.workspaces,
					) + stringResource(R.string.settings_sentence_end)
					Text(
						text = summary,
						style = MaterialTheme.typography.bodyLarge,
					)
					preview.createdAt?.let { createdAt ->
						Text(
							text = stringResource(
								R.string.settings_exported_at,
								DateFormat.getDateTimeInstance(
									DateFormat.MEDIUM,
									DateFormat.SHORT,
								).format(Date(createdAt)),
							),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					Text(
						text = stringResource(R.string.settings_import_version, preview.databaseVersion),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
			confirmButton = {
				Button(onClick = viewModel::confirmImport) { Text(stringResource(R.string.settings_import)) }
			},
			dismissButton = {
				TextButton(onClick = viewModel::cancelImport) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showUndoConfirm) {
		AlertDialog(
			onDismissRequest = { showUndoConfirm = false },
			title = { Text(stringResource(R.string.settings_undo_title)) },
			text = {
				Text(stringResource(R.string.settings_undo_body))
			},
			confirmButton = {
				Button(
					onClick = {
						showUndoConfirm = false
						viewModel.undoLastImport()
					},
				) { Text(stringResource(R.string.settings_undo_action)) }
			},
			dismissButton = {
				TextButton(onClick = { showUndoConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showNotices) {
		val notices = remember {
			runCatching {
				context.assets.open("THIRD-PARTY-NOTICES.md")
					.bufferedReader()
					.use { it.readText() }
			}.getOrElse { "" }
		}
		WideDialog(
			onDismissRequest = { showNotices = false },
			title = stringResource(R.string.settings_third_party_notices),
			dismissLabel = stringResource(R.string.settings_close),
			// The licence is longer than a screen. 320dp leaves room for the
			// title, the close row and the system bars on a 640dp phone; a
			// larger body pushed the only way out of the dialog off the bottom.
			maxBodyHeight = 320.dp,
		) {
			SelectionContainer {
				Text(
					text = notices.ifBlank { stringResource(R.string.settings_third_party_missing) },
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}

	if (showProviderPicker) {
		ProviderPickerDialog(
			onPick = { provider ->
				showProviderPicker = false
				viewModel.addProvider(provider)
			},
			onDismiss = { showProviderPicker = false },
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

	state.mediaDraft?.let { draft ->
		MediaToolDialog(
			draft = draft,
			providers = state.providers,
			onMode = viewModel::setMediaMode,
			onProvider = viewModel::setMediaProvider,
			onModel = viewModel::setMediaModel,
			onModelPicker = viewModel::setMediaModelPicker,
			onBaseUrl = viewModel::setMediaBaseUrl,
			onApiKey = viewModel::setMediaKeyInput,
			onSave = viewModel::saveMedia,
			onClose = viewModel::closeMedia,
		)
	}
}

private enum class BackupMode { Export, Import }

@Composable
private fun MediaBackendRow(title: String, summary: String, onClick: () -> Unit) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(vertical = 10.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(2.dp),
		) {
			Text(text = title, style = MaterialTheme.typography.titleSmall)
			Text(
				text = summary,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
		Icon(
			imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun mediaSummary(
	capability: MediaCapability,
	config: MediaToolConfig,
	providers: List<ProviderProfileEntity>,
): String {
	val model = config.model.ifBlank { capability.defaultModel }
	return when (config.mode) {
		MediaMode.Assistant -> if (config.model.isBlank()) {
			stringResource(R.string.settings_media_summary_assistant)
		} else {
			stringResource(
				R.string.settings_media_summary_assistant_model,
				config.model,
			)
		}
		MediaMode.Provider -> {
			val provider = providers.firstOrNull { it.id == config.providerId }
			if (provider == null) {
				stringResource(R.string.settings_media_summary_missing)
			} else {
				stringResource(
					R.string.settings_media_summary_provider,
					provider.name,
					model,
				)
			}
		}
		MediaMode.Direct -> stringResource(
			R.string.settings_media_summary_direct,
			Uri.parse(config.baseUrl).host ?: config.baseUrl,
		)
	}
}

@Composable
private fun MediaCapability.title(): String = when (this) {
	MediaCapability.Image -> stringResource(R.string.settings_media_image)
	MediaCapability.Video -> stringResource(R.string.settings_media_video)
	MediaCapability.Speech -> stringResource(R.string.settings_media_speech)
	MediaCapability.Transcription -> stringResource(R.string.settings_media_transcribe)
	MediaCapability.Decision -> stringResource(R.string.settings_media_decision)
}

@Composable
private fun ProviderPickerDialog(
	onPick: (KnownProvider?) -> Unit,
	onDismiss: () -> Unit,
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_choose_provider)) },
		text = {
			LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
				item {
					TextButton(
						onClick = { onPick(null) },
						modifier = Modifier.fillMaxWidth(),
					) {
						Column(modifier = Modifier.fillMaxWidth()) {
							Text(
								stringResource(R.string.settings_custom_provider),
								style = MaterialTheme.typography.bodyLarge,
							)
							Text(
								stringResource(R.string.settings_custom_provider_detail),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
				items(ProviderCatalog.known, key = { it.id }) { provider ->
					TextButton(
						onClick = { onPick(provider) },
						modifier = Modifier.fillMaxWidth(),
					) {
						Column(modifier = Modifier.fillMaxWidth()) {
							Text(
								provider.name,
								style = MaterialTheme.typography.bodyLarge,
							)
							Text(
								stringResource(R.string.settings_known_provider_detail),
								style = MaterialTheme.typography.bodySmall,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
		},
		confirmButton = {},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

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
			if (hasKey) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
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
			text = if (hasKey) {
				stringResource(R.string.settings_key)
			} else {
				stringResource(R.string.settings_no_key)
			},
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
		title = {
			Text(
				if (draft.isNew) {
					stringResource(R.string.settings_add_provider_title)
				} else {
					draft.name
				},
			)
		},
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
					label = { Text(stringResource(R.string.common_name)) },
					singleLine = true,
				)
				if (draft.isPreset) {
					Text(
						stringResource(R.string.settings_provider_preset_summary),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						stringResource(R.string.settings_provider_endpoint, draft.baseUrl),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Text(
						stringResource(R.string.settings_provider_login_note),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					TextButton(onClick = { onUpdate { it.copy(isPreset = false) } }) {
						Text(stringResource(R.string.settings_provider_customize))
					}
				}
				if (!draft.isPreset) {
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
						selected = draft.kind == ProviderKinds.OPENAI_COMPATIBLE ||
							draft.kind == ProviderFactory.OPENAI_CHAT,
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
					FilterChip(
						selected = draft.kind == ProviderKinds.ANTHROPIC_MESSAGES,
						onClick = {
							onUpdate {
								it.copy(
									kind = ProviderKinds.ANTHROPIC_MESSAGES,
									baseUrl = if (it.isNew) "https://api.anthropic.com" else it.baseUrl,
									reasoningMode = ProviderReasoning.NONE,
								)
							}
						},
						label = { Text("Anthropic Messages") },
					)
					FilterChip(
						selected = draft.kind == ProviderKinds.GOOGLE_GENERATIVE_AI,
						onClick = {
							onUpdate {
								it.copy(
									kind = ProviderKinds.GOOGLE_GENERATIVE_AI,
									baseUrl = if (it.isNew) {
										"https://generativelanguage.googleapis.com/v1beta"
									} else {
										it.baseUrl
									},
									reasoningMode = ProviderReasoning.NONE,
								)
							}
						},
						label = { Text("Google Gemini") },
					)
				}
				OutlinedTextField(
					value = draft.baseUrl,
					onValueChange = { value -> onUpdate { it.copy(baseUrl = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_base_url)) },
					supportingText = { Text(stringResource(R.string.settings_base_url_support)) },
					singleLine = true,
				)
				}
				OutlinedTextField(
					value = draft.keyInput,
					onValueChange = { value ->
						onUpdate { it.copy(keyInput = value, hasKey = value.isNotBlank()) }
					},
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_api_key)) },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = if (draft.hasKey) {
						stringResource(R.string.settings_key_stored)
					} else {
						stringResource(R.string.settings_no_key_stored)
					},
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (!draft.isPreset &&
					(draft.kind == ProviderKinds.OPENAI_COMPATIBLE ||
						draft.kind == ProviderKinds.DEEPSEEK ||
						draft.kind == ProviderFactory.OPENAI_CHAT)
				) {
					Text(
						stringResource(R.string.settings_reasoning_fields),
						style = MaterialTheme.typography.labelLarge,
					)
					FlowRowCompat {
						FilterChip(
							selected = draft.reasoningMode == ProviderReasoning.NONE,
							onClick = { onUpdate { it.copy(reasoningMode = ProviderReasoning.NONE) } },
							label = { Text(stringResource(R.string.settings_reasoning_none)) },
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
						Text(
							stringResource(R.string.settings_request_stream_usage),
							style = MaterialTheme.typography.bodyMedium,
						)
						WorkbenchSwitch(
							checked = draft.includeStreamUsage,
							onCheckedChange = { checked ->
								onUpdate { it.copy(includeStreamUsage = checked) }
							},
						)
					}
				}
				if (!draft.isPreset) {
					OutlinedTextField(
						value = draft.manualModels,
						onValueChange = { value -> onUpdate { it.copy(manualModels = value) } },
						modifier = Modifier.fillMaxWidth(),
						label = { Text(stringResource(R.string.settings_fallback_models)) },
						supportingText = {
							Text(stringResource(R.string.settings_fallback_models_support))
						},
						minLines = 2,
					)
				}
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
						Text(
							if (draft.testing) {
								stringResource(R.string.settings_checking)
							} else {
								stringResource(R.string.settings_test_connection)
							},
						)
					}
					if (!draft.isBuiltIn && !draft.isNew) {
						TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
					}
				}
			}
		},
		confirmButton = {
			Button(onClick = onSave, shape = MaterialTheme.shapes.small) { Text(stringResource(R.string.common_save)) }
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
/**
 * One foldable settings group.
 *
 * Settings grew into a single multi-screen scroll: provider keys, web keys,
 * demo/SSH switches, turn limits, backup and every tool policy at once. The
 * groups keep the same content but let the user open one concern at a time.
 * `rememberSaveable` preserves the open group across rotation; the state is a
 * reading preference, not an edit.
 */
@Composable
internal fun SettingsFold(
	code: String,
	title: String,
	summary: String,
	initiallyExpanded: Boolean = false,
	content: @Composable ColumnScope.() -> Unit,
) {
	var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clickable { expanded = !expanded }
				.padding(vertical = 10.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(
				modifier = Modifier.weight(1f),
				verticalArrangement = Arrangement.spacedBy(2.dp),
			) {
				Eyebrow(code)
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = summary,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 2,
				)
			}
			Icon(
				imageVector = if (expanded) {
					Icons.Filled.KeyboardArrowUp
				} else {
					Icons.Filled.KeyboardArrowDown
				},
				contentDescription = stringResource(
					if (expanded) R.string.settings_collapse else R.string.settings_expand,
					title,
				),
				tint = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		if (expanded) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(bottom = 10.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
				content = content,
			)
		}
		Hairline()
	}
}

@Composable
private fun LocalNetworkSection(
	serverUrl: String,
	currentVersionName: String,
	currentVersionCode: Long,
	checking: Boolean,
	downloading: Boolean,
	progress: Int?,
	info: UpdateInfo?,
	readyToInstall: Boolean,
	status: String?,
	statusIsError: Boolean,
	onServerUrlChange: (String) -> Unit,
	onCheck: () -> Unit,
	onDownload: () -> Unit,
	onInstall: () -> Unit,
) {
	val focus = LocalFocusManager.current
	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = stringResource(R.string.settings_local_body),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		OutlinedTextField(
			value = serverUrl,
			onValueChange = onServerUrlChange,
			label = { Text(stringResource(R.string.settings_local_url)) },
			placeholder = { Text(stringResource(R.string.settings_local_url_hint)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		Text(
			text = stringResource(
				R.string.settings_update_current,
				currentVersionName,
				currentVersionCode.toString(),
			),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		if (info != null) {
			if (info.notes.isNotBlank()) {
				Text(
					text = stringResource(R.string.settings_update_notes),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					text = info.notes,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			TextButton(
				onClick = {
					focus.clearFocus()
					onCheck()
				},
				enabled = !checking && !downloading,
			) {
				Text(
					if (checking) {
						stringResource(R.string.settings_checking)
					} else {
						stringResource(R.string.settings_update_check)
					},
				)
			}
			if (info != null && !readyToInstall) {
				Button(
					onClick = {
						focus.clearFocus()
						onDownload()
					},
					enabled = !downloading,
				) {
					Text(
						if (downloading) {
							stringResource(
								R.string.settings_update_downloading,
								progress ?: 0,
							)
						} else {
							stringResource(R.string.settings_update_download)
						},
					)
				}
			}
			if (readyToInstall) {
				Button(
					onClick = {
						focus.clearFocus()
						onInstall()
					},
				) {
					Text(stringResource(R.string.settings_update_install))
				}
			}
		}

		status?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = if (statusIsError) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
		}
	}
}

@Composable
private fun DiagnosticsSection(
	uploading: Boolean,
	status: String?,
	statusIsError: Boolean,
	onUploadLog: () -> Unit,
) {
	val context = LocalContext.current
	var bytes by remember { mutableStateOf(AppDiagnostics.sizeBytes()) }
	var confirmingClear by remember { mutableStateOf(false) }
	val shareLog = stringResource(R.string.settings_share_log)
	val shareSubject = stringResource(R.string.settings_share_subject)

	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		Text(
			// The promise matters as much as the feature: this is the
			// user's data, and a log is exactly where content leaks by
			// accident. The implementation keeps it to shapes, lengths and
			// identifiers; the sentence tells the user that.
			text = stringResource(R.string.settings_diagnostics_body),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = stringResource(
				R.string.settings_diagnostics_file,
				formatLogSize(bytes),
				AppDiagnostics.currentFile()?.name
					?: stringResource(R.string.settings_no_file_yet),
			),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(
				onClick = { shareDiagnostics(context, bytes, shareLog, shareSubject) },
				enabled = bytes > 0,
			) {
				Text(shareLog)
			}
			TextButton(
				onClick = onUploadLog,
				enabled = bytes > 0 && !uploading,
			) {
				Text(
					if (uploading) {
						stringResource(R.string.settings_log_uploading)
					} else {
						stringResource(R.string.settings_log_upload)
					},
				)
			}
			TextButton(
				onClick = { confirmingClear = true },
				enabled = bytes > 0,
			) {
				Text(stringResource(R.string.settings_clear))
			}
		}
		status?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = if (statusIsError) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
		}
	}

	if (confirmingClear) {
		AlertDialog(
			onDismissRequest = { confirmingClear = false },
			title = { Text(stringResource(R.string.settings_clear_log_title)) },
			text = {
				Text(stringResource(R.string.settings_clear_log_body))
			},
			confirmButton = {
				TextButton(
					onClick = {
						AppDiagnostics.clear()
						bytes = AppDiagnostics.sizeBytes()
						confirmingClear = false
					},
				) {
					Text(stringResource(R.string.settings_clear))
				}
			},
			dismissButton = {
				TextButton(onClick = { confirmingClear = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
		)
	}
}

private fun shareDiagnostics(
	context: Context,
	bytes: Long,
	chooserTitle: String,
	subject: String,
) {
	val file = AppDiagnostics.currentFile() ?: return
	Diag.i("diag", "share requested bytes=$bytes file=${file.name}")
	val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
	val send = Intent(Intent.ACTION_SEND).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_STREAM, uri)
		putExtra(Intent.EXTRA_SUBJECT, subject)
		addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
	}
	context.startActivity(Intent.createChooser(send, chooserTitle))
}

@Composable
private fun formatLogSize(bytes: Long): String = when {
	bytes <= 0L -> stringResource(R.string.settings_no_log)
	bytes < 1024L -> "$bytes B"
	bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
	else -> formatBytes(bytes)
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
	sshToolEnabled: Boolean,
	onRuleChange: (String, ToolPolicy) -> Unit,
) {
	Column(
		modifier = Modifier
			.widthIn(max = Layout.ReadableMeasure)
			.fillMaxWidth(),
		verticalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
	) {
		Text(
			text = if (sshToolEnabled) {
				stringResource(R.string.settings_approval_body_with_ssh)
			} else {
				stringResource(R.string.settings_approval_body)
			},
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		PaidToolsNotice(modifier = Modifier.padding(vertical = 2.dp))

		for (entry in ToolCatalog.visible(
			sshToolEnabled,
			includeWorkspaceBound = AppCapabilities.workspaceOk,
		)) {
			val current = rules[entry.name] ?: ToolPolicy.Free
			ApprovalRuleRow(
				name = entry.name,
				blurb = toolBlurb(entry),
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
	Column(verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed)) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(Layout.Space.InMessage),
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
			text = if (changesFiles) {
				stringResource(R.string.settings_policy_changes_files, blurb)
			} else {
				blurb
			},
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
 * A `@Composable` because the word now comes from a resource: resolving one
 * needs a composition, and that is the price of a translatable label.
 */
@Composable
private fun ToolPolicy.label(): String = when (this) {
	ToolPolicy.Free -> stringResource(R.string.settings_policy_free)
	ToolPolicy.Ask -> stringResource(R.string.settings_policy_ask)
	ToolPolicy.Deny -> stringResource(R.string.settings_policy_deny)
	ToolPolicy.Blind -> stringResource(R.string.settings_policy_blind)
}

/**
 * Set the system-owned per-app locale.
 *
 * The platform persists it and recreates the activity, so there is no app-side
 * preference to store or migrate. `LocaleManager` is API 33; the app's minSdk is
 * 34, which is why this needs no version guard. `InhumanApp` fills an empty list
 * with Chinese on first launch, so choosing English here is a real, persistent
 * choice rather than one that the next start overwrites.
 */
private fun setAppLanguage(context: Context, tag: String) {
	val manager = context.getSystemService(LocaleManager::class.java) ?: return
	manager.applicationLocales = LocaleList.forLanguageTags(tag)
}
