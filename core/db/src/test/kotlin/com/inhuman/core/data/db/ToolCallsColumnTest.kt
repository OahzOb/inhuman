package com.inhuman.core.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored tool-call column, shared by Android and the desktop host.
 *
 * The argument strings are compared unchanged because the provider cache
 * compares the request it produced; re-serialising a parsed object would change
 * key order and whitespace.
 */
class ToolCallsColumnTest {

	@Test
	fun `round-trips the exact argument string`() {
		val calls = listOf(
			StoredToolCall(
				index = 0,
				id = "call-1",
				name = "write_file",
				argumentsJson = """{"path":"a b.txt","content":"line one\nline two"}""",
				thoughtSignature = "opaque-signature",
			),
		)

		val decoded = ToolCallsColumn.decode(ToolCallsColumn.encode(calls))

		assertEquals(calls, decoded)
	}

	@Test
	fun `SSH credentials are removed before the row is stored`() {
		val raw = ToolCallsColumn.encode(
			listOf(
				StoredToolCall(
					index = 0,
					id = "call-1",
					name = "ssh_exec",
					argumentsJson = """
						{"host":"example.test","username":"root","command":"id",
						 "password":"hunter2","privateKey":"BEGIN KEY","passphrase":"open"}
					""".trimIndent(),
				),
			),
		)

		assertFalse(raw.contains("hunter2"))
		assertFalse(raw.contains("BEGIN KEY"))
		assertFalse(raw.contains("open"))

		val arguments = Json.parseToJsonElement(
			ToolCallsColumn.decode(raw).single().argumentsJson,
		).jsonObject
		assertEquals("example.test", arguments["host"]?.jsonPrimitive?.content)
		assertEquals("id", arguments["command"]?.jsonPrimitive?.content)
		assertFalse(arguments.containsKey("password"))
		assertFalse(arguments.containsKey("privateKey"))
		assertFalse(arguments.containsKey("passphrase"))
	}

	@Test
	fun `invalid input reads as no calls`() {
		assertTrue(ToolCallsColumn.decode(null).isEmpty())
		assertTrue(ToolCallsColumn.decode("").isEmpty())
		assertTrue(ToolCallsColumn.decode("not json").isEmpty())
		assertTrue(ToolCallsColumn.decode("""{"not":"an array"}""").isEmpty())
	}
}
