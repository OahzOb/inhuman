package com.inhuman.core.common.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RollingLogTest {

	@get:Rule
	val temp = TemporaryFolder()

	private fun log(maxBytes: Long = 1_000, archives: Int = 3) =
		RollingLog(temp.newFolder(), maxBytes = maxBytes, archives = archives)

	@Test
	fun `append writes lines in the order they arrived`() {
		val log = log()
		log.start()

		log.append("one")
		log.append("two")

		assertEquals("one\ntwo\n", log.readText())
	}

	@Test
	fun `a full file rotates into one archive`() {
		val log = log(maxBytes = 20)
		log.start()

		log.append("aaaaaaaaaa")
		log.append("bbbbbbbbbb")
		log.append("cccccccccc")

		val files = log.existingFiles()
		assertEquals(2, files.size)
		assertTrue(files[0].name.endsWith("1.log"))
		assertTrue(files[0].readText().contains("aaaaaaaaaa"))
		assertEquals("cccccccccc\n", log.readText().substringAfterLast("bbbbbbbbbb\n"))
	}

	@Test
	fun `the oldest archive is dropped when the budget is exhausted`() {
		val log = log(maxBytes = 1, archives = 2)
		log.start()

		for (line in listOf("first", "second", "third", "fourth")) log.append(line)

		val text = log.readText()
		assertFalse(text.contains("first"))
		assertTrue(text.contains("second"))
		assertTrue(text.contains("third"))
		assertTrue(text.contains("fourth"))
		assertEquals(3, log.existingFiles().size)
	}

	@Test
	fun `start rotates a file left over from the previous process`() {
		val directory = temp.newFolder()
		val current = File(directory, "app.log")
		current.writeText("x".repeat(100))

		val log = RollingLog(directory, maxBytes = 10)
		log.start()

		assertFalse(current.exists())
		assertTrue(File(directory, "app.1.log").exists())
	}

	@Test
	fun `clear removes the live file and every archive`() {
		val log = log(maxBytes = 1, archives = 2)
		log.start()
		for (line in listOf("one", "two", "three", "four")) log.append(line)
		assertTrue(log.sizeBytes() > 0)

		log.clear()

		assertEquals(0L, log.sizeBytes())
		assertTrue(log.existingFiles().isEmpty())
	}

	@Test
	fun `all files are ordered oldest first`() {
		val log = log(maxBytes = 1, archives = 2)
		log.start()
		for (line in listOf("one", "two", "three", "four")) log.append(line)

		val names = log.allFiles().map { it.name }
		assertEquals(listOf("app.2.log", "app.1.log", "app.log"), names)
	}
}
