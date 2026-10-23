package com.inhuman.core.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The required-field check the consent dialogue depends on.
 *
 * The dialogue is the strongest warning the app shows, and showing it for a
 * call the tool will reject before connecting is both confusing and a way to
 * train the user to click through. These cases are the tool schemas' `required`
 * lists, checked against the same normalised object the tool runs with.
 */
class SshArgumentsTest {

	private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
		pairs.forEach { (key, value) -> put(key, value) }
	}

	@Test
	fun hostAndUsernameComeBeforeAnyToolSpecificField() {
		assertEquals("host", SshArguments.missingRequired("ssh_exec", args()))
		assertEquals(
			"username",
			SshArguments.missingRequired("ssh_exec", args("host" to "example.test")),
		)
	}

	@Test
	fun execRequiresACommand() {
		assertEquals(
			"command",
			SshArguments.missingRequired(
				"ssh_exec",
				args("host" to "example.test", "username" to "root"),
			),
		)
		assertNull(
			SshArguments.missingRequired(
				"ssh_exec",
				args("host" to "example.test", "username" to "root", "command" to "uptime"),
			),
		)
	}

	@Test
	fun transferToolsNameBothMissingPaths() {
		val connection = arrayOf("host" to "example.test", "username" to "root")
		assertEquals(
			"localPath and remotePath",
			SshArguments.missingRequired("ssh_upload", args(*connection)),
		)
		assertEquals(
			"remotePath and localPath",
			SshArguments.missingRequired("ssh_download", args(*connection)),
		)
	}

	@Test
	fun writeFileRequiresPathAndContent() {
		val connection = arrayOf("host" to "example.test", "username" to "root")
		assertEquals(
			"remotePath",
			SshArguments.missingRequired("ssh_write_file", args(*connection)),
		)
		assertEquals(
			"content",
			SshArguments.missingRequired(
				"ssh_write_file",
				args(*connection, "remotePath" to "/etc/motd"),
			),
		)
		// An empty file is content the model may legitimately write; only a
		// missing field is malformed.
		assertNull(
			SshArguments.missingRequired(
				"ssh_write_file",
				args(*connection, "remotePath" to "/etc/motd", "content" to ""),
			),
		)
	}

	@Test
	fun probeNeedsOnlyConnectionDetailsButListAndReadNeedAPath() {
		val connection = arrayOf("host" to "example.test", "username" to "root")
		assertNull(SshArguments.missingRequired("ssh_probe", args(*connection)))
		assertEquals("remotePath", SshArguments.missingRequired("ssh_list", args(*connection)))
		assertEquals(
			"remotePath",
			SshArguments.missingRequired("ssh_read_file", args(*connection)),
		)
	}

	@Test
	fun aWrappedEnvelopeIsUnwrappedBeforeTheCheck() {
		val wrapped = buildJsonObject {
			put(
				"arguments",
				"""{"host":"example.test","username":"root","command":"uptime"}""",
			)
		}
		assertNull(SshArguments.missingRequired("ssh_exec", wrapped))
	}

	@Test
	fun hostnameIsAcceptedAsHost() {
		val aliased = args("hostname" to "example.test", "username" to "root", "command" to "id")
		assertNull(SshArguments.missingRequired("ssh_exec", aliased))
	}
}
