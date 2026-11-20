package com.inhuman.ui.chat

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The share provider's half of "Share conversation", on a device.
 *
 * The chooser itself is a system activity, but the part that fails silently is
 * the read grant: a wrong authority or path resource makes the receiving app
 * get a `SecurityException` after the user has already picked a destination.
 * This drives the same bytes-in/bytes-out path the chooser uses.
 */
@RunWith(AndroidJUnit4::class)
class ConversationShareTest {

	private val context: Context
		get() = InstrumentationRegistry.getInstrumentation().targetContext

	@Test
	fun `the share provider reads the Markdown the chooser receives`() {
		val file = ConversationShare.newFile(context, "conversation.md")
		val markdown = "# A conversation\n\nhello\n"
		file.writeText(markdown)

		val uri = ConversationShare.uri(context, file)
		assertEquals("content", uri.scheme)
		assertEquals("${context.packageName}.share", uri.authority)
		val read = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
		assertEquals(markdown, read?.toString(Charsets.UTF_8))

		assertTrue(file.parentFile?.deleteRecursively() == true)
	}
}
