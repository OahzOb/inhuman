package com.inhuman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.inhuman.core.design.InhumanTheme
import com.inhuman.ui.ConversationsScreen

class MainActivity : ComponentActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		// Edge-to-edge is requested explicitly: the app draws its own insets so
		// the composer can sit above the keyboard and below the status bar
		// without a double gap.
		enableEdgeToEdge()
		super.onCreate(savedInstanceState)
		setContent {
			InhumanTheme {
				ConversationsScreen()
			}
		}
	}
}
