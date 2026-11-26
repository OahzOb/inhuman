package com.inhuman.core.engine.roleplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The external-card importer.
 *
 * The fixtures are built here instead of committed as binaries: a PNG card is
 * just a PNG with one `tEXt` chunk, and a CHARX is a ZIP with `card.json`, so a
 * test can construct the exact shape it means to exercise (including the
 * JPEG+ZIP hybrid) without a fixture directory.
 */
class RoleplayCardImportTest {

	// ---- formats ------------------------------------------------------------

	@Test
	fun `v2 json maps the fields and reports what was dropped`() {
		val json = """
			{
			  "spec": "chara_card_v2",
			  "spec_version": "2.0",
			  "data": {
			    "name": "Aster",
			    "description": "A lighthouse keeper.",
			    "personality": "Dry.",
			    "scenario": "A storm.",
			    "first_mes": "You are late.",
			    "mes_example": "<START>\n{{user}}: Hello\n{{char}}: Go away.",
			    "creator_notes": "Made for a test.",
			    "system_prompt": "Stay in character.",
			    "post_history_instructions": "Be brief.",
			    "alternate_greetings": ["Hi.", "Hello."],
			    "group_only_greetings": ["Group intro."],
			    "character_book": {"entries": [{"keys": ["sea"]}, {"keys": ["light"]}]},
			    "extensions": {"regex_scripts": [{}, {}]},
			    "assets": [{"type": "icon"}]
			  }
			}
		""".trimIndent()
		val result = RoleplayCardImport.import(json.toByteArray(), "card.json", characterName = "Aster")
		val success = result as RoleplayCardImport.Result.Success
		assertEquals("chara_card_v2", success.warnings.sourceFormat)
		assertEquals("Aster", success.warnings.cardName)
		assertEquals("A lighthouse keeper.", success.card.description)
		assertEquals("You are late.", success.card.greeting)
		assertEquals("Dry.", success.card.personality)
		assertEquals("A storm.", success.card.scenario)
		// The example keeps its turns; `<START>` becomes a line break.
		assertEquals("{{user}}: Hello\nAster: Go away.", success.card.exampleDialogue)
		assertTrue(success.warnings.unsupported.containsKey(RoleplayCardImport.UnsupportedPart.AlternateGreetings))
		assertEquals(2, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.AlternateGreetings])
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.GroupOnlyGreetings])
		assertEquals(2, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.CharacterBook])
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.CreatorNotes])
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.SystemPrompt])
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.PostHistoryInstructions])
		assertEquals(2, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.Extensions])
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.Assets])
	}

	@Test
	fun `v3 json is read through its data object`() {
		val json = """
			{"spec":"chara_card_v3","spec_version":"3.0","data":{
			  "name":"Iris","description":"Curator.","first_mes":"Come in.",
			  "alternate_greetings":["Back again?"],"group_only_greetings":[]
			}}
		""".trimIndent()
		val success = RoleplayCardImport.import(json.toByteArray(), "card.json") as RoleplayCardImport.Result.Success
		assertEquals("chara_card_v3", success.warnings.sourceFormat)
		assertEquals("Iris", success.card.name)
		assertEquals("Curator.", success.card.description)
		assertEquals("Come in.", success.card.greeting)
		assertEquals(1, success.warnings.unsupported[RoleplayCardImport.UnsupportedPart.AlternateGreetings])
	}

	@Test
	fun `v1 flat json and this app's own fields are both accepted`() {
		val v1 = """{"name":"Moss","description":"A collector.","first_mes":"Hello.","mes_example":"A: hi"}"""
		val success = RoleplayCardImport.import(v1.toByteArray(), "card.json") as RoleplayCardImport.Result.Success
		assertEquals("chara_card_v1", success.warnings.sourceFormat)
		assertEquals("A collector.", success.card.description)
		assertEquals("Hello.", success.card.greeting)

		val mine = """{"name":"","description":"Own format.","greeting":"Ahoy.","exampleDialogue":"A: hey"}"""
		val own = RoleplayCardImport.import(mine.toByteArray(), "card.json") as RoleplayCardImport.Result.Success
		assertEquals("inhuman", own.warnings.sourceFormat)
		assertEquals("Ahoy.", own.card.greeting)
		assertEquals("A: hey", own.card.exampleDialogue)
	}

	@Test
	fun `a png card is read from its tEXt chara chunk`() {
		val payload = """{"spec":"chara_card_v2","data":{"name":"Png","first_mes":"Seen."}}"""
		val png = pngWithChunk("tEXt", "chara\u0000" + Base64.getEncoder().encodeToString(payload.toByteArray()))
		val success = RoleplayCardImport.import(png, "card.png") as RoleplayCardImport.Result.Success
		assertEquals("Seen.", success.card.greeting)
	}

	@Test
	fun `a charx archive is read from card json`() {
		val card = """{"spec":"chara_card_v2","data":{"name":"Zip","first_mes":"Unpacked."}}"""
		val charx = zipOf(mapOf("card.json" to card.toByteArray(), "assets/avatar.png" to byteArrayOf(1, 2, 3)))
		val success = RoleplayCardImport.import(charx, "card.charx") as RoleplayCardImport.Result.Success
		assertEquals("Unpacked.", success.card.greeting)
	}

	@Test
	fun `a zip appended to an image is still read`() {
		val card = """{"data":{"name":"Hybrid","first_mes":"Appended."}}"""
		val zip = zipOf(mapOf("card.json" to card.toByteArray()))
		val hybrid = "JPEGDATA".toByteArray() + zip
		val success = RoleplayCardImport.import(hybrid, "card.charx") as RoleplayCardImport.Result.Success
		assertEquals("Appended.", success.card.greeting)
	}

	// ---- cleanup and limits -------------------------------------------------

	@Test
	fun `html in mapped text becomes plain text`() {
		val json = """
			{"data":{"name":"Html","description":"<div style=\"color:red\"><p>Line one</p><p>Line <b>two</b><br>three &amp; four</p><script>alert(1)</script></div>"}}
		""".trimIndent()
		val success = RoleplayCardImport.import(json.toByteArray(), "card.json") as RoleplayCardImport.Result.Success
		assertTrue(success.warnings.htmlStripped)
		assertEquals("Line one\nLine two\nthree & four", success.card.description)
		assertFalse(success.card.description.contains("<"))
		assertFalse(success.card.description.contains("alert"))
	}

	@Test
	fun `over-limit fields are truncated and reported`() {
		val long = "x".repeat(RoleplayLimits.DESCRIPTION_MAX + 100)
		val json = """{"data":{"name":"Big","description":"$long"}}"""
		val success = RoleplayCardImport.import(json.toByteArray(), "card.json") as RoleplayCardImport.Result.Success
		assertEquals(RoleplayLimits.DESCRIPTION_MAX, success.card.description.length)
		assertTrue(success.warnings.truncated.contains(RoleplayField.Description))
	}

	@Test
	fun `char placeholders become the assistant name`() {
		val json = """{"data":{"name":"Card","description":"{{char}} watches {{user}}."}}"""
		val success = RoleplayCardImport.import(json.toByteArray(), "card.json", characterName = "Aster") as RoleplayCardImport.Result.Success
		assertEquals("Aster watches {{user}}.", success.card.description)
	}

	@Test
	fun `a card without name uses the assistant name for the placeholder`() {
		val json = """{"data":{"description":"{{char}} waits."}}"""
		val success = RoleplayCardImport.import(json.toByteArray(), "card.json", characterName = "") as RoleplayCardImport.Result.Success
		assertEquals("{{char}} waits.", success.card.description)
	}

	// ---- failures -----------------------------------------------------------

	@Test
	fun `invalid and foreign files fail with a reason`() {
		assertEquals(
			RoleplayCardImport.FailureReason.InvalidJson,
			(RoleplayCardImport.import("{not json".toByteArray(), "card.json") as RoleplayCardImport.Result.Failure).reason,
		)
		assertEquals(
			RoleplayCardImport.FailureReason.NotACardFile,
			(RoleplayCardImport.import("hello".toByteArray(), "card.txt") as RoleplayCardImport.Result.Failure).reason,
		)
		assertEquals(
			RoleplayCardImport.FailureReason.MissingCardData,
			(RoleplayCardImport.import("""{"random":true}""".toByteArray(), "card.json") as RoleplayCardImport.Result.Failure).reason,
		)
		assertEquals(
			RoleplayCardImport.FailureReason.EmptyCard,
			(RoleplayCardImport.import("""{"data":{"name":"OnlyName"}}""".toByteArray(), "card.json") as RoleplayCardImport.Result.Failure).reason,
		)
	}

	@Test
	fun `an oversized file is refused before parsing`() {
		val bytes = ByteArray(RoleplayCardImport.MAX_BYTES + 1)
		assertEquals(
			RoleplayCardImport.FailureReason.TooLarge,
			(RoleplayCardImport.import(bytes, "card.png") as RoleplayCardImport.Result.Failure).reason,
		)
	}

	@Test
	fun `a png without a chara chunk is missing card data`() {
		val png = pngWithChunk("tEXt", "Comment\u0000not a card")
		assertEquals(
			RoleplayCardImport.FailureReason.MissingCardData,
			(RoleplayCardImport.import(png, "card.png") as RoleplayCardImport.Result.Failure).reason,
		)
	}

	// ---- helpers ------------------------------------------------------------

	/** Minimal PNG: signature + one chunk + IEND. The importer never decodes the image. */
	private fun pngWithChunk(type: String, text: String): ByteArray {
		val out = ByteArrayOutputStream()
		out.write(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()))
		val data = text.toByteArray(Charsets.ISO_8859_1)
		out.writeInt(data.size)
		out.write(type.toByteArray(Charsets.US_ASCII))
		out.write(data)
		out.writeInt(0) // CRC: the importer does not verify it
		out.writeInt(0)
		out.write("IEND".toByteArray(Charsets.US_ASCII))
		out.writeInt(0)
		return out.toByteArray()
	}

	private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
		val out = ByteArrayOutputStream()
		ZipOutputStream(out).use { zip ->
			for ((name, bytes) in entries) {
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return out.toByteArray()
	}

	private fun ByteArrayOutputStream.writeInt(value: Int) {
		write((value ushr 24) and 0xFF)
		write((value ushr 16) and 0xFF)
		write((value ushr 8) and 0xFF)
		write(value and 0xFF)
	}
}
