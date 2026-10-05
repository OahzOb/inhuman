package com.inhuman.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inhuman.core.design.Layout
import com.inhuman.core.design.comfortableGutter
import com.inhuman.ui.chat.SettingsViewModel

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
							text = "Replies are scripted on the device, and the file " +
								"tools run for real. Nothing is sent anywhere and no key " +
								"is needed. With tools switched on it creates a file and " +
								"then asks to delete it, so the approval gate can be " +
								"seen without an account.",
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
		}
	}
}
