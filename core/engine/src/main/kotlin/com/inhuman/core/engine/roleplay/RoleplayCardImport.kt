package com.inhuman.core.engine.roleplay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Imports a character card from the formats the wider role-play ecosystem uses.
 *
 * ## What it accepts
 *
 * - **JSON** — Character Card V1 (flat), V2 and V3 (`spec` + `data`), as well as
 *   this app's own `card.json` shape. Both `first_mes`/`mes_example` and
 *   `greeting`/`exampleDialogue` are read, so a card exported by inhuman can be
 *   imported again.
 * - **PNG** — a card image whose `tEXt`/`iTXt` chunk `chara` carries base64 JSON
 *   (the SillyTavern/Character Card convention).
 * - **CHARX** — a ZIP (or JPEG+ZIP hybrid) containing `card.json` and assets.
 *
 * ## What it does not do
 *
 * This is the *content* import, not a runtime. A front-end card's HTML UI lives
 * in `data.extensions.regex_scripts` and only works with a regex engine plus
 * JavaScript variables; a world book needs a lorebook feature the app does not
 * have yet. Those parts are reported in [CardImportWarnings.unsupported] and are
 * not stored: silently keeping hundreds of kilobytes of inert JSON in every
 * request's card would cost context and buy nothing. The UI says so.
 *
 * HTML found in the *mapped text* (greeting, description, example dialogue) is
 * converted to plain text. Card files are untrusted third-party input: nothing
 * here executes, loads a resource, or keeps a tag for a renderer to interpret.
 * The raw file is not persisted -- the user still has it and can import again.
 *
 * ## Limits
 *
 * The card's prompt budget is [RoleplayLimits], not the source file's. Fields
 * longer than this app's ceilings are truncated and reported; they are never
 * silently shortened. The file itself is capped at [MAX_BYTES] before parsing.
 */
object RoleplayCardImport {

	/** Hard ceiling for the whole file. PNG/CHARX cards are images; this is generous. */
	const val MAX_BYTES: Int = 16 * 1024 * 1024

	private val pngSignature = byteArrayOf(
		0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
		0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
	)

	/** A part of the source card this release cannot use yet. */
	enum class UnsupportedPart {
		AlternateGreetings,
		GroupOnlyGreetings,
		CharacterBook,
		CreatorNotes,
		SystemPrompt,
		PostHistoryInstructions,
		Extensions,
		Assets,
	}

	data class CardImportWarnings(
		/** `chara_card_v1`, `chara_card_v2`, `chara_card_v3` or `inhuman`. */
		val sourceFormat: String = "",
		/** The card's own name, when it has one. The assistant's name is not changed. */
		val cardName: String = "",
		/** Mapped text contained HTML, which was converted to plain text. */
		val htmlStripped: Boolean = false,
		/** Mapped fields that exceeded this app's ceilings and were truncated. */
		val truncated: List<RoleplayField> = emptyList(),
		/** Parts of the source card that were dropped, with their counts when countable. */
		val unsupported: Map<UnsupportedPart, Int> = emptyMap(),
	)

	sealed interface Result {
		data class Success(val card: RoleplayCard, val warnings: CardImportWarnings) : Result
		data class Failure(val reason: FailureReason, val detail: String = "") : Result
	}

	enum class FailureReason {
		NotACardFile,
		TooLarge,
		InvalidJson,
		MissingCardData,
		EmptyCard,
		Unreadable,
	}

	/**
	 * Parse one card file.
	 *
	 * @param characterName the assistant's name, substituted for `{{char}}` in
	 *   imported text. An empty value leaves the placeholder alone rather than
	 *   baking in an English fallback the author did not write.
	 */
	fun import(bytes: ByteArray, fileName: String? = null, characterName: String = ""): Result {
		if (bytes.isEmpty()) return Result.Failure(FailureReason.Unreadable)
		if (bytes.size > MAX_BYTES) return Result.Failure(FailureReason.TooLarge, "${bytes.size} bytes")
		val json = when {
			isPng(bytes) -> pngCardJson(bytes)
			isZip(bytes) || hasAppendedZip(bytes) -> charxCardJson(bytes)
			else -> decodeJsonText(bytes, fileName)
		}
		return when (json) {
			is EmbeddedJson.Found -> parseCard(json.text, characterName)
			is EmbeddedJson.Failure -> json.result
		}
	}

