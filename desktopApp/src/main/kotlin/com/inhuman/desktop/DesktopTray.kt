package com.inhuman.desktop

import java.awt.Color
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * The Windows notification-area presence.
 *
 * A turn can run while the window is minimised; without a tray icon its
 * completion is invisible. The icon opens the window and exits the app, and a
 * balloon is shown when a reply finishes while the window is not focused.
 *
 * Everything here is best-effort: a headless session or a disabled tray must
 * not stop the app from starting, so [install] returns false instead of
 * throwing.
 */
class DesktopTray(
	private val onOpen: () -> Unit,
	private val onExit: () -> Unit,
) {
	private var icon: TrayIcon? = null

	fun install(): Boolean {
		if (!SystemTray.isSupported()) return false
		return runCatching {
			val trayIcon = TrayIcon(iconImage(), "inhuman").apply {
				isImageAutoSize = true
				addActionListener { SwingUtilities.invokeLater(onOpen) }
				popupMenu = PopupMenu().apply {
					add(
						MenuItem("Open inhuman").apply {
							addActionListener { SwingUtilities.invokeLater(onOpen) }
						},
					)
					addSeparator()
					add(
						MenuItem("Exit").apply {
							addActionListener { SwingUtilities.invokeLater(onExit) }
						},
					)
				}
			}
			SystemTray.getSystemTray().add(trayIcon)
			icon = trayIcon
			true
		}.getOrDefault(false)
	}

	fun notifyReply() {
		runCatching {
			icon?.displayMessage(
				"inhuman",
				"Reply ready",
				TrayIcon.MessageType.INFO,
			)
		}
	}

	fun remove() {
		runCatching { icon?.let { SystemTray.getSystemTray().remove(it) } }
		icon = null
	}

	private fun iconImage(): BufferedImage {
		val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
		val graphics = image.createGraphics()
		try {
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
			graphics.color = Color(21, 19, 17)
			graphics.fillRect(0, 0, 16, 16)
			graphics.color = Color(229, 107, 63)
			graphics.fillOval(2, 2, 12, 12)
			graphics.color = Color(34, 20, 14)
			graphics.fillRect(7, 4, 2, 8)
			graphics.fillRect(7, 2, 2, 2)
		} finally {
			graphics.dispose()
		}
		return image
	}
}
