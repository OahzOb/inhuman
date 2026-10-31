package com.inhuman.core.ai.provider.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `/models` parser, against the provider's actual response.
 *
 * ## Why the fixture is verbatim
 *
 * The shape of this JSON is not ours. It is documented per field, not as a
 * schema, and the app depends on four things inside it: which ids can be sent,
 * which models accept images, what context window each has, and which reasoning
 * effort levels each advertises. If any of those moves, the app breaks in ways
 * that look like its own bugs -- a model missing from the picker, an image
 * attachment offered on a model that rejects it, an effort level sent as a
 * value the API refuses.
 *
 * So the fixture below is copied from a live `GET /models` rather than written
 * by hand. Inventing one would test the parser against my idea of the response
 * instead of the response, which is the failure mode this is meant to catch.
 *
 * It has already earned its keep once: the ids are `deepseek-flash` and
 * `deepseek-v4-pro` while the display names are `DeepSeek-V4.1-Flash` and
 * `DeepSeek-V4-Pro`. Code that had guessed one from the other -- a reasonable
 * assumption about a boring API -- would send a display name as a model id.
 */
class DeepSeekModelsTest {

	/** Copied from a live GET https://api.deepseek.com/models. */
	private val liveResponse = """
		{
		  "object": "list",
		  "data": [
		    {
		      "id": "deepseek-flash",
		      "object": "model",
		      "owned_by": "deepseek",
		      "name": "DeepSeek-V4.1-Flash",
		      "context_window": 1048576,
		      "max_output_tokens": 393216,
		      "input_modalities": ["text", "image"],
		      "output_modalities": ["text"],
		      "effort": {
		        "supported_levels": ["low", "high", "max"],
		        "default_level": "high"
		      },
		      "api_capabilities": {
		        "anthropic_messages": { "system_prompt_update": "in-history" }
		      }
		    },
		    {
		      "id": "deepseek-v4-pro",
		      "object": "model",
		      "owned_by": "deepseek",
		      "name": "DeepSeek-V4-Pro",
		      "context_window": 1048576,
		      "max_output_tokens": 393216,
		      "input_modalities": ["text"],
		      "output_modalities": ["text"],
		      "effort": {
		        "supported_levels": ["low", "high", "max"],
		        "default_level": "high"
		      },
		      "api_capabilities": {
		        "anthropic_messages": { "system_prompt_update": "leading-only" }
		      }
		    }
		  ]
		}
	""".trimIndent()