	private sealed interface EmbeddedJson {
		data class Found(val text: String) : EmbeddedJson
		data class Failure(val result: Result.Failure) : EmbeddedJson
	}

	private fun decodeJsonText(bytes: ByteArray, fileName: String?): EmbeddedJson {
		val text = runCatching { bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF") }.getOrNull()
			?: return EmbeddedJson.Failure(Result.Failure(FailureReason.Unreadable))
		val trimmed = text.trim()
		val looksJson = trimmed.startsWith("{") || trimmed.startsWith("[")
		if (!looksJson) return EmbeddedJson.Failure(Result.Failure(FailureReason.NotACardFile))
		// A file that ended in .png but has no PNG signature is almost always a
		// truncated download; name it rather than pretending it was JSON.
		if (fileName?.lowercase()?.endsWith(".png") == true) {
			return EmbeddedJson.Failure(Result.Failure(FailureReason.MissingCardData))
		}
		return EmbeddedJson.Found(text)
	}

	// ---- JSON ---------------------------------------------------------------

	private val json = Json { ignoreUnknownKeys = true }

	private fun parseCard(text: String, characterName: String): Result {
		val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
			?: return Result.Failure(FailureReason.InvalidJson)
		val spec = (root["spec"] as? JsonPrimitive)?.contentOrNull()
		// V2/V3 put the fields under `data`; V1 and this app's card.json are flat.
		val data = (root["data"] as? JsonObject)
			?: (root["character"] as? JsonObject)
			?: (root["char"] as? JsonObject)
			?: root
		val looksLikeCard = data.stringOrNull("name") != null ||
			data.stringOrNull("first_mes") != null ||
			data.stringOrNull("greeting") != null ||
			data.stringOrNull("description") != null
		if (!looksLikeCard) return Result.Failure(FailureReason.MissingCardData)

		val format = when {
			spec.equals("chara_card_v3", ignoreCase = true) -> "chara_card_v3"
			spec.equals("chara_card_v2", ignoreCase = true) -> "chara_card_v2"
			root !== data -> "chara_card_v1"
			// A flat object has no spec to read, so the field names decide: the
			// SillyTavern V1 keys versus this app's own exported shape.
			listOf("first_mes", "mes_example", "creator_notes", "system_prompt", "post_history_instructions")
				.any { data.containsKey(it) } -> "chara_card_v1"
			else -> "inhuman"
		}
		val cardName = data.stringOrNull("name") ?: data.stringOrNull("char_name") ?: ""

		val raw = Mapped(
			description = data.stringOrNull("description").orEmpty(),
			personality = data.stringOrNull("personality").orEmpty(),
			scenario = data.stringOrNull("scenario").orEmpty(),
			greeting = data.stringOrNull("first_mes") ?: data.stringOrNull("greeting").orEmpty(),
			exampleDialogue = data.stringOrNull("mes_example")
				?: data.stringOrNull("exampleDialogue")
				?: data.stringOrNull("example_dialogue")
				?: "",
		)

		var htmlStripped = false
		val cleaned = raw.mapValues { value ->
			val result = HtmlText.clean(value)
			htmlStripped = htmlStripped || result.changed
			result.text
		}.substituted(characterName = characterName.ifBlank { cardName })
		val hasContent = cleaned.description.isNotBlank() || cleaned.personality.isNotBlank() ||
			cleaned.scenario.isNotBlank() || cleaned.greeting.isNotBlank() ||
			cleaned.exampleDialogue.isNotBlank()
		if (!hasContent) return Result.Failure(FailureReason.EmptyCard)

		val truncated = mutableListOf<RoleplayField>()
		// The card's name counts toward the same total budget the editor
		// enforces (`RoleplayLimits.cardChars` includes it), so the import must
		// leave room for it instead of filling the ceiling with prose.
		val limitedName = cardName.take(RoleplayLimits.NAME_MAX).also {
			if (it.length != cardName.length) truncated += RoleplayField.Name
		}
		val budget = (RoleplayLimits.MAX_CARD_CHARS - limitedName.length).coerceAtLeast(0)
		val limited = cleaned.limitToCeilings(truncated, budget)
		val limitedCard = limited.toCard(limitedName)

		val unsupported = mutableMapOf<UnsupportedPart, Int>()
		data.stringOrNull("creator_notes")?.takeIf { it.isNotBlank() }
			?.let { unsupported[UnsupportedPart.CreatorNotes] = 1 }
		data.stringOrNull("system_prompt")?.takeIf { it.isNotBlank() }
			?.let { unsupported[UnsupportedPart.SystemPrompt] = 1 }
		data.stringOrNull("post_history_instructions")?.takeIf { it.isNotBlank() }
			?.let { unsupported[UnsupportedPart.PostHistoryInstructions] = 1 }
		(data["alternate_greetings"] as? JsonArray)?.takeIf { it.isNotEmpty() }
			?.let { unsupported[UnsupportedPart.AlternateGreetings] = it.size }
		(data["group_only_greetings"] as? JsonArray)?.takeIf { it.isNotEmpty() }
			?.let { unsupported[UnsupportedPart.GroupOnlyGreetings] = it.size }
		(data["character_book"] as? JsonObject)?.let { book ->
			val entries = (book["entries"] as? JsonArray)?.size ?: 0
			unsupported[UnsupportedPart.CharacterBook] = entries
		}
		(data["assets"] as? JsonArray)?.takeIf { it.isNotEmpty() }
			?.let { unsupported[UnsupportedPart.Assets] = it.size }
		(data["extensions"] as? JsonObject)?.takeIf { it.isNotEmpty() }
			?.let { extensions ->
				val scripts = (extensions["regex_scripts"] as? JsonArray)?.size
				unsupported[UnsupportedPart.Extensions] = scripts ?: 1
			}

		return Result.Success(
			card = limitedCard,
			warnings = CardImportWarnings(
				sourceFormat = format,
				cardName = cardName,
				htmlStripped = htmlStripped,
				truncated = truncated,
				unsupported = unsupported,
			),
		)
	}

	private class Mapped(
		val description: String,
		val personality: String,
		val scenario: String,
		val greeting: String,
		val exampleDialogue: String,
	) {
		fun mapValues(transform: (String) -> String): Mapped = Mapped(
			description = transform(description),
			personality = transform(personality),
			scenario = transform(scenario),
			greeting = transform(greeting),
			exampleDialogue = transform(exampleDialogue),
		)

		fun substituted(characterName: String): Mapped =
			if (characterName.isBlank()) this else mapValues { value ->
				value.replace("{{char}}", characterName, ignoreCase = true)
			}

		fun toCard(name: String): RoleplayCard = RoleplayCard(
			name = name,
			description = description,
			personality = personality,
			scenario = scenario,
			greeting = greeting,
			exampleDialogue = exampleDialogue,
		)

		/**
		 * Trim each field to this app's ceiling, reporting what was cut.
		 *
		 * The source card's limits are its author's; this app's are what the
		 * prompt budget can carry. Truncation is visible in the import summary,
		 * never silent. The per-field limits sum above the total limit, so the
		 * total is enforced afterwards by shortening the first field that has
		 * room left.
		 */
		fun limitToCeilings(truncated: MutableList<RoleplayField>, totalBudget: Int): Mapped {
			val fields = linkedMapOf<RoleplayField, String>(
				RoleplayField.Description to description,
				RoleplayField.Personality to personality,
				RoleplayField.Scenario to scenario,
				RoleplayField.Greeting to greeting,
				RoleplayField.ExampleDialogue to exampleDialogue,
			)
			val maxima = mapOf(
				RoleplayField.Description to RoleplayLimits.DESCRIPTION_MAX,
				RoleplayField.Personality to RoleplayLimits.PERSONALITY_MAX,
				RoleplayField.Scenario to RoleplayLimits.SCENARIO_MAX,
				RoleplayField.Greeting to RoleplayLimits.GREETING_MAX,
				RoleplayField.ExampleDialogue to RoleplayLimits.EXAMPLE_DIALOGUE_MAX,
			)
			for ((field, value) in fields) {
				val max = maxima.getValue(field)
				if (value.length > max) {
					fields[field] = value.take(max)
					truncated += field
				}
			}
			var total = fields.values.sumOf { it.length }
			if (total > totalBudget) {
				val order = listOf(
					RoleplayField.ExampleDialogue,
					RoleplayField.Description,
					RoleplayField.Personality,
					RoleplayField.Scenario,
					RoleplayField.Greeting,
				)
				for (field in order) {
					if (total <= totalBudget) break
					val value = fields.getValue(field)
					val excess = total - totalBudget
					val next = value.dropLast(minOf(excess, value.length))
					if (next != value) {
						fields[field] = next
						if (field !in truncated) truncated += field
						total = fields.values.sumOf { it.length }
					}
				}
			}
			return Mapped(
				description = fields.getValue(RoleplayField.Description),
				personality = fields.getValue(RoleplayField.Personality),
				scenario = fields.getValue(RoleplayField.Scenario),
				greeting = fields.getValue(RoleplayField.Greeting),
				exampleDialogue = fields.getValue(RoleplayField.ExampleDialogue),
			)
		}
	}

	// ---- PNG ----------------------------------------------------------------

	private fun isPng(bytes: ByteArray): Boolean =
		bytes.size >= pngSignature.size && pngSignature.indices.all { bytes[it] == pngSignature[it] }

	/** Pull the `chara` payload out of a card PNG, without decoding the image. */
	private fun pngCardJson(bytes: ByteArray): EmbeddedJson {
		var pos = 8 // signature
		while (pos + 8 <= bytes.size) {
			val length = readInt(bytes, pos)
			pos += 4
			if (length < 0 || length > bytes.size - pos - 4) {
				return EmbeddedJson.Failure(Result.Failure(FailureReason.MissingCardData))
			}
			val type = bytes.copyOfRange(pos, pos + 4).toString(Charsets.US_ASCII)
			pos += 4
			val dataStart = pos
			val dataEnd = pos + length
			pos = dataEnd + 4 // skip CRC
			val payload = when (type) {
				"tEXt" -> textChunk(bytes, dataStart, dataEnd)
				"iTXt" -> itxtChunk(bytes, dataStart, dataEnd)
				else -> null
			}
			if (payload != null) return EmbeddedJson.Found(payload)
		}
		return EmbeddedJson.Failure(Result.Failure(FailureReason.MissingCardData))
	}

	private fun textChunk(bytes: ByteArray, start: Int, end: Int): String? {
		val separator = (start until end).firstOrNull { bytes[it] == 0.toByte() } ?: return null
		val keyword = bytes.copyOfRange(start, separator).toString(Charsets.ISO_8859_1)
		if (!keyword.equals("chara", ignoreCase = true)) return null
		val base64 = bytes.copyOfRange(separator + 1, end).toString(Charsets.ISO_8859_1)
		return decodeBase64Json(base64)
	}

	private fun itxtChunk(bytes: ByteArray, start: Int, end: Int): String? {
		val separator = (start until end).firstOrNull { bytes[it] == 0.toByte() } ?: return null
		val keyword = bytes.copyOfRange(start, separator).toString(Charsets.ISO_8859_1)
		if (!keyword.equals("chara", ignoreCase = true)) return null
		val compressionFlag = bytes.getOrNull(separator + 1)?.toInt() ?: return null
		var cursor = separator + 3 // flag + method
		cursor = (cursor until end).firstOrNull { bytes[it] == 0.toByte() }?.plus(1) ?: return null // language tag
		cursor = (cursor until end).firstOrNull { bytes[it] == 0.toByte() }?.plus(1) ?: return null // translated keyword
		if (cursor > end) return null
		val textBytes = bytes.copyOfRange(cursor, end)
		val decoded = if (compressionFlag == 1) {
			runCatching { inflate(textBytes) }.getOrNull() ?: return null
		} else {
			textBytes
		}
		return decodeBase64Json(decoded.toString(Charsets.UTF_8))
	}

	private fun decodeBase64Json(value: String): String {
		val cleaned = value.filterNot { it.isWhitespace() }
		val decoded = runCatching { Base64.getDecoder().decode(cleaned) }.getOrNull()
			?: runCatching { Base64.getMimeDecoder().decode(cleaned) }.getOrNull()
			?: return ""
		return decoded.toString(Charsets.UTF_8)
	}

	// ---- ZIP / CHARX --------------------------------------------------------

	private fun isZip(bytes: ByteArray): Boolean =
		bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
			bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

	/**
	 * CHARX may be a plain ZIP or a JPEG with a ZIP appended. ZIP offsets inside
	 * the archive are relative to the archive start, so the end-of-central-
	 * directory record gives the start: EOCD sits right after the central
	 * directory, and both sizes are recorded in it.
	 */
	private fun hasAppendedZip(bytes: ByteArray): Boolean = zipStart(bytes) != null

	private fun zipStart(bytes: ByteArray): Int? {
		if (isZip(bytes)) return 0
		val minSearch = maxOf(0, bytes.size - 65_557) // 22-byte EOCD + max comment
		for (i in bytes.size - 22 downTo minSearch) {
			// ZIP stores its integers little-endian; the PNG reader above is
			// big-endian, so the two helpers are deliberately separate.
			if (readLittleEndianInt(bytes, i) != 0x06054B50) continue
			val centralSize = readLittleEndianInt(bytes, i + 12)
			val centralOffset = readLittleEndianInt(bytes, i + 16)
			if (centralSize < 0 || centralOffset < 0) continue
			val start = i - centralSize - centralOffset
			if (start in 0 until bytes.size && isZip(bytes.copyOfRange(start, minOf(start + 4, bytes.size)))) return start
		}
		return null
	}

	private fun charxCardJson(bytes: ByteArray): EmbeddedJson {
		val start = zipStart(bytes) ?: return EmbeddedJson.Failure(Result.Failure(FailureReason.MissingCardData))
		val archive = bytes.copyOfRange(start, bytes.size)
		runCatching {
			ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (entry.isDirectory) continue
					val name = entry.name.substringAfterLast('/')
					if (name.equals("card.json", ignoreCase = true) || name.endsWith(".json", ignoreCase = true)) {
						return EmbeddedJson.Found(readBounded(zip).toString(Charsets.UTF_8).removePrefix("\uFEFF"))
					}
				}
			}
		}.onFailure { return EmbeddedJson.Failure(Result.Failure(FailureReason.Unreadable, it.message.orEmpty())) }
		return EmbeddedJson.Failure(Result.Failure(FailureReason.MissingCardData))
	}

	// ---- bytes helpers -------------------------------------------------------

	private fun readInt(bytes: ByteArray, offset: Int): Int {
		if (offset < 0 || offset + 4 > bytes.size) return -1
		return ((bytes[offset].toInt() and 0xFF) shl 24) or
			((bytes[offset + 1].toInt() and 0xFF) shl 16) or
			((bytes[offset + 2].toInt() and 0xFF) shl 8) or
			(bytes[offset + 3].toInt() and 0xFF)
	}

	private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int {
		if (offset < 0 || offset + 4 > bytes.size) return -1
		return (bytes[offset].toInt() and 0xFF) or
			((bytes[offset + 1].toInt() and 0xFF) shl 8) or
			((bytes[offset + 2].toInt() and 0xFF) shl 16) or
			((bytes[offset + 3].toInt() and 0xFF) shl 24)
	}

	/** Read up to the import ceiling; a huge entry is truncated, not allocated whole. */
	private fun readBounded(input: InputStream): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(8 * 1024)
		while (out.size() < MAX_BYTES) {
			val read = input.read(buffer)
			if (read <= 0) break
			out.write(buffer, 0, minOf(read, MAX_BYTES - out.size()))
		}
		return out.toByteArray()
	}

	private fun inflate(compressed: ByteArray): ByteArray {
		val inflater = Inflater()
		return try {
			inflater.setInput(compressed)
			val out = ByteArrayOutputStream()
			val buffer = ByteArray(8 * 1024)
			while (!inflater.finished()) {
				val read = inflater.inflate(buffer)
				if (read == 0) break
				out.write(buffer, 0, read)
			}
			out.toByteArray()
		} finally {
			inflater.end()
		}
	}
}

