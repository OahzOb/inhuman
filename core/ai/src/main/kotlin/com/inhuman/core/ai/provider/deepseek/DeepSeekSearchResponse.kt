package com.inhuman.core.ai.provider.deepseek

import com.inhuman.core.ai.tools.SearchResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads DeepSeek's native web search out of an Anthropic-format Messages reply.
 *
 * ## Why the reply is parsed rather than requested
 *
 * DeepSeek exposes **no search endpoint**. Its web search is a *server-side
 * tool*: you declare it on a Messages request, the model decides to use it, and
 * the results come back as content blocks beside the prose. There is nothing to
 * call and nothing to ask for -- only something to read once the turn is over.
 *
 * That has a consequence worth stating plainly, because it shapes the whole
 * design: **one search costs a whole model turn** in latency and tokens. It is
 * not a lookup, it is a conversation with one answer in it.
 *
 * ## Why the prose is discarded
 *
 * The provider writes a summary alongside the results, and it is not used as an
 * answer here. A search result set is evidence; a paragraph around it is a
 * claim about that evidence, produced by the same model the results are being
 * handed to. Passing both would let the caller cite a synthesis as though it
 * were a source. Only the structured blocks survive this function.
 *
 * ## Why `content` blocks rather than text scraping
 *
 * The alternative -- regex the URLs out of the model's prose -- produces
 * citations that the model wrote rather than links the search returned, which is
 * exactly the failure mode where a citation looks authoritative and points
 * nowhere. The structured blocks are the only part of the reply that came from
 * the search.
 */
internal object DeepSeekSearchResponse {

	/** The block type carrying one search's results. */
	private const val RESULT_BLOCK = "web_search_tool_result"

	/** The item type inside it. */
	private const val RESULT_ITEM = "web_search_result"

	/**
	 * Pull every source out of a Messages reply.
	 *
	 * @return de-duplicated by URL, in the order the searches returned them.
	 *   Empty when the model answered without searching, which is a legitimate
	 *   outcome and not an error -- it means the model decided it already knew.
	 */
	fun parse(body: String): List<SearchResult> {
		val root = runCatching { DeepSeekJson.parseToJsonElement(body) as? JsonObject }
			.getOrNull() ?: return emptyList()

		val blocks = (root["content"] as? JsonArray) ?: return emptyList()

		val seen = HashSet<String>()
		val out = mutableListOf<SearchResult>()

		for (block in blocks) {
			val obj = block as? JsonObject ?: continue
			if (obj.str("type") != RESULT_BLOCK) continue

			val items = (obj["content"] as? JsonArray) ?: continue
			for (element in items) {
				val item = element as? JsonObject ?: continue
				if (item.str("type") != RESULT_ITEM) continue

				val rawUrl = item.str("url")?.takeIf { it.isNotBlank() } ?: continue
				val url = cleanUrl(rawUrl)

				// De-duplicated across all searches in the turn, not within one
				// block: the model often searches twice and gets an overlapping
				// set, and showing the same page twice wastes the reader's
				// attention on a duplicate rather than a second source.
				if (!seen.add(url)) continue

				out += SearchResult(
					title = item.str("title")?.takeIf { it.isNotBlank() } ?: url,
					url = url,
					snippet = citedText(obj, rawUrl),
					publishedAt = item.str("page_age")?.takeIf { it.isNotBlank() },
				)
			}
		}

		return out
	}

	/**
	 * The excerpt for one result, when the reply carries one.
	 *
	 * DeepSeek keys cited text by URL rather than nesting it in the result, and
	 * only some results have any. Absent is normal and stays absent -- an empty
	 * string would make "no excerpt" and "an empty excerpt" look the same to
	 * whatever renders this.
	 */
	private fun citedText(block: JsonObject, rawUrl: String): String? {
		val cited = (block["cited_text"] as? JsonObject) ?: return null
		val forUrl = (cited[rawUrl] as? JsonArray) ?: return null
		val joined = forUrl
			.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
			.filter { it.isNotBlank() }
			.joinToString(" ")
			.replace(Regex("\\s+"), " ")
			.trim()
		return joined.takeIf { it.isNotEmpty() }
	}

	/**
	 * Tracking parameters removed from a result URL.
	 *
	 * Not cosmetic. DeepSeek's results arrive carrying campaign parameters from
	 * whoever ranked for the query -- a live search for a Kotlin release
	 * returned `?via=Knowgenerativeai.com&utm_source=Knowgenerativeai.com&...`.
	 * Handing those to a model means it cites them back to the user, and
	 * following one tells a third party which query found them.
	 *
	 * Only parameters whose sole purpose is tracking are dropped. Anything
	 * unknown is kept, because a removed parameter that *was* meaningful turns a
	 * working link into a broken one, and that is the worse failure.
	 */
	internal fun cleanUrl(raw: String): String {
		val trimmed = raw.trim()
		val queryStart = trimmed.indexOf('?')
		if (queryStart < 0) return trimmed

		val fragmentStart = trimmed.indexOf('#', queryStart).let { if (it < 0) trimmed.length else it }
		val base = trimmed.substring(0, queryStart)
		val fragment = trimmed.substring(fragmentStart)
		val query = trimmed.substring(queryStart + 1, fragmentStart)

		val kept = query.split('&')
			.filter { it.isNotBlank() }
			.filterNot { pair ->
				val name = pair.substringBefore('=').lowercase()
				name in TRACKING || name.startsWith("utm_")
			}

		return if (kept.isEmpty()) base + fragment else "$base?${kept.joinToString("&")}$fragment"
	}

	/**
	 * Parameters that exist only to identify a visitor or a campaign.
	 *
	 * Deliberately a fixed list rather than a pattern: `ref` and `source` are
	 * excluded because plenty of sites use them to select content rather than to
	 * track a reader, and dropping them would break those links.
	 */
	private val TRACKING = setOf(
		"gclid", "fbclid", "msclkid", "dclid", "yclid", "twclid",
		"mc_cid", "mc_eid", "igshid", "mkt_tok", "vero_id", "vero_conv",
		"_hsenc", "_hsmi", "hsctatracking",
		"spm", "scm", "via", "ref_src", "trk", "trk_params",
	)

	private fun JsonObject.str(key: String): String? =
		(this[key] as? JsonPrimitive)?.contentOrNull
}
