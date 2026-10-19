package com.inhuman.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.common.diag.Diag
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.diagnostics.AppDiagnostics
import com.inhuman.ui.chat.SettingsViewModel
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
	var revealed by remember { mutableStateOf(false) }
	var showHostKeysDialog by remember { mutableStateOf(false) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Settings", style = MaterialTheme.typography.titleMedium) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Text("‹", style = MaterialTheme.typography.titleLarge)
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
					titleContentColor = MaterialTheme.colorScheme.onBackground,
					navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
				),
			)
		},
		containerColor = MaterialTheme.colorScheme.background,
	) { inner ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(inner)
				.windowInsetsPadding(WindowInsets.safeDrawing)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = comfortableGutter(), vertical = 12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Column(
				modifier = Modifier
					// Constrain first, fill second. `fillMaxWidth()` is greedy and
					// consumes the incoming maximum, so with these swapped the cap
					// was inert and the field stretched across 1280dp.
					.widthIn(max = Layout.ReadableMeasure)
					.fillMaxWidth(),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				Text(
					text = "DeepSeek API key",
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onBackground,
				)
				Text(
					text = "Stored encrypted, with the key held in the Android Keystore. " +
						"It never leaves the device except to call DeepSeek.",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)

				OutlinedTextField(
					value = state.apiKeyInput,
					onValueChange = viewModel::onApiKeyChange,
					modifier = Modifier.fillMaxWidth(),
					singleLine = true,
					label = { Text("sk-…") },
					visualTransformation = if (revealed) {
						VisualTransformation.None
					} else {
						PasswordVisualTransformation()
					},
					trailingIcon = {
						IconButton(onClick = { revealed = !revealed }) {
							Icon(
								imageVector = if (revealed) Icons.Filled.VisibilityOff
								else Icons.Filled.Visibility,
								contentDescription = if (revealed) "Hide" else "Show",
							)
						}
					},
					keyboardOptions = KeyboardOptions(
						keyboardType = KeyboardType.Password,
						imeAction = ImeAction.Done,
					),
				)

				Row(
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Button(
						onClick = viewModel::save,
						enabled = !state.isChecking,
					) {
						Text("Save")
					}
					TextButton(
						onClick = viewModel::verify,
						enabled = !state.isChecking && state.hasSavedKey,
					) {
						Text("Test connection")
					}
					if (state.isChecking) {
						CircularProgressIndicator(
							modifier = Modifier.padding(start = 4.dp),
							strokeWidth = 2.dp,
						)
					}
				}

				state.status?.let { status ->
					Text(
						text = status,
						style = MaterialTheme.typography.bodyMedium,
						color = if (state.statusIsError) {
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
					verticalAlignment = Alignment.CenterVertically,
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
					Switch(
						checked = state.demoMode,
						onCheckedChange = viewModel::setDemoMode,
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
					verticalAlignment = Alignment.CenterVertically,
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
					Switch(
						checked = state.sshToolEnabled,
						onCheckedChange = viewModel::setSshToolEnabled,
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
