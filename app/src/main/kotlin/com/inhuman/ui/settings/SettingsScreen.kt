package com.inhuman.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.net.Uri
import android.os.LocaleList
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.AppCapabilities
import com.inhuman.ui.common.formatBytes
import com.inhuman.ui.common.ToolGroupPolicyRow
import com.inhuman.ui.common.groupPolicy
import com.inhuman.ui.common.toolGroups
import com.inhuman.R
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.web.LocalWebProviders
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.data.db.ProviderKinds
import com.inhuman.core.data.db.ProviderProfileEntity
import com.inhuman.core.data.db.ProviderReasoning
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import com.inhuman.core.design.Eyebrow
import com.inhuman.core.design.Hairline
import com.inhuman.core.design.Layout
import com.inhuman.core.design.SectionHeading
import com.inhuman.core.design.StatusDot
import com.inhuman.core.design.WideDialog
import com.inhuman.core.design.WorkbenchSwitch
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.common.PaidBadge
import com.inhuman.core.design.dialogBodyMaxHeight
import com.inhuman.ui.backup.BACKUP_REMINDER_PERIODS
import com.inhuman.core.ai.provider.KnownProvider
import com.inhuman.core.ai.provider.ProviderCatalog
import com.inhuman.core.ai.provider.ProviderFactory
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.updates.BUILT_IN_UPDATE_URLS
import com.inhuman.updates.UpdateInfo
import com.inhuman.core.ai.oauth.OAuthProtocol
import com.inhuman.ui.chat.ProviderDraft
import com.inhuman.ui.ssh.SshHostSummary
import com.inhuman.ui.chat.OAuthLoginUi
import com.inhuman.ui.chat.SettingsViewModel
import com.inhuman.ui.chat.MediaCapability
import com.inhuman.ui.chat.MediaMode
import com.inhuman.ui.chat.MediaToolConfig
import com.inhuman.ui.chat.TimeHintMode
import com.inhuman.ui.chat.TimeHintPolicy
import java.text.DateFormat
import java.util.Date
import com.inhuman.ui.common.FlowRowCompat
import com.inhuman.ui.common.toolBlurb
import com.inhuman.ui.common.PaidBadge
import com.inhuman.ui.common.PaidToolsNotice
import com.inhuman.ui.common.RemoteBadge
import com.inhuman.ui.common.RemoteToolsNotice

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
	/** Theme changes are applied at the composition root, beside the layout preference. */
	onThemePaletteChange: (com.inhuman.core.design.ThemePalette) -> Unit = {},
	onThemeModeChange: (com.inhuman.core.design.ThemeMode) -> Unit = {},
	onSurfaceFamilyChange: (com.inhuman.core.design.SurfaceFamily) -> Unit = {},
	/** Open with the backup section expanded, for the home reminder's action. */
	startBackup: Boolean = false,
	onBackupStarted: () -> Unit = {},
	/** An `inhuman://oauth` callback delivered by the activity. */
	oauthCallback: String? = null,
	onOAuthConsumed: () -> Unit = {},
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	LaunchedEffect(oauthCallback) {
		val callback = oauthCallback ?: return@LaunchedEffect
		viewModel.completeOAuth(callback)
		onOAuthConsumed()
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
	// is closed. The Usage report is large enough to be worth deferring: it is
	// requested when that section is opened, below, rather than on entry.
	var showHostKeysDialog by remember { mutableStateOf(false) }
	var showSshHostDialog by remember { mutableStateOf(false) }
	var editingSshHost by remember { mutableStateOf<com.inhuman.ui.ssh.SshHostSummary?>(null) }
	var backupMode by remember { mutableStateOf<BackupMode?>(null) }
	var backupUri by remember { mutableStateOf<Uri?>(null) }
	var showBackupPassphrase by remember { mutableStateOf(false) }
	var showUndoConfirm by remember { mutableStateOf(false) }
	var showNotices by remember { mutableStateOf(false) }
	var showSpeechVoiceDialog by remember { mutableStateOf(false) }
	var backupPassphrase by remember { mutableStateOf("") }
	var backupPassphraseConfirm by remember { mutableStateOf("") }
	LaunchedEffect(startBackup) {
		if (startBackup) {
			viewModel.refreshBackupFolder()
			val current = viewModel.state.value
			if (current.backupFolderUri != null && !current.backupFolderPermissionLost) {
				backupMode = BackupMode.ExportToFolder
				backupUri = null
				backupPassphrase = ""
				backupPassphraseConfirm = ""
				showBackupPassphrase = true
			}
			onBackupStarted()
		}
	}
	var showProviderPicker by remember { mutableStateOf(false) }
	// Clearing both web keys at once is the one key action here that is not tied
	// to the editor that owns a single key, so it is confirmed before it runs.
	var showClearWebKeysConfirm by remember { mutableStateOf(false) }
	var query by rememberSaveable { mutableStateOf("") }

	// The text a section is searched by. Built once per composition because
	// every string is localized; the list is also the answer to "did the
	// search match anything", so the empty state does not have to guess.
	fun matches(section: SettingsSectionText): Boolean =
		settingsSectionMatches(
			query = query,
			code = section.code,
			title = section.title,
			summary = section.summary,
			keywords = section.keywords,
		)
	val modelsText = SettingsSectionText(
		stringResource(R.string.settings_section_models),
		stringResource(R.string.settings_providers_title),
		stringResource(R.string.settings_providers_detail),
		stringResource(R.string.settings_keywords_models),
	)
	val webText = SettingsSectionText(
		stringResource(R.string.settings_section_web),
		stringResource(R.string.settings_web_title),
		stringResource(R.string.settings_web_summary),
		stringResource(R.string.settings_keywords_web),
	)
	val runtimeText = SettingsSectionText(
		stringResource(R.string.settings_section_runtime),
		stringResource(R.string.settings_runtime_title),
		stringResource(R.string.settings_runtime_summary),
		stringResource(R.string.settings_keywords_runtime),
	)
	val limitsText = SettingsSectionText(
		stringResource(R.string.settings_section_limits),
		stringResource(R.string.settings_limits_title),
		stringResource(R.string.settings_limits_body),
		stringResource(R.string.settings_keywords_limits),
	)
	val usageText = SettingsSectionText(
		stringResource(R.string.settings_section_usage),
		stringResource(R.string.settings_usage_title),
		stringResource(R.string.settings_usage_body),
		stringResource(R.string.settings_keywords_usage),
	)
	val backupText = SettingsSectionText(
		stringResource(R.string.settings_section_backup),
		stringResource(R.string.settings_backup_title),
		stringResource(R.string.settings_backup_summary),
		stringResource(R.string.settings_keywords_backup),
	)
	val approvalsText = SettingsSectionText(
		stringResource(R.string.settings_section_approvals),
		stringResource(R.string.settings_approvals_title),
		stringResource(R.string.settings_approvals_none),
		stringResource(R.string.settings_keywords_approvals),
	)
	val diagnosticsText = SettingsSectionText(
		stringResource(R.string.settings_section_diagnostics),
		stringResource(R.string.settings_diagnostics_title),
		stringResource(R.string.settings_diagnostics_summary),
		stringResource(R.string.settings_keywords_diagnostics),
	)
	val localText = SettingsSectionText(
		stringResource(R.string.settings_section_local),
		stringResource(R.string.settings_local_title),
		stringResource(R.string.settings_local_summary),
		stringResource(R.string.settings_keywords_local),
	)
	val displayText = SettingsSectionText(
		stringResource(R.string.settings_section_display),
		stringResource(R.string.settings_display_title),
		stringResource(R.string.settings_layout_body) + " " +
			stringResource(R.string.settings_theme_body) + " " +
			stringResource(R.string.settings_surface_body),
		stringResource(R.string.settings_keywords_display),
	)
	val languageText = SettingsSectionText(
		stringResource(R.string.settings_section_language),
		stringResource(R.string.settings_language_title),
		stringResource(R.string.settings_language_body),
		stringResource(R.string.settings_keywords_language),
	)
	val timeText = SettingsSectionText(
		stringResource(R.string.settings_section_time),
		stringResource(R.string.settings_time_title),
		stringResource(R.string.settings_time_body),
		stringResource(R.string.settings_keywords_time),
	)
	val sectionTexts = listOf(
		modelsText, webText, runtimeText, limitsText, usageText, backupText,
		approvalsText, diagnosticsText, localText, displayText, languageText,
		timeText,
	)
	val anySectionMatches = sectionTexts.any { matches(it) }

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
	val backupFolderLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocumentTree(),
	) { uri ->
		if (uri != null) {
			runCatching {
				context.contentResolver.takePersistableUriPermission(
					uri,
					Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
				)
			}
			viewModel.setBackupFolder(uri)
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
				Box(
					modifier = Modifier.fillMaxWidth(),
					contentAlignment = Alignment.TopCenter,
				) {
					OutlinedTextField(
						value = query,
						onValueChange = { query = it },
						modifier = Modifier
							.widthIn(max = Layout.ReadableMeasure)
							.fillMaxWidth()
							.padding(horizontal = comfortableGutter(), vertical = 6.dp),
						singleLine = true,
						shape = MaterialTheme.shapes.small,
						placeholder = { Text(stringResource(R.string.settings_search_placeholder)) },
						leadingIcon = {
							Icon(
								Icons.Outlined.Search,
								contentDescription = null,
								modifier = Modifier.size(18.dp),
							)
						},
						trailingIcon = if (query.isNotEmpty()) {
							{
								IconButton(onClick = { query = "" }) {
									Icon(
										Icons.Filled.Close,
										contentDescription = stringResource(R.string.settings_search_clear),
										modifier = Modifier.size(18.dp),
									)
								}
							}
						} else {
							null
						},
					)
				}
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
			if (query.isNotBlank()) {
				Text(
					text = if (anySectionMatches) {
						stringResource(R.string.settings_search_matching)
					} else {
						stringResource(R.string.settings_search_no_matches, query.trim())
					},
					style = MaterialTheme.typography.bodySmall,
					color = if (anySectionMatches) {
						MaterialTheme.colorScheme.onSurfaceVariant
					} else {
						MaterialTheme.colorScheme.error
					},
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
				)
			}
			if (matches(modelsText)) {
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
						onOAuth = if (provider.isBuiltIn) null else {
							{ viewModel.openOAuth(provider) }
						},
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
			}

			Hairline(Modifier.padding(vertical = 14.dp))

			SettingsFold(
				code = stringResource(R.string.settings_section_web),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(webText),
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
				Text(
					text = stringResource(R.string.settings_web_ocr_hint),
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
				if (state.hasWebDeepSeekKey || state.hasWebExaKey) {
					TextButton(onClick = { showClearWebKeysConfirm = true }) {
						Text(
							text = stringResource(R.string.settings_web_clear_keys),
							color = MaterialTheme.colorScheme.error,
						)
					}
				}
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

				// The app's own web tools: no key, no bill, two independent
				// switches. Both rows are opt-in on the assistant; these
				// switches only make the capability available at all.
				Hairline(Modifier.padding(vertical = 10.dp))
				Eyebrow(stringResource(R.string.settings_local_web_title))
				Text(
					text = stringResource(R.string.settings_local_web_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				SwitchRow(
					title = stringResource(R.string.settings_local_search_title),
					body = stringResource(R.string.settings_local_search_body),
					checked = state.localWebSearchEnabled,
					onCheckedChange = viewModel::setLocalWebSearchEnabled,
				)
				Eyebrow(stringResource(R.string.settings_local_engine))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					FilterChip(
						selected = state.localWebEngine == LocalWebProviders.ENGINE_DUCKDUCKGO,
						onClick = {
							viewModel.setLocalWebEngine(LocalWebProviders.ENGINE_DUCKDUCKGO)
						},
						label = { Text("DuckDuckGo") },
					)
					FilterChip(
						selected = state.localWebEngine == LocalWebProviders.ENGINE_BING,
						onClick = {
							viewModel.setLocalWebEngine(LocalWebProviders.ENGINE_BING)
						},
						label = { Text("Bing") },
					)
				}
				SwitchRow(
					title = stringResource(R.string.settings_local_fetch_title),
					body = stringResource(R.string.settings_local_fetch_body),
					checked = state.localWebFetchEnabled,
					onCheckedChange = viewModel::setLocalWebFetchEnabled,
				)

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

				// Reply read-aloud is part of the speech backend, but its
				// settings are used from the transcript rather than from a tool
				// call, so the voice and auto-read choice live here rather than
				// in the per-capability key dialog.
				Hairline(Modifier.padding(vertical = 10.dp))
				Eyebrow(stringResource(R.string.settings_speech_title))
				Text(
					text = stringResource(R.string.settings_speech_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 8.dp),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_speech_auto_read),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.settings_speech_auto_read_body),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.autoReadReplies,
						onCheckedChange = viewModel::setAutoReadReplies,
						modifier = Modifier.padding(start = 12.dp),
					)
				}
				MediaBackendRow(
					title = stringResource(R.string.settings_speech_voice),
					summary = if (state.speechVoice.isBlank()) {
						stringResource(R.string.settings_speech_voice_default)
					} else {
						state.speechVoice
					},
					onClick = { showSpeechVoiceDialog = true },
				)
				Text(
					text = stringResource(R.string.settings_speech_voice_hint),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				// Anonymous Bilibili reads. Unlike the paid backends above
				// there is no key to configure and no bill to disclose; what
				// needs saying is that the app itself now contacts a
				// third-party site on the assistant's behalf, anonymously.
				Hairline(Modifier.padding(vertical = 10.dp))
				Eyebrow(stringResource(R.string.settings_bilibili_title))
				Text(
					text = stringResource(R.string.settings_bilibili_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.bilibiliEnabled,
							role = Role.Switch,
							onValueChange = viewModel::setBilibiliEnabled,
						),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_bilibili_switch),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
					}
					WorkbenchSwitch(
						checked = state.bilibiliEnabled,
						onCheckedChange = null,
						modifier = Modifier.padding(start = 12.dp),
					)
				}

				// Optional read-only login. It is the only way to read what a
				// video says, and the only thing that can page comments past the
				// first; the anonymous rows above never depend on it.
				Hairline(Modifier.padding(vertical = 6.dp))
				Text(
					text = if (state.bilibiliLoggedIn) {
						stringResource(R.string.settings_bilibili_login_linked, state.bilibiliLoginName)
					} else {
						stringResource(R.string.settings_bilibili_login_unlinked)
					},
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = stringResource(R.string.settings_bilibili_login_body),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					if (state.bilibiliLoggedIn) {
						OutlinedButton(
							onClick = viewModel::unlinkBilibili,
							shape = MaterialTheme.shapes.small,
						) { Text(stringResource(R.string.settings_bilibili_login_unlink)) }
					} else {
						Button(
							onClick = viewModel::beginBilibiliLogin,
							enabled = state.bilibiliEnabled && !state.bilibiliLoginRunning,
							shape = MaterialTheme.shapes.small,
						) { Text(stringResource(R.string.settings_bilibili_login_scan)) }
					}
				}
				state.bilibiliLoginStatus
					?.takeIf { state.bilibiliLoginQr == null }
					?.let { status ->
						Text(
							text = status,
							style = MaterialTheme.typography.labelSmall,
							color = if (state.bilibiliLoginStatusIsError) {
								MaterialTheme.colorScheme.error
							} else {
								MaterialTheme.colorScheme.primary
							},
						)
					}

				state.bilibiliLoginQr?.let { qr ->
					AlertDialog(
						onDismissRequest = viewModel::cancelBilibiliLogin,
						title = { Text(stringResource(R.string.settings_bilibili_login_title)) },
						text = {
							Column(
								modifier = Modifier
									.heightIn(max = dialogBodyMaxHeight(320.dp))
									.verticalScroll(rememberScrollState()),
								horizontalAlignment = Alignment.CenterHorizontally,
								verticalArrangement = Arrangement.spacedBy(10.dp),
							) {
								Image(
									bitmap = qr.asImageBitmap(),
									contentDescription = stringResource(R.string.settings_bilibili_login_title),
									modifier = Modifier.size(220.dp),
								)
								Text(
									text = state.bilibiliLoginStatus
										?: stringResource(R.string.settings_bilibili_login_waiting),
									style = MaterialTheme.typography.bodySmall,
									color = if (state.bilibiliLoginStatusIsError) {
										MaterialTheme.colorScheme.error
									} else {
										MaterialTheme.colorScheme.onSurfaceVariant
									},
								)
								Text(
									text = stringResource(R.string.settings_bilibili_login_hint),
									style = MaterialTheme.typography.labelSmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						},
						confirmButton = {
							TextButton(onClick = viewModel::cancelBilibiliLogin) {
								Text(stringResource(R.string.settings_bilibili_login_close))
							}
						},
					)
				}
			}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_runtime),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(runtimeText),
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
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.demoMode,
							role = Role.Switch,
							onValueChange = viewModel::setDemoMode,
						),
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
						onCheckedChange = null,
						modifier = Modifier.padding(top = 2.dp),
					)
				}
			}

			HorizontalDivider(
				modifier = Modifier.padding(vertical = 8.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)

			// URL downloads. A second outbound path, so the copy says the two
			// things that matter: the app makes the request (the assistant has
			// no network), and a URL is not inert -- anything encoded into one
			// leaves the device when it is fetched.
			Column(
				modifier = Modifier
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
			) {
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.downloadEnabled,
							role = Role.Switch,
							onValueChange = viewModel::setDownloadEnabled,
						),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_download_title),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.settings_download_body),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.downloadEnabled,
						onCheckedChange = null,
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
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.sshToolEnabled,
							role = Role.Switch,
							onValueChange = viewModel::setSshToolEnabled,
						),
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
						onCheckedChange = null,
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
					text = stringResource(R.string.settings_ssh_hosts_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = stringResource(R.string.settings_ssh_hosts_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (state.sshHosts.isEmpty()) {
					Text(
						text = stringResource(R.string.settings_ssh_hosts_none),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				state.sshHosts.forEach { host ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clickable {
								editingSshHost = host
								showSshHostDialog = true
							}
							.padding(vertical = 8.dp),
						horizontalArrangement = Arrangement.SpaceBetween,
						verticalAlignment = Alignment.CenterVertically,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = host.label,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onBackground,
							)
							host.hostKeyFingerprint?.takeIf { it.isNotBlank() }?.let { pinned ->
								Text(
									text = stringResource(R.string.settings_ssh_host_pinned, pinned),
									style = MaterialTheme.typography.bodySmall,
									color = MaterialTheme.colorScheme.onSurfaceVariant,
								)
							}
						}
					}
				}
				TextButton(
					onClick = {
						editingSshHost = null
						showSshHostDialog = true
					},
				) {
					Text(stringResource(R.string.settings_ssh_host_add))
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
							label = { Text("${bytes / 1000} kB") },
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
							label = { Text("$kib KiB") },
						)
					}
				}
			}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_limits),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(limitsText),
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
				loading = state.usageLoading,
				initiallyExpanded = query.isNotBlank(),
				visible = matches(usageText),
				onPeriodChange = viewModel::setUsagePeriod,
				onRetry = viewModel::refreshUsage,
				onExpand = viewModel::refreshUsage,
			)

			SettingsFold(
				code = stringResource(R.string.settings_section_backup),
				title = stringResource(R.string.settings_backup_title),
				summary = stringResource(R.string.settings_backup_summary),
				initiallyExpanded = startBackup || query.isNotBlank(),
				visible = matches(backupText),
				onExpand = viewModel::refreshBackupFolder,
			) {
			Text(
				text = stringResource(R.string.settings_backup_body),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			HorizontalDivider(
				modifier = Modifier.padding(vertical = 6.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)
			Eyebrow(stringResource(R.string.settings_backup_folder_heading))
			if (state.backupFolderUri == null) {
				Text(
					text = stringResource(R.string.settings_backup_folder_unset),
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					OutlinedButton(
						onClick = { backupFolderLauncher.launch(null) },
						enabled = !state.backupBusy,
						shape = MaterialTheme.shapes.small,
					) { Text(stringResource(R.string.settings_backup_choose_folder)) }
				}
			} else {
				Text(
					text = stringResource(
						R.string.settings_backup_folder_current,
						state.backupFolderName ?: state.backupFolderUri.orEmpty(),
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurface,
				)
				if (state.backupFolderPermissionLost) {
					Text(
						text = stringResource(R.string.settings_backup_folder_lost),
						modifier = Modifier
							.widthIn(max = Layout.ReadableMeasure)
							.fillMaxWidth(),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.error,
					)
				}
				FlowRowCompat {
					if (!state.backupFolderPermissionLost) {
						Button(
							onClick = {
								backupMode = BackupMode.ExportToFolder
								backupUri = null
								backupPassphrase = ""
								backupPassphraseConfirm = ""
								showBackupPassphrase = true
							},
							enabled = !state.backupBusy,
							shape = MaterialTheme.shapes.small,
						) { Text(stringResource(R.string.settings_backup_to_folder)) }
					}
					OutlinedButton(
						onClick = { backupFolderLauncher.launch(null) },
						enabled = !state.backupBusy,
						shape = MaterialTheme.shapes.small,
					) { Text(stringResource(R.string.settings_backup_change_folder)) }
					TextButton(
						onClick = viewModel::clearBackupFolder,
						enabled = !state.backupBusy,
						shape = MaterialTheme.shapes.small,
					) { Text(stringResource(R.string.settings_backup_clear_folder)) }
				}
			}
			HorizontalDivider(
				modifier = Modifier.padding(vertical = 6.dp),
				color = MaterialTheme.colorScheme.outlineVariant,
			)
			FlowRowCompat {
				OutlinedButton(
					onClick = { exportLauncher.launch("inhuman-backup") },
					enabled = !state.backupBusy,
					shape = MaterialTheme.shapes.small,
				) {
					Text(
						stringResource(
							if (state.backupFolderUri != null && !state.backupFolderPermissionLost) {
								R.string.settings_export_backup_file
							} else {
								R.string.settings_export_backup
							},
						),
					)
				}
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
				initiallyExpanded = query.isNotBlank(),
				visible = matches(approvalsText),
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
					suggestionsEnabled = state.suggestionsEnabled,
					bilibiliEnabled = state.bilibiliEnabled,
					bilibiliLoggedIn = state.bilibiliLoggedIn,
					downloadEnabled = state.downloadEnabled,
					localSearchEnabled = state.localWebSearchEnabled,
					localFetchEnabled = state.localWebFetchEnabled,
					mediaConfigured = state.mediaConfiguredTools,
					onRuleChange = viewModel::setApprovalRule,
					onRulesChange = viewModel::setApprovalRules,
				)
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_diagnostics),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(diagnosticsText),
				title = stringResource(R.string.settings_diagnostics_title),
				summary = stringResource(R.string.settings_diagnostics_summary),
			) {
				DiagnosticsSection()
				OutlinedButton(
					onClick = { showNotices = true },
					shape = MaterialTheme.shapes.small,
				) {
					Text(stringResource(R.string.settings_third_party_notices))
				}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_local),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(localText),
				title = stringResource(R.string.settings_local_title),
				summary = stringResource(R.string.settings_local_summary),
			) {
				LocalNetworkSection(
					updateServerUrl = state.updateServerUrl,
					uploadServerUrl = state.uploadServerUrl,
					currentVersionName = state.currentVersionName,
					currentVersionCode = state.currentVersionCode,
					checking = state.updateChecking,
					downloading = state.updateDownloading,
					progress = state.updateProgress,
					info = state.updateInfo,
					readyToInstall = state.updateReadyToInstall,
					status = state.updateStatus,
					statusIsError = state.updateStatusIsError,
					uploading = state.logUploading,
					logStatus = state.logUploadStatus,
					logStatusIsError = state.logUploadStatusIsError,
					recordingActive = state.recordingActive,
					recordingBytes = state.recordingBytes,
					onUpdateServerUrlChange = viewModel::onUpdateServerUrlChange,
					onUploadServerUrlChange = viewModel::onUploadServerUrlChange,
					onCheck = viewModel::checkForUpdates,
					onDownload = viewModel::downloadUpdate,
					onInstall = viewModel::installUpdate,
					onUploadLog = viewModel::uploadLog,
					onStartRecording = viewModel::startDiagnosticRecording,
					onStopRecording = viewModel::stopDiagnosticRecording,
					onUploadRecording = viewModel::uploadRecording,
					onDeleteRecording = viewModel::deleteDiagnosticRecording,
				)
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_display),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(displayText),
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
							label = {
								Text(
									stringResource(
										when (preference) {
											com.inhuman.core.design.LayoutPreference.Auto ->
												R.string.settings_layout_auto_short

											com.inhuman.core.design.LayoutPreference.Compact ->
												R.string.settings_layout_compact_short

											com.inhuman.core.design.LayoutPreference.Comfortable ->
												R.string.settings_layout_comfortable_short
										},
									),
								)
							},
						)
					}
				}
				HorizontalDivider(
					modifier = Modifier.padding(vertical = 8.dp),
					color = MaterialTheme.colorScheme.outlineVariant,
				)
				Text(
					text = stringResource(R.string.settings_theme_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = stringResource(R.string.settings_theme_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					com.inhuman.core.design.ThemePalette.entries.forEach { preference ->
						FilterChip(
							selected = state.themePalette == preference,
							onClick = {
								viewModel.setThemePalette(preference)
								onThemePaletteChange(preference)
							},
							label = {
								Text(
									stringResource(
										when (preference) {
											com.inhuman.core.design.ThemePalette.Ember ->
												R.string.settings_theme_ember
											com.inhuman.core.design.ThemePalette.Moss ->
												R.string.settings_theme_moss
											com.inhuman.core.design.ThemePalette.Indigo ->
												R.string.settings_theme_indigo
											com.inhuman.core.design.ThemePalette.Plum ->
												R.string.settings_theme_plum
										},
									),
								)
							},
							leadingIcon = {
								// The chip is the swatch's label, not the other way
								// round: a colour alone would be unreadable and
								// would fail the same check that took `outline`
								// out of text slots.
								Box(
									Modifier
										.size(12.dp)
										.background(
											com.inhuman.core.design.paletteColors(preference).darkPrimary,
											CircleShape,
										),
								)
							},
						)
					}
				}
				FlowRowCompat {
					com.inhuman.core.design.ThemeMode.entries.forEach { mode ->
						FilterChip(
							selected = state.themeMode == mode,
							onClick = {
								viewModel.setThemeMode(mode)
								onThemeModeChange(mode)
							},
							label = {
								Text(
									stringResource(
										when (mode) {
											com.inhuman.core.design.ThemeMode.System ->
												R.string.settings_theme_mode_system
											com.inhuman.core.design.ThemeMode.Light ->
												R.string.settings_theme_mode_light
											com.inhuman.core.design.ThemeMode.Dark ->
												R.string.settings_theme_mode_dark
										},
									),
								)
							},
						)
					}
				}
				HorizontalDivider(
					modifier = Modifier.padding(vertical = 8.dp),
					color = MaterialTheme.colorScheme.outlineVariant,
				)
				Text(
					text = stringResource(R.string.settings_surface_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = stringResource(R.string.settings_surface_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				FlowRowCompat {
					com.inhuman.core.design.SurfaceFamily.entries.forEach { family ->
						val swatch = com.inhuman.core.design.surfaceColors(family)
						FilterChip(
							selected = state.surfaceFamily == family,
							onClick = {
								viewModel.setSurfaceFamily(family)
								onSurfaceFamilyChange(family)
							},
							label = {
								Text(
									stringResource(
										when (family) {
											com.inhuman.core.design.SurfaceFamily.Warm ->
												R.string.settings_surface_warm
											com.inhuman.core.design.SurfaceFamily.Slate ->
												R.string.settings_surface_slate
											com.inhuman.core.design.SurfaceFamily.Sepia ->
												R.string.settings_surface_sepia
										},
									),
								)
							},
							leadingIcon = {
								// The paper step is the one visible on both a
								// dark and a light chip; the outline keeps a
								// pale swatch from disappearing on paper.
								Box(
									Modifier
										.size(12.dp)
										.background(swatch.lightBackground, CircleShape)
										.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
								)
							},
						)
					}
				}
				HorizontalDivider(
					modifier = Modifier.padding(vertical = 8.dp),
					color = MaterialTheme.colorScheme.outlineVariant,
				)
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.toggleable(
							value = state.suggestionsEnabled,
							role = Role.Switch,
							onValueChange = viewModel::setSuggestionsEnabled,
						),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.Top,
				) {
					Column(modifier = Modifier.weight(1f)) {
						Text(
							text = stringResource(R.string.settings_suggestions_title),
							style = MaterialTheme.typography.titleMedium,
							color = MaterialTheme.colorScheme.onBackground,
						)
						Text(
							text = stringResource(R.string.settings_suggestions_body),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					WorkbenchSwitch(
						checked = state.suggestionsEnabled,
						onCheckedChange = null,
						modifier = Modifier.padding(start = 12.dp),
					)
				}
			}

			SettingsFold(
				code = stringResource(R.string.settings_section_language),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(languageText),
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

			SettingsFold(
				code = stringResource(R.string.settings_section_time),
				initiallyExpanded = query.isNotBlank(),
				visible = matches(timeText),
				title = stringResource(R.string.settings_time_title),
				summary = when (state.timeHints.mode) {
					TimeHintMode.Off -> stringResource(R.string.settings_time_summary_off)
					TimeHintMode.Every -> stringResource(R.string.settings_time_summary_every)
					TimeHintMode.AfterGap -> stringResource(
						R.string.settings_time_summary_gap,
						state.timeHints.normalizedGapMinutes(),
					)
				},
			) {
				Column(
					modifier = Modifier
						.widthIn(max = Layout.ReadableMeasure)
						.fillMaxWidth(),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenCollapsed),
				) {
					Text(
						text = stringResource(R.string.settings_time_body),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Eyebrow(stringResource(R.string.settings_time_mode))
					FlowRowCompat {
						FilterChip(
							selected = state.timeHints.mode == TimeHintMode.AfterGap,
							onClick = { viewModel.setTimeHintMode(TimeHintMode.AfterGap) },
							label = { Text(stringResource(R.string.settings_time_mode_gap)) },
						)
						FilterChip(
							selected = state.timeHints.mode == TimeHintMode.Every,
							onClick = { viewModel.setTimeHintMode(TimeHintMode.Every) },
							label = { Text(stringResource(R.string.settings_time_mode_every)) },
						)
						FilterChip(
							selected = state.timeHints.mode == TimeHintMode.Off,
							onClick = { viewModel.setTimeHintMode(TimeHintMode.Off) },
							label = { Text(stringResource(R.string.settings_time_mode_off)) },
						)
					}
					// The threshold only means something in the gap mode; showing
					// it beside "every message" would suggest it applies there.
					if (state.timeHints.mode == TimeHintMode.AfterGap) {
						Text(
							text = stringResource(R.string.settings_time_gap_body),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
						Eyebrow(stringResource(R.string.settings_time_gap))
						FlowRowCompat {
							TimeHintPolicy.GAP_OPTIONS.forEach { minutes ->
								FilterChip(
									selected = state.timeHints.normalizedGapMinutes() == minutes,
									onClick = { viewModel.setTimeHintGapMinutes(minutes) },
									label = {
										Text(
											stringResource(
												R.string.settings_time_gap_minutes,
												minutes,
											),
										)
									},
								)
							}
						}
					}
				}
			}
		}
	}

	if (showHostKeysDialog) {
		AlertDialog(
			onDismissRequest = { showHostKeysDialog = false },
			title = { Text(stringResource(R.string.settings_host_keys_title)) },
			text = {
				Column(
					modifier = Modifier
						.heightIn(max = dialogBodyMaxHeight(360.dp))
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(Layout.Space.BetweenMessages),
				) {
					Text(
						stringResource(R.string.settings_host_keys_body),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

	if (showSshHostDialog) {
		SshHostDialog(
			initial = editingSshHost,
			onSave = { name, host, port, username, fingerprint ->
				viewModel.saveSshHost(
					id = editingSshHost?.id,
					name = name,
					host = host,
					port = port,
					username = username,
					hostKeyFingerprint = fingerprint,
				)
				showSshHostDialog = false
			},
			onDelete = {
				editingSshHost?.let { viewModel.deleteSshHost(it.id) }
				showSshHostDialog = false
			},
			onDismiss = { showSshHostDialog = false },
		)
	}

	if (showBackupPassphrase && backupMode != null &&
		(backupMode == BackupMode.ExportToFolder || backupUri != null)
	) {
		val mode = backupMode!!
		val isExport = mode == BackupMode.Export || mode == BackupMode.ExportToFolder
		val canSubmit = backupPassphrase.length >= 8 &&
			(!isExport || backupPassphrase == backupPassphraseConfirm)
		AlertDialog(
			onDismissRequest = {
				showBackupPassphrase = false
				backupPassphrase = ""
				backupPassphraseConfirm = ""
			},
			title = {
				Text(
					stringResource(
						if (isExport) {
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
						text = when (mode) {
							BackupMode.Export ->
								stringResource(R.string.settings_export_passphrase_body)
							BackupMode.ExportToFolder ->
								stringResource(
									R.string.settings_export_folder_passphrase_body,
									state.backupFolderName ?: state.backupFolderUri.orEmpty(),
								)
							BackupMode.Import ->
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
					if (isExport) {
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
						val passphrase = backupPassphrase.toCharArray()
						showBackupPassphrase = false
						backupPassphrase = ""
						backupPassphraseConfirm = ""
						when (mode) {
							BackupMode.Export -> {
								val uri = backupUri ?: run {
									passphrase.fill('\u0000')
									return@Button
								}
								viewModel.exportBackup(uri, passphrase)
							}
							BackupMode.ExportToFolder -> {
								viewModel.exportBackupToFolder(passphrase)
							}
							BackupMode.Import -> {
								val uri = backupUri ?: run {
									passphrase.fill('\u0000')
									return@Button
								}
								viewModel.previewImport(uri, passphrase)
							}
						}
					},
					enabled = canSubmit,
					shape = MaterialTheme.shapes.small,
				) {
					Text(
						stringResource(
							if (isExport) {
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
			onClearKey = viewModel::clearProviderKey,
			onDismiss = viewModel::closeProviderEditor,
		)
	}

	state.oauthLogin?.let { login ->
		OAuthLoginDialog(
			ui = login,
			onUpdate = viewModel::updateOAuthLogin,
			onBegin = viewModel::beginOAuth,
			onSignOut = viewModel::signOutOAuth,
			onConsumeUrl = viewModel::consumeOAuthUrl,
			onDismiss = viewModel::closeOAuth,
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
			onClearKey = viewModel::clearMediaKey,
			onClose = viewModel::closeMedia,
		)
	}

	if (showClearWebKeysConfirm) {
		AlertDialog(
			onDismissRequest = { showClearWebKeysConfirm = false },
			title = { Text(stringResource(R.string.settings_web_clear_keys)) },
			text = { Text(stringResource(R.string.settings_web_clear_keys_confirm)) },
			confirmButton = {
				TextButton(
					onClick = {
						showClearWebKeysConfirm = false
						viewModel.clearWebKeys()
					},
				) {
					Text(
						text = stringResource(R.string.common_delete),
						color = MaterialTheme.colorScheme.error,
					)
				}
			},
			dismissButton = {
				TextButton(onClick = { showClearWebKeysConfirm = false }) {
					Text(stringResource(R.string.common_cancel))
				}
			},
			containerColor = MaterialTheme.colorScheme.surface,
			titleContentColor = MaterialTheme.colorScheme.onSurface,
			textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}

	if (showSpeechVoiceDialog) {
		SpeechVoiceDialog(
			initial = state.speechVoice,
			onSave = { voice ->
				viewModel.saveSpeechVoice(voice)
				showSpeechVoiceDialog = false
			},
			onDismiss = { showSpeechVoiceDialog = false },
		)
	}
}

@Composable
private fun SpeechVoiceDialog(
	initial: String,
	onSave: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var text by remember(initial) { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_speech_voice)) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				OutlinedTextField(
					value = text,
					onValueChange = { text = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_speech_voice)) },
					supportingText = {
						Text(stringResource(R.string.settings_speech_voice_hint))
					},
					singleLine = true,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onSave(text) }) {
				Text(stringResource(R.string.common_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

private enum class BackupMode { Export, ExportToFolder, Import }

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
			Row(
				verticalAlignment = Alignment.CenterVertically,
				horizontalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(text = title, style = MaterialTheme.typography.titleSmall)
				// Choosing the backend is choosing the bill: the chat-menu
				// dialogs for the same tools already carry this badge.
				PaidBadge()
			}
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
			} else if (provider.kind == ProviderKinds.DEEPSEEK &&
				(capability == MediaCapability.Speech ||
					capability == MediaCapability.Transcription)
			) {
				// DeepSeek's speech endpoints take no model id; printing the
				// Qwen default here would name a model that never goes on the
				// wire.
				stringResource(
					R.string.settings_media_summary_provider,
					provider.name,
					stringResource(R.string.settings_media_deepseek_backend),
				)
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
private fun SshHostDialog(
	initial: SshHostSummary?,
	onSave: (
		name: String,
		host: String,
		port: Int,
		username: String,
		fingerprint: String,
	) -> Unit,
	onDelete: () -> Unit,
	onDismiss: () -> Unit,
) {
	var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
	var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
	var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
	var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
	var fingerprint by remember(initial) { mutableStateOf(initial?.hostKeyFingerprint.orEmpty()) }
	var error by remember { mutableStateOf<String?>(null) }
	val errorText = stringResource(R.string.settings_saved_host_error)
	// A typed port outside 1..65535 used to be silently coerced on
	// save (99999 became 65535, 0 became 1), so the stored value
	// disagreed with the field and the dialog closed anyway.
	val portInvalid = port.isNotBlank() &&
		(port.toIntOrNull()?.let { it in 1..65535 } != true)

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_saved_host_title)) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = dialogBodyMaxHeight(380.dp))
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					stringResource(R.string.settings_saved_host_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					stringResource(R.string.settings_saved_host_note),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				// The message sits at the top of the scrolled form. It used to
				// be the last child, below seven fields: tapping Save on a short
				// window scrolled the error out of sight, so the button looked
				// dead.
				error?.let {
					Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
				}
				OutlinedTextField(
					value = name,
					onValueChange = { name = it; error = null },
					label = { Text(stringResource(R.string.settings_ssh_host_name)) },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = host,
					onValueChange = { host = it; error = null },
					label = { Text(stringResource(R.string.settings_saved_host_host)) },
					singleLine = true,
					isError = error != null && host.isBlank(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = port,
					onValueChange = { port = it.filter(Char::isDigit); error = null },
					label = { Text(stringResource(R.string.settings_saved_host_port)) },
					singleLine = true,
					isError = error != null && portInvalid,
					supportingText = if (error != null && portInvalid) {
						{ Text(stringResource(R.string.settings_saved_host_port_range)) }
					} else {
						null
					},
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = username,
					onValueChange = { username = it; error = null },
					label = { Text(stringResource(R.string.settings_saved_host_user)) },
					singleLine = true,
					isError = error != null && username.isBlank(),
					modifier = Modifier.fillMaxWidth(),
				)
				OutlinedTextField(
					value = fingerprint,
					onValueChange = { fingerprint = it; error = null },
					label = { Text(stringResource(R.string.settings_saved_host_fingerprint)) },
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					if (host.isBlank() || username.isBlank() || portInvalid) {
						error = errorText
					} else {
						onSave(
							name,
							host,
							port.toIntOrNull() ?: 22,
							username,
							fingerprint,
						)
					}
				},
			) { Text(stringResource(R.string.common_save)) }
		},
		dismissButton = {
			Row {
				if (initial != null) {
					TextButton(onClick = onDelete) {
						Text(stringResource(R.string.settings_ssh_host_delete))
					}
				}
				TextButton(onClick = onDismiss) {
					Text(stringResource(R.string.common_cancel))
				}
			}
		},
	)
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
			LazyColumn(modifier = Modifier.heightIn(max = dialogBodyMaxHeight(480.dp))) {
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
	onOAuth: (() -> Unit)? = null,
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
		if (onOAuth != null) {
			TextButton(
				onClick = onOAuth,
				contentPadding = PaddingValues(horizontal = 8.dp),
			) {
				Text(
					text = stringResource(R.string.settings_oauth_action),
					style = MaterialTheme.typography.labelSmall,
				)
			}
		}
		Icon(
			Icons.AutoMirrored.Filled.KeyboardArrowRight,
			contentDescription = null,
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.size(18.dp),
		)
	}
}

@Composable
private fun OAuthLoginDialog(
	ui: OAuthLoginUi,
	onUpdate: ((OAuthLoginUi) -> OAuthLoginUi) -> Unit,
	onBegin: () -> Unit,
	onSignOut: () -> Unit,
	onConsumeUrl: () -> Unit,
	onDismiss: () -> Unit,
) {
	val context = LocalContext.current
	val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
	val openFailed = stringResource(R.string.settings_oauth_open_failed)
	LaunchedEffect(Unit) {
		// A tap that opens the dialog can land on the field now underneath it;
		// start with no focus so the keyboard does not cover the instructions.
		focusManager.clearFocus()
	}
	LaunchedEffect(ui.authorizeUrl) {
		val url = ui.authorizeUrl ?: return@LaunchedEffect
		val opened = runCatching {
			context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
		}.isSuccess
		if (!opened) {
			onUpdate {
				it.copy(
					status = openFailed,
					statusIsError = true,
				)
			}
		}
		onConsumeUrl()
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.settings_oauth_title, ui.providerName)) },
		text = {
			Column(
				modifier = Modifier
					.heightIn(max = dialogBodyMaxHeight(360.dp))
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Text(
					text = stringResource(R.string.settings_oauth_detail),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				OutlinedTextField(
					value = ui.authorizationEndpoint,
					onValueChange = { value -> onUpdate { it.copy(authorizationEndpoint = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_oauth_authorization)) },
					singleLine = true,
				)
				OutlinedTextField(
					value = ui.tokenEndpoint,
					onValueChange = { value -> onUpdate { it.copy(tokenEndpoint = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_oauth_token)) },
					singleLine = true,
				)
				OutlinedTextField(
					value = ui.clientId,
					onValueChange = { value -> onUpdate { it.copy(clientId = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_oauth_client_id)) },
					singleLine = true,
				)
				OutlinedTextField(
					value = ui.scopes,
					onValueChange = { value -> onUpdate { it.copy(scopes = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_oauth_scopes)) },
					singleLine = true,
				)
				OutlinedTextField(
					value = ui.clientSecret,
					onValueChange = { value -> onUpdate { it.copy(clientSecret = value) } },
					modifier = Modifier.fillMaxWidth(),
					label = { Text(stringResource(R.string.settings_oauth_secret)) },
					singleLine = true,
					visualTransformation = PasswordVisualTransformation(),
				)
				Text(
					text = stringResource(
						R.string.settings_oauth_redirect,
						OAuthProtocol.DEFAULT_REDIRECT_URI,
					),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				if (ui.signedIn) {
					Text(
						text = stringResource(R.string.settings_oauth_signed_in),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.secondary,
					)
				}
				ui.status?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodySmall,
						color = if (ui.statusIsError) {
							MaterialTheme.colorScheme.error
						} else {
							MaterialTheme.colorScheme.onSurfaceVariant
						},
					)
				}
			}
		},
		confirmButton = {
			if (ui.signedIn) {
				TextButton(onClick = onSignOut) {
					Text(stringResource(R.string.settings_oauth_sign_out))
				}
			} else {
				TextButton(onClick = onBegin, enabled = ui.canBegin && !ui.busy) {
					Text(
						if (ui.busy) {
							stringResource(R.string.settings_oauth_busy)
						} else {
							stringResource(R.string.settings_oauth_sign_in)
						},
					)
				}
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.common_cancel))
			}
		},
	)
}

@Composable
private fun ProviderEditorDialog(
	draft: ProviderDraft,
	onUpdate: ((ProviderDraft) -> ProviderDraft) -> Unit,
	onSave: () -> Unit,
	onTest: () -> Unit,
	onDelete: () -> Unit,
	onClearKey: () -> Unit,
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
					.heightIn(max = dialogBodyMaxHeight(520.dp))
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				// First, not last: this form scrolls, and the error used to
				// sit under the manual-models field eight controls down -- so
				// pressing Save looked like nothing happened. SshHostDialog
				// carries the same note for the same fix.
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
					FilterChip(
						selected = draft.kind == ProviderKinds.OPENAI_RESPONSES,
						onClick = {
							onUpdate {
								it.copy(
									kind = ProviderKinds.OPENAI_RESPONSES,
									baseUrl = if (it.isNew) "https://api.openai.com/v1" else it.baseUrl,
									reasoningMode = ProviderReasoning.NONE,
								)
							}
						},
						label = { Text("OpenAI Responses") },
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
				// Hidden once a replacement has been typed: hasKey becomes true
				// from the input too, and clearing the stored key while a new one
				// is merely typed would throw the old key away on Cancel.
				if (draft.hasStoredKey) {
					TextButton(onClick = onClearKey) {
						Text(
							text = stringResource(R.string.settings_provider_clear_key),
							color = MaterialTheme.colorScheme.error,
						)
					}
					Text(
						text = stringResource(R.string.settings_provider_clear_key_note),
						style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
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
							selected = draft.reasoningMode == ProviderReasoning.OPENCODE,
							onClick = {
								onUpdate { it.copy(reasoningMode = ProviderReasoning.OPENCODE) }
							},
							label = { Text("OpenCode") },
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
/** The localized words a settings search can match for one section. */
private data class SettingsSectionText(
	val code: String,
	val title: String,
	val summary: String,
	/**
	 * Words for the things inside the section: the voice picker, the Bilibili
	 * login, the address book.
	 *
	 * The title and summary describe a section in a sentence, and a sentence
	 * cannot name every control. Searching for a setting by the name the user
	 * knows it by returned "no matching section" while the setting sat inside
	 * one; a keyword list is the cheapest honest fix, and it is localized
	 * because the keywords are what a person types.
	 */
	val keywords: String = "",
)

@Composable
internal fun SettingsFold(
	code: String,
	title: String,
	summary: String,
	initiallyExpanded: Boolean = false,
	/** False while a settings search excludes this section. */
	visible: Boolean = true,
	/**
	 * Called once each time the section becomes expanded.
	 *
	 * The Usage section uses this to defer its full-table read until the user
	 * actually asks for the numbers; opening Settings no longer pays for a
	 * report that may never be looked at.
	 */
	onExpand: () -> Unit = {},
	content: @Composable ColumnScope.() -> Unit,
) {
	if (!visible) return
	var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
	// A search opens what it matched. `rememberSaveable` keeps the user's fold
	// state otherwise, so this only pushes open, never closed.
	LaunchedEffect(initiallyExpanded) {
		if (initiallyExpanded) expanded = true
	}
	LaunchedEffect(expanded) {
		if (expanded) onExpand()
	}
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

/**
 * The chip word for a built-in update address.
 *
 * Keyed by host rather than by list position, so reordering the presets cannot
 * pair a chip with the wrong address.
 */
private fun updatePresetLabel(url: String): Int = when {
	url.startsWith("https://github.com/") -> R.string.settings_update_preset_github
	url.contains("gh-proxy.com") -> R.string.settings_update_preset_mirror
	else -> R.string.settings_update_preset_mirror_alt
}

@Composable
private fun LocalNetworkSection(
	updateServerUrl: String,
	uploadServerUrl: String,
	currentVersionName: String,
	currentVersionCode: Long,
	checking: Boolean,
	downloading: Boolean,
	progress: Int?,
	info: UpdateInfo?,
	readyToInstall: Boolean,
	status: String?,
	statusIsError: Boolean,
	uploading: Boolean,
	logStatus: String?,
	logStatusIsError: Boolean,
	recordingActive: Boolean,
	recordingBytes: Long,
	onUpdateServerUrlChange: (String) -> Unit,
	onUploadServerUrlChange: (String) -> Unit,
	onCheck: () -> Unit,
	onDownload: () -> Unit,
	onInstall: () -> Unit,
	onUploadLog: () -> Unit,
	onStartRecording: () -> Unit,
	onStopRecording: () -> Unit,
	onUploadRecording: () -> Unit,
	onDeleteRecording: () -> Unit,
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
			value = updateServerUrl,
			onValueChange = onUpdateServerUrlChange,
			label = { Text(stringResource(R.string.settings_update_server)) },
			placeholder = { Text(stringResource(R.string.settings_update_server_hint)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		// One tap instead of typing a 70+ character URL. Choosing a chip is
		// exactly "set the update address": it writes the same field the user
		// could type, and the field stays visible and editable afterwards. It
		// deliberately does not touch the upload address.
		Text(
			text = stringResource(R.string.settings_update_presets),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		FlowRowCompat {
			BUILT_IN_UPDATE_URLS.forEach { url ->
				FilterChip(
					selected = updateServerUrl.trimEnd('/') == url,
					onClick = { onUpdateServerUrlChange(url) },
					label = { Text(stringResource(updatePresetLabel(url))) },
				)
			}
		}
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

		// Uploading the log and uploading a conversation belong to the second
		// address, not the update one: the public release feeds have no /log or
		// /conversation endpoint. This section owns that address, which is why
		// the button lives here rather than in Diagnostics.
		HorizontalDivider(Modifier.padding(top = 4.dp))
		OutlinedTextField(
			value = uploadServerUrl,
			onValueChange = onUploadServerUrlChange,
			label = { Text(stringResource(R.string.settings_upload_server)) },
			placeholder = { Text(stringResource(R.string.settings_upload_server_hint)) },
			singleLine = true,
			modifier = Modifier.fillMaxWidth(),
		)
		Text(
			text = stringResource(R.string.settings_upload_note),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		TextButton(
			onClick = {
				focus.clearFocus()
				onUploadLog()
			},
			enabled = !uploading,
		) {
			Text(
				if (uploading) {
					stringResource(R.string.settings_log_uploading)
				} else {
					stringResource(R.string.settings_log_upload)
				},
			)
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
		logStatus?.let {
			Text(
				text = it,
				style = MaterialTheme.typography.bodySmall,
				color = if (logStatusIsError) {
					MaterialTheme.colorScheme.error
				} else {
					MaterialTheme.colorScheme.primary
				},
			)
		}

		HorizontalDivider(Modifier.padding(top = 4.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.settings_recording_title),
					style = MaterialTheme.typography.titleSmall,
				)
				Text(
					text = stringResource(R.string.settings_recording_body),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			WorkbenchSwitch(
				checked = recordingActive,
				onCheckedChange = { record ->
					if (record) onStartRecording() else onStopRecording()
				},
			)
		}
		when {
			recordingActive -> Text(
				text = stringResource(R.string.settings_recording_active),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.primary,
			)

			recordingBytes > 0 -> {
				Text(
					text = stringResource(
						R.string.settings_recording_last,
						formatBytes(recordingBytes),
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					TextButton(
						onClick = onUploadRecording,
						enabled = !uploading,
					) {
						Text(
							if (uploading) {
								stringResource(R.string.settings_log_uploading)
							} else {
								stringResource(R.string.settings_recording_upload)
							},
						)
					}
					TextButton(onClick = onDeleteRecording) {
						Text(stringResource(R.string.settings_recording_delete))
					}
				}
			}

			else -> Text(
				text = stringResource(R.string.settings_recording_empty_hint),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun DiagnosticsSection() {
	val context = LocalContext.current
	var bytes by remember { mutableStateOf(AppDiagnostics.sizeBytes()) }
	var confirmingClear by remember { mutableStateOf(false) }
	val shareLog = stringResource(R.string.settings_share_log)
	val shareSubject = stringResource(R.string.settings_share_subject)
	var sandboxOk by remember { mutableStateOf(AppCapabilities.sandboxOk) }
	LaunchedEffect(Unit) {
		while (sandboxOk == null) {
			kotlinx.coroutines.delay(200)
			sandboxOk = AppCapabilities.sandboxOk
		}
	}

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
		Text(
			text = when (sandboxOk) {
				true -> stringResource(R.string.settings_sandbox_available)
				false -> stringResource(
					R.string.settings_sandbox_unavailable,
					AppCapabilities.sandboxError ?: stringResource(R.string.settings_sandbox_unknown_reason),
				)
				null -> stringResource(R.string.settings_sandbox_checking)
			},
			style = MaterialTheme.typography.bodySmall,
			color = if (sandboxOk == false) {
				MaterialTheme.colorScheme.error
			} else {
				MaterialTheme.colorScheme.onSurfaceVariant
			},
		)

		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			Button(
				onClick = { shareDiagnostics(context, bytes, shareLog, shareSubject) },
				enabled = bytes > 0,
			) {
				Text(shareLog)
			}
			TextButton(
				onClick = { confirmingClear = true },
				enabled = bytes > 0,
			) {
				Text(stringResource(R.string.settings_clear))
			}
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
	// The shared formatter says KiB/MiB; the local "KB" for the same binary
	// division was the one label that disagreed with every other size.
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
	suggestionsEnabled: Boolean,
	bilibiliEnabled: Boolean,
	bilibiliLoggedIn: Boolean,
	downloadEnabled: Boolean,
	localSearchEnabled: Boolean,
	localFetchEnabled: Boolean,
	mediaConfigured: Set<String>?,
	onRuleChange: (String, ToolPolicy) -> Unit,
	onRulesChange: (List<String>, ToolPolicy) -> Unit,
) {
	// One family open at a time, same as the assistant editor. What is visible
	// by default is the answer to "does anything here ask first?" -- the full
	// catalogue is one tap below its family row instead of forty rows of chips.
	var expandedFamily by rememberSaveable { mutableStateOf("") }
	val visible = ToolCatalog.visible(
		sshToolEnabled,
		includeWorkspaceBound = AppCapabilities.workspaceOk,
		includeSandbox = AppCapabilities.sandboxOk == true,
		includeSuggestions = suggestionsEnabled,
		includeBilibili = bilibiliEnabled,
		includeBilibiliLogin = bilibiliLoggedIn,
		includeDownload = downloadEnabled,
		includeLocalSearch = localSearchEnabled,
		includeLocalFetch = localFetchEnabled,
		includePdfVision = true,
		mediaConfigured = mediaConfigured,
	)
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
		if (sshToolEnabled) {
			RemoteToolsNotice(modifier = Modifier.padding(bottom = 2.dp))
		}

		toolGroups(visible).forEach { group ->
			val names = group.entries.map { it.name }
			val familyExpanded = expandedFamily == group.family.name
			ToolGroupPolicyRow(
				family = group.family,
				entries = group.entries,
				policy = groupPolicy(rules, names),
				customised = names.count { (rules[it] ?: ToolPolicy.Free) != ToolPolicy.Free },
				assistant = false,
				expanded = familyExpanded,
				onSelect = { policy -> onRulesChange(names, policy) },
				onToggleExpanded = {
					expandedFamily = if (familyExpanded) "" else group.family.name
				},
			)
			if (familyExpanded) {
				group.entries.forEach { entry ->
					val current = rules[entry.name] ?: ToolPolicy.Free
					ApprovalRuleRow(
						name = entry.name,
						blurb = toolBlurb(entry),
						changesFiles = entry.mutates,
						paid = entry.paid,
						remote = entry.remote,
						policy = current,
						onSelect = { onRuleChange(entry.name, it) },
					)
				}
			}
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
	remote: Boolean,
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
			// Remote is marked here too: this is the screen where an SSH tool's
			// policy is chosen, and a plain name made it look local.
			if (paid) PaidBadge()
			if (remote) RemoteBadge()
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
		// Four chips do not fit on one line at 1.5x font on a phone; a plain
		// Row clipped the last one. FlowRowCompat is the same fix the provider
		// and media editors already use.
		FlowRowCompat {
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

/** A title + explanation + switch row, for the local web section. */
@Composable
private fun SwitchRow(
	title: String,
	body: String,
	checked: Boolean,
	onCheckedChange: (Boolean) -> Unit,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 8.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.Top,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(
				text = title,
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onBackground,
			)
			Text(
				text = body,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
		WorkbenchSwitch(
			checked = checked,
			onCheckedChange = onCheckedChange,
			modifier = Modifier.padding(start = 12.dp),
		)
	}
}
