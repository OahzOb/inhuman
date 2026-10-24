package com.inhuman.desktop

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The second process must be refused while the first holds the data directory.
 *
 * `tryLock` is per JVM as well as per file, so this test catches the failure
 * that matters: a second host opening the same SQLite file.
 */
class SingleInstanceTest {

	@get:Rule
	val tmp = TemporaryFolder()

	@Test
	fun `only one holder per data directory`() {
		val dir = tmp.newFolder("instance")

		val first = SingleInstance.acquire(dir)
		assertNotNull(first)
		assertNull("a second acquisition must be refused", SingleInstance.acquire(dir))

		first!!.close()
		val afterRelease = SingleInstance.acquire(dir)
		assertNotNull("closing the first holder must release the directory", afterRelease)
		afterRelease!!.close()
	}
}
