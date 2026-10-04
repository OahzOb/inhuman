package com.inhuman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.secret.SecureStore
import com.inhuman.core.design.InhumanTheme
import com.inhuman.ui.ConversationsScreen
import com.inhuman.ui.ConversationsViewModel
import com.inhuman.ui.chat.ChatScreen
import com.inhuman.ui.chat.ChatViewModel
import com.inhuman.ui.chat.SettingsViewModel
import com.inhuman.ui.settings.SettingsScreen

/**
 * Where the app currently is.
 *
 * Plain state rather than a navigation library. With three screens and no deep
 * links, a navigation graph would be more machinery than the problem has --
 * and Navigation 3's "you own the back stack" model means adopting it later is
 * a change of where this list lives, not a rewrite of the screens.
 */
private enum class Screen { Conversations, Chat, Settings }

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			InhumanTheme {
				// Named Root, not InhumanApp: InhumanApp is the Application
				// class in this same package, and a composable of the same name
				// is an overload ambiguity rather than a shadowing warning.
				InhumanRoot()
			}
		}
	}
}

@Composable
private fun InhumanRoot() {
	val context = androidx.compose.ui.platform.LocalContext.current
	// One store and one repository for the process. The Keystore lookup inside
	// SecureStore is not free, and Room's concurrency is per-instance -- two
	// instances against one file would serialise badly and invite locking that
	// a single instance avoids.
	val secure = remember { SecureStore(context) }
	val repository = remember { ConversationRepository(context) }

	// Which conversation the chat screen is showing. Null means the list.
	var openConversationId by remember { mutableStateOf<String?>(null) }
	var showSettings by remember { mutableStateOf(false) }

	val settingsFactory = remember(secure) { viewModelFactory { SettingsViewModel(secure) } }

	when {
		showSettings -> {
			val vm: SettingsViewModel = viewModel(factory = settingsFactory)
			SettingsScreen(viewModel = vm, onBack = { showSettings = false })
		}

		openConversationId != null -> {
			// Keyed by conversation id so switching conversations creates a new
			// view model rather than reusing one that is still holding the
			// previous transcript.
			val id = openConversationId!!
			val factory = remember(id) {
				viewModelFactory { ChatViewModel(repository, secure, id) }
			}
			val vm: ChatViewModel = viewModel(key = "chat-$id", factory = factory)
			ChatScreen(
				viewModel = vm,
				onBack = { openConversationId = null },
				onOpenSettings = { showSettings = true },
			)
		}

		else -> {
			val factory = remember(repository) { viewModelFactory { ConversationsViewModel(repository) } }
			val vm: ConversationsViewModel = viewModel(factory = factory)
			ConversationsScreen(
				viewModel = vm,
				onOpenConversation = { openConversationId = it },
				onOpenSettings = { showSettings = true },
			)
		}
	}
}

/**
 * A [ViewModelProvider.Factory] for a view model with constructor arguments.
 *
 * Written by hand rather than pulled from a DI framework: there are exactly two
 * view models and one dependency, and Koin would be more configuration than the
 * graph it describes. It earns its place when the graph grows, not before.
 */
private inline fun <reified VM : ViewModel> viewModelFactory(
	crossinline create: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
	@Suppress("UNCHECKED_CAST")
	override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
