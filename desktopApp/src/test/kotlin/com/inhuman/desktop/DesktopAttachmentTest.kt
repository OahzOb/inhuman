package com.inhuman.desktop

import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.FinishReason
import com.inhuman.core.ai.RemoteModel
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.StreamChunk
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopAttachmentTest {
	@get:Rule
	val tmp = TemporaryFolder()

	private fun noisyPng(file: File, width: Int = 2_000, height: Int = 1_200) {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
		val random = Random(7)
		for (y in 0 until height) {
			for (x in 0 until width) {
				image.setRGB(x, y, random.nextInt(0xFFFFFF))
			}
		}
		ImageIO.write(image, "png", file)
	}

	@Test
	fun `codec reads the Android attachment shape`() {
		val decoded = DesktopAttachmentCodec.decode(
			"""[{"path":"attachments/a.jpg","mime":"image/jpeg","size":123}]""",
		)
		assertEquals(1, decoded.size)
		assertEquals("attachments/a.jpg", decoded[0].relativePath)
		assertEquals("image/jpeg", decoded[0].mimeType)
		assertEquals(123L, decoded[0].sizeBytes)
		assertEquals("a.jpg", decoded[0].displayName)
	}

	@Test
	fun `a noisy image is re-encoded under the workspace read limit`() {
		val source = tmp.newFile("noise.png")
		noisyPng(source)
		assertTrue("the fixture should exceed the limit", source.length() > 1_000_000)

		val prepared = DesktopImages.prepare(source, 300_000)
		assertEquals("image/jpeg", prepared.mimeType)
		assertTrue(prepared.bytes.size <= 300_000)
		assertTrue(prepared.width <= 1_600)
		assertTrue(prepared.height <= 1_600)
	}

	@Test
	fun `an imported image reaches the request as a ChatImage`() = runBlocking {
		val store = DesktopStore.open(tmp.newFolder("desktop-image"))
		try {
			val conversation = store.create("Images")
			val source = tmp.newFile("source.png")
			noisyPng(source, width = 800, height = 600)
			val attachment = store.importAttachment(conversation.id, source)
			assertTrue(attachment.relativePath.startsWith("attachments/"))
			assertTrue(attachment.sizeBytes > 0)
			assertTrue(store.readAttachment(conversation.id, attachment)?.isNotEmpty() == true)

			val provider = CaptureProvider()
			val failure = DesktopChat(store, conversation.id, null).send(
				userText = "what is in this image?",
				provider = provider,
				model = "capture-model",
				attachments = listOf(attachment),
			) {}
			assertNull(failure)
			val user = provider.request!!.messages.last { it.role == Role.User }
			assertEquals("the image is attached on the wire", 1, user.images.size)
			assertEquals("image/jpeg", user.images[0].mimeType)

			val loaded = store.load(conversation.id).first { it.role == "user" }
			assertEquals(1, loaded.attachments.size)
			assertEquals(attachment.relativePath, loaded.attachments[0].relativePath)

			val rewind = store.rewindLastTurn(conversation.id)
			assertNull(rewind.error)
			assertEquals("rewind returns the attachment to the composer", 1, rewind.attachments.size)
			assertEquals(attachment.relativePath, rewind.attachments[0].relativePath)
		} finally {
			store.close()
		}
	}
}

private class CaptureProvider : ChatProvider {
	override val id = "capture"
	var request: ChatRequest? = null

	override suspend fun listModels(): List<RemoteModel> = emptyList()

	override fun stream(request: ChatRequest): Flow<StreamChunk> {
		this.request = request
		return flowOf(
			StreamChunk.TextDelta("ok"),
			StreamChunk.Finish(FinishReason.Stop),
		)
	}
}
