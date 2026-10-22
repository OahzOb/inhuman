package com.inhuman

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English and Chinese resource files must carry the same keys.
 *
 * A missing key is not a compile error: Android falls back to the default
 * resource, so an accidental half-translation looks correct in English and
 * mixed-language in Chinese. Lint's `MissingTranslation` check would catch it,
 * but lint is not part of this project's offline verification loop, and this is
 * a two-file comparison.
 *
 * The files are read from the source tree rather than through a `Context`
 * because the check is about the resource *set*, not about resolving one value,
 * and this way it runs on the JVM with no device and no Robolectric.
 */
class StringResourceParityTest {

	@Test
	fun `English and Chinese define the same keys`() {
		val english = keys("src/main/res/values/strings.xml")
		val chinese = keys("src/main/res/values-zh-rCN/strings.xml")

		assertEquals(
			"missing in values-zh-rCN: ${english - chinese}; missing in values: ${chinese - english}",
			english,
			chinese,
		)
	}

	@Test
	fun `a translation keeps the placeholders of its source`() {
		// `String.format` silently renders a missing placeholder as literal
		// text, and a changed index either crashes or shows the wrong value.
		// The copy pass touched every long warning in the Chinese file, so this
		// is the check that a reworded sentence still has the same arguments.
		val english = placeholders("src/main/res/values/strings.xml")
		val chinese = placeholders("src/main/res/values-zh-rCN/strings.xml")
		val mismatches = english.keys.intersect(chinese.keys).filter { name ->
			english[name] != chinese[name]
		}.sorted()
		val details = mismatches.joinToString("\n") { name ->
			"$name: en=${english[name]} zh=${chinese[name]}"
		}
		assertEquals("placeholder mismatch:\n$details", emptyList<String>(), mismatches)
	}

	/**
	 * Placeholder descriptors, with padding and width ignored.
	 *
	 * `%1$02d` and `%1$d` format the same argument; the Chinese count does not
	 * use zero padding because "已保存 00 个对话" is wrong in Chinese. The
	 * argument index and type are what must survive.
	 */
	private fun placeholderDescriptors(text: String): List<String> {
		val regex = Regex("""%(?:(\d+)\$)?0?\d*([sd])""")
		return regex.findAll(text).map { match ->
			val index = match.groupValues[1]
			val type = match.groupValues[2]
			if (index.isEmpty()) type else "$index:$type"
		}.sorted().toList()
	}

	private fun placeholders(path: String): Map<String, List<String>> {
		val document = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(File(path))
		val out = mutableMapOf<String, List<String>>()
		val nodes = document.getElementsByTagName("*")
		for (index in 0 until nodes.length) {
			val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
			when (element.tagName) {
				"string" -> out[element.getAttribute("name")] =
					placeholderDescriptors(element.textContent)
				"plurals" -> {
					val items = element.getElementsByTagName("item")
					val descriptors = sortedSetOf<String>()
					for (item in 0 until items.length) {
						val itemElement = items.item(item) as? org.w3c.dom.Element ?: continue
						descriptors += placeholderDescriptors(itemElement.textContent)
					}
					out[element.getAttribute("name")] = descriptors.toList()
				}
			}
		}
		return out
	}

	private fun keys(path: String): Set<String> {
		val document = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(File(path))
		val nodes = document.getElementsByTagName("*")
		val names = mutableSetOf<String>()
		for (index in 0 until nodes.length) {
			val element = nodes.item(index) as? org.w3c.dom.Element ?: continue
			if (element.tagName == "string" || element.tagName == "plurals") {
				names += element.getAttribute("name")
			}
		}
		return names
	}
}
