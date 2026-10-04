package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.ChatImage
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatRequest
import com.inhuman.core.ai.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image wire format, pinned to what the API actually accepted.
 *
 * ## Why this test exists at all
 *
 * Two shapes are reasonable and only one works. `image_url` as an object with a
 * `url` field returns 200; as a bare string it returns
 * `422 ... invalid type: string, expected struct OpenAIChatMessageContentPartImage`.
 * The field is named after a URL, so a string is the natural guess -- and the
 * failure only appears on a request that carries an image, which is the path
 * least likely to be exercised by hand.
 *
 * ## Why the body is asserted as JSON rather than as a string
 *
 * Key order is not part of the contract and a string comparison would break on
 * a harmless reordering. The structure is what matters.
 */
class DeepSeekImageRequestTest {

	/** Four bytes that are not valid UTF-8, so encoding has to be real base64. */
	private val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

	private fun body(messages: List<ChatMessage>): JsonObject =
		DeepSeekJson.parseToJsonElement(
			DeepSeekRequest.build(
				ChatRequest(
					model = "deepseek-flash",
					messages = messages,
					tools = emptyList(),
					thinking = false,
					reasoningEffort = null,
					userId = null,
				),
			).toString(),
		).jsonObject

	private fun firstMessage(messages: List<ChatMessage>): JsonObject =
		body(messages)["messages"]!!.jsonArray[0].jsonObject

	@Test
	fun `an image is sent as content parts with image_url as an object`() {
		val message = firstMessage(
			listOf(
				ChatMessage(
					role = Role.User,
					content = "what is this?",
					images = listOf(ChatImage("image/png", bytes)),
				),
			),
		)

		val content = message["content"]!!.jsonArray
		assertEquals(2, content.size)

		assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
		assertEquals("what is this?", content[0].jsonObject["text"]!!.jsonPrimitive.content)

		val imagePart = content[1].jsonObject
		assertEquals("image_url", imagePart["type"]!!.jsonPrimitive.content)

		// The whole point: image_url must be an object, not a string. A string
		// here compiles, reads correctly, and is rejected by the API with a 422
		// that names a Rust type.
		val imageUrl = imagePart["image_url"]
		assertTrue(
			"image_url must be an object with a url field, not a bare string",
			imageUrl is JsonObject,
		)
		assertTrue(imageUrl!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
	}

	@Test
	fun `the bytes are base64 encoded, not passed through`() {
		val message = firstMessage(
			listOf(
				ChatMessage(
					role = Role.User,
					content = "x",
					images = listOf(ChatImage("image/jpeg", bytes)),
				),
			),
		)

		val url = message["content"]!!.jsonArray[1].jsonObject["image_url"]!!
			.jsonObject["url"]!!.jsonPrimitive.content

		// 0x89 is not printable and would corrupt the JSON if written raw.
		assertEquals("data:image/jpeg;base64,", url.substringBefore("base64,") + "base64,")
		assertEquals("iVBORw==", url.substringAfter("base64,"))
	}

	@Test
	fun `text alone stays a plain string`() {
		// Both forms are accepted, so routing text through an array would work
		// and would be pointless -- and would make every stored transcript
		// depend on a shape it does not need.
		val message = firstMessage(listOf(ChatMessage(Role.User, "hello")))

		assertTrue(
			"a message with no images should keep content as a string",
			message["content"] is kotlinx.serialization.json.JsonPrimitive,
		)
		assertEquals("hello", message["content"]!!.jsonPrimitive.content)
	}

	@Test
	fun `an image with no text sends only the image part`() {
		// "What is this?" is implied by attaching a picture, and an empty text
		// part is a different thing from no text part.
		val message = firstMessage(
			listOf(
				ChatMessage(
					role = Role.User,
					content = "",
					images = listOf(ChatImage("image/png", bytes)),
				),
			),
		)

		val content = message["content"]!!.jsonArray
		assertEquals(1, content.size)
		assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
	}

	@Test
	fun `several images keep their order`() {
		val message = firstMessage(
			listOf(
				ChatMessage(
					role = Role.User,
					content = "compare",
					images = listOf(
						ChatImage("image/png", byteArrayOf(1)),
						ChatImage("image/png", byteArrayOf(2)),
						ChatImage("image/png", byteArrayOf(3)),
					),
				),
			),
		)

		val parts = message["content"]!!.jsonArray
		assertEquals(4, parts.size)
		val urls = parts.drop(1).map {
			it.jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
		}
		// Order is the only thing that lets a model say "the second one".
		assertEquals(listOf("AQ==", "Ag==", "Aw=="), urls.map { it.substringAfter("base64,") })
	}

	@Test
	fun `an assistant turn never carries images`() {
		// The provider answers in text. An assistant message with parts would be
		// a transcript the model did not produce, and replaying it is at best
		// ignored and at worst rejected.
		val message = firstMessage(
			listOf(ChatMessage(role = Role.Assistant, content = "sure")),
		)

		assertEquals("sure", message["content"]!!.jsonPrimitive.content)
		assertNull(message["tool_calls"])
	}

	@Test
	fun `an attachment survives being built twice`() {
		// ChatImage defines equals by hand because ByteArray's generated one
		// compares identity. If that were wrong, the same message sent twice
		// would build two different bodies.
		val a = ChatImage("image/png", byteArrayOf(1, 2, 3))
		val b = ChatImage("image/png", byteArrayOf(1, 2, 3))

		assertEquals(a, b)
		assertEquals(a.hashCode(), b.hashCode())
		assertTrue(a != ChatImage("image/jpeg", byteArrayOf(1, 2, 3)))
		assertTrue(a != ChatImage("image/png", byteArrayOf(1, 2, 4)))
	}
}
