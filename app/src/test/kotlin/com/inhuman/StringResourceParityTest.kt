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