	@Test
	fun `reads every model in the list`() {
		val models = DeepSeekModels.parse(liveResponse)

		assertEquals(2, models.size)
		assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.id })
	}

	@Test
	fun `keeps the id and the display name apart`() {
		val flash = DeepSeekModels.parse(liveResponse).first { it.id == "deepseek-flash" }

		// The id is what goes on the wire; the name is what a person reads.
		// Conflating them sends "DeepSeek-V4.1-Flash" as a model, which the API
		// does not know.
		assertEquals("DeepSeek-V4.1-Flash", flash.displayName)
		assertTrue(flash.id != flash.displayName)
	}

	@Test
	fun `reports vision per model rather than globally`() {
		val models = DeepSeekModels.parse(liveResponse).associateBy { it.id }

		// This is the reason the list is fetched. Offering an image attachment
		// on a text-only model produces a request the provider rejects, and the
		// user sees an error that names nothing they did.
		assertTrue(models.getValue("deepseek-flash").supportsVision)
		assertTrue(!models.getValue("deepseek-v4-pro").supportsVision)
	}

	@Test
	fun `reads the context window and output limit`() {
		val flash = DeepSeekModels.parse(liveResponse).first { it.id == "deepseek-flash" }

		assertEquals(1_048_576, flash.contextWindow)
		assertEquals(393_216, flash.maxOutputTokens)
	}

	@Test
	fun `reads the effort levels from the nested object`() {
		val flash = DeepSeekModels.parse(liveResponse).first { it.id == "deepseek-flash" }

		// A picker populated from anything else would offer a level the API
		// does not accept. "max" in particular is easy to assume away.
		assertEquals(listOf("low", "high", "max"), flash.supportedEffortLevels)
		assertEquals("high", flash.defaultEffortLevel)
	}

	// --- what the parser must survive -----------------------------------------

	@Test
	fun `a new field does not break the list`() {
		// Providers add fields. This one is nested inside a model object, where
		// a strict decoder would fail the whole response rather than one entry.
		val withExtra = liveResponse.replace(
			""""owned_by": "deepseek",""",
			""""owned_by": "deepseek", "brand_new": { "nested": [1, 2, 3] },""",
		)

		assertEquals(2, DeepSeekModels.parse(withExtra).size)
	}

	@Test
	fun `a model with no effort block is still offered`() {
		// A non-reasoning model would plausibly omit `effort` entirely, and
		// dropping it from the picker would hide a model that works fine.
		val noEffort = """
			{"data":[{"id":"plain","name":"Plain","context_window":8000,"input_modalities":["text"]}]}
		""".trimIndent()

		val model = DeepSeekModels.parse(noEffort).single()

		assertEquals("plain", model.id)
		assertEquals(emptyList<String>(), model.supportedEffortLevels)
		assertNull(model.defaultEffortLevel)
	}

	@Test
	fun `an entry with no id is dropped rather than sent`() {
		// A model that cannot be named cannot be requested. Keeping it would put
		// an unselectable row in the picker.
		val anonymous = """{"data":[{"name":"No id","context_window":1000},{"id":"real"}]}"""

		val models = DeepSeekModels.parse(anonymous)

		assertEquals(listOf("real"), models.map { it.id })
	}

	@Test
	fun `a wrongly typed field becomes null instead of failing`() {
		// Defensive rather than pedantic: if `context_window` ever arrives as a
		// string, throwing would take the whole picker down over a field the app
		// only displays.
		val wrongTypes = """
			{"data":[{"id":"m","context_window":"a lot","max_output_tokens":null,"name":42}]}
		""".trimIndent()

		val model = DeepSeekModels.parse(wrongTypes).single()

		assertEquals("m", model.id)
		assertNull(model.contextWindow)
		assertNull(model.maxOutputTokens)
		assertNull(model.displayName)
	}

	@Test
	fun `openrouter architecture fields are read as a fallback`() {
		val body = """
			{"data":[{"id":"openai/gpt-x","name":"GPT X",
			  "architecture":{"context_length":128000,"input_modalities":["text","image"]}}]}
		""".trimIndent()

		val model = DeepSeekModels.parse(body).single()

		assertEquals(128000, model.contextWindow)
		assertTrue(model.supportsVision)
	}

	@Test
	fun `opencode limit and modalities are read`() {
		// Modelled on a live GET https://opencode.ai/zen/go/v1/models; the
		// fields the parser reads are copied verbatim. The gateway describes
		// context and modalities under `limit` and `modalities` rather than
		// DeepSeek's flat fields. Without this fallback every OpenCode model
		// would look text-only and with no context window.
		val body = """
			{"object":"list","data":[{
			  "id":"gpt-6-luna","object":"model","name":"GPT 6 Luna","type":"chat",
			  "modalities":{"input":["text","image"],"output":["text"]},
			  "limit":{"context":272000,"output":32000}
			}]}
		""".trimIndent()

		val model = DeepSeekModels.parse(body).single()

		assertEquals("GPT 6 Luna", model.displayName)
		assertEquals(272000, model.contextWindow)
		assertEquals(32000, model.maxOutputTokens)
		assertTrue(model.supportsVision)
	}

	@Test
	fun `an empty list is empty rather than an error`() {
		// Compared by size: JUnit's assertEquals is heavily overloaded and
		// inference cannot resolve a bare emptyList() on either side.
		assertEquals(0, DeepSeekModels.parse("""{"object":"list","data":[]}""").size)
	}

	@Test
	fun `a body with no data key yields nothing`() {
		// A provider having a bad day should empty the picker, not crash the
		// screen that shows it.
		assertEquals(0, DeepSeekModels.parse("""{"object":"list"}""").size)
	}
}
