package com.inhuman.core.documents

import org.w3c.dom.Document as XmlDocument
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** A malformed or unsupported document, with a user-readable reason. */
class DocumentException(message: String) : Exception(message)

/**
 * The small zip/XML toolkit shared by DOCX and XLSX.
 *
 * OOXML is a package of parts; the writer must copy every part it does not
 * understand, or a macro, a comment or an embedded chart disappears without a
 * word. These helpers never expose a path or write to disk: bytes in, bytes out.
 */
internal object Ooxml {

	const val MAX_ENTRIES = 10_000
	const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
	const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

	fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
		if (bytes.size > DocumentLimits.MAX_BYTES) {
			throw DocumentException("The document is larger than ${DocumentLimits.MAX_BYTES / 1024 / 1024} MB.")
		}
		val entries = LinkedHashMap<String, ByteArray>()
		var total = 0L
		try {
			ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
				while (true) {
					val entry = zip.nextEntry ?: break
					if (entry.isDirectory) continue
					if (entries.size >= MAX_ENTRIES) {
						throw DocumentException("The document has too many package entries.")
					}
					val name = entry.name
					if (name.startsWith("/") || name.contains("..")) {
						throw DocumentException("The document contains an unsafe package path.")
					}
					val data = zip.readBounded(MAX_ENTRY_BYTES)
					total += data.size
					if (total > MAX_TOTAL_BYTES) {
						throw DocumentException("The document expands beyond the read limit.")
					}
					entries[name] = data
					zip.closeEntry()
				}
			}
		} catch (e: DocumentException) {
			throw e
		} catch (e: Exception) {
			throw DocumentException("The file is not a valid Office Open XML package.")
		}
		if (entries.isEmpty()) throw DocumentException("The document package is empty.")
		return entries
	}

	fun zip(entries: Map<String, ByteArray>): ByteArray {
		val out = ByteArrayOutputStream()
		ZipOutputStream(out).use { zip ->
			entries.forEach { (name, bytes) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return out.toByteArray()
	}

	/**
	 * Keep an existing `[Content_Types].xml` and add only what is missing.
	 *
	 * Replacing the part wholesale is how a DOCX write silently deletes the
	 * declaration that makes an image, chart or macro part legal. The template's
	 * declarations are copied through; the writer's own parts are appended only
	 * when their `PartName` is not already present.
	 */
	fun contentTypes(
		existing: ByteArray?,
		defaults: Map<String, String>,
		overrides: Map<String, String>,
	): ByteArray {
		if (existing == null) {
			return buildString {
				append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
				append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
				defaults.forEach { (extension, type) ->
					append("""<Default Extension="${escape(extension)}" ContentType="${escape(type)}"/>""")
				}
				overrides.forEach { (part, type) ->
					append("""<Override PartName="${escape(part)}" ContentType="${escape(type)}"/>""")
				}
				append("</Types>")
			}.toByteArray()
		}
		var xml = String(existing, Charsets.UTF_8)
		if (!xml.contains("Extension=\"rels\"")) {
			xml = xml.insertBeforeTypes(
				"""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""",
			)
		}
		if (!xml.contains("Extension=\"xml\"")) {
			xml = xml.insertBeforeTypes("""<Default Extension="xml" ContentType="application/xml"/>""")
		}
		overrides.forEach { (part, type) ->
			if (!xml.contains("PartName=\"${escape(part)}\"")) {
				xml = xml.insertBeforeTypes(
					"""<Override PartName="${escape(part)}" ContentType="${escape(type)}"/>""",
				)
			}
		}
		return xml.toByteArray()
	}

	private fun String.insertBeforeTypes(element: String): String {
		val close = lastIndexOf("</Types>")
		return if (close < 0) {
			this
		} else {
			substring(0, close) + element + substring(close)
		}
	}

	fun parse(bytes: ByteArray): XmlDocument {
		try {
			val factory = DocumentBuilderFactory.newInstance()
			factory.isNamespaceAware = true
			// A document is untrusted input. External entities and DTDs are how
			// an XML parser becomes a file reader or an SSRF client.
			runCatching {
				factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
			}
			runCatching {
				factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
			}
			runCatching {
				factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
			}
			runCatching {
				factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
			}
			runCatching {
				factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
			}
			factory.isExpandEntityReferences = false
			return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
		} catch (e: Exception) {
			throw DocumentException("The document contains invalid XML.")
		}
	}

	fun element(node: Node): Element? = node as? Element

	fun children(element: Element): List<Element> {
		val result = ArrayList<Element>()
		var child = element.firstChild
		while (child != null) {
			(child as? Element)?.let(result::add)
			child = child.nextSibling
		}
		return result
	}

	fun child(element: Element, name: String): Element? =
		children(element).firstOrNull { it.localName == name }

	fun descendants(element: Element, name: String): List<Element> {
		val result = ArrayList<Element>()
		fun walk(node: Element) {
			children(node).forEach { child ->
				if (child.localName == name) result.add(child)
				walk(child)
			}
		}
		walk(element)
		return result
	}

	fun attr(element: Element, name: String): String? {
		val attributes = element.attributes ?: return null
		attributes.getNamedItem(name)?.nodeValue?.let { return it }
		for (i in 0 until attributes.length) {
			val attribute = attributes.item(i)
			if (attribute.localName == name || attribute.nodeName.endsWith(":$name")) {
				return attribute.nodeValue
			}
		}
		return null
	}

	/** Text at `w:t`/`t` children, in document order. */
	fun text(element: Element, textElement: String = "t"): String = buildString {
		descendants(element, textElement).forEach { append(it.textContent.orEmpty()) }
	}.trim()

	fun escape(text: String): String = buildString(text.length + 16) {
		text.forEach { c ->
			when (c) {
				'&' -> append("&amp;")
				'<' -> append("&lt;")
				'>' -> append("&gt;")
				'"' -> append("&quot;")
				'\'' -> append("&apos;")
				else -> append(c)
			}
		}
	}

	private fun ZipInputStream.readBounded(limit: Long): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(8192)
		var total = 0L
		while (true) {
			val read = read(buffer)
			if (read <= 0) break
			total += read
			if (total > limit) throw DocumentException("A package part is larger than the read limit.")
			out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}
}