/** The text-level cleanup applied to mapped card fields. */
internal object HtmlText {

	data class Cleaned(val text: String, val changed: Boolean)

	private val scriptOrStyle = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
	private val comment = Regex("(?s)<!--.*?-->")
	private val breakTag = Regex("(?i)<\\s*br\\s*/?\\s*>")
	private val blockClose = Regex("(?i)</\\s*(p|div|li|h[1-6]|tr|blockquote)\\s*>")
	private val listOpen = Regex("(?i)<\\s*li\\b[^>]*>")
	private val startTag = Regex("(?is)<\\s*(START)\\s*>", RegexOption.IGNORE_CASE)
	private val anyTag = Regex("(?s)<[^>]*>")
	private val entity = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")
	private val extraBlankLines = Regex("\\n{3,}")

	private val entities = mapOf(
		"amp" to "&",
		"lt" to "<",
		"gt" to ">",
		"quot" to "\"",
		"apos" to "'",
		"nbsp" to " ",
		"hellip" to "…",
		"mdash" to "—",
		"ndash" to "–",
	)

	/**
	 * Convert the HTML a character card may carry in its text fields into plain
	 * text. Script and style *content* is dropped, not just the tags, because
	 * otherwise a stylesheet would be imported as prose.
	 */
	fun clean(value: String): Cleaned {
		if (value.isBlank()) return Cleaned(value, false)
		if (!value.contains('<') && !value.contains('&')) return Cleaned(value, false)
		val original = value
		var text = value.replace(scriptOrStyle, " ")
			.replace(comment, " ")
			.replace(startTag, "\n")
			.replace(breakTag, "\n")
			.replace(listOpen, "\n• ")
			.replace(blockClose, "\n")
			.replace(anyTag, "")
			.replace(entity) { match -> decodeEntity(match.groupValues[1]) }
			.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replace(extraBlankLines, "\n\n")
			.trim()
		if (text == original) return Cleaned(original, false)
		return Cleaned(text, true)
	}

	private fun decodeEntity(body: String): String {
		if (body.startsWith("#x", ignoreCase = true)) {
			return body.drop(2).toIntOrNull(16)?.let { codePointToString(it) } ?: "&$body;"
		}
		if (body.startsWith("#")) {
			return body.drop(1).toIntOrNull()?.let { codePointToString(it) } ?: "&$body;"
		}
		return entities[body.lowercase()] ?: "&$body;"
	}

	private fun codePointToString(codePoint: Int): String =
		if (codePoint in 1..0x10FFFF) String(Character.toChars(codePoint)) else ""
}

private fun JsonObject.stringOrNull(key: String): String? =
	(this[key] as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }

private fun JsonPrimitive.contentOrNull(): String? = if (this is JsonNull) null else content
