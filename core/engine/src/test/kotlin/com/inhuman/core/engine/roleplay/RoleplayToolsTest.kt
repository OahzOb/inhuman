package com.inhuman.core.engine.roleplay

import com.inhuman.core.files.SessionWorkspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The authoring helpers behave the way the scene flow will: a valid file is
 * reported as valid, an invalid one names the field, and neither output quotes
 * the file's prose.
 */
class RoleplayToolsTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun arguments(json: String) = Json.parseToJsonElement(json).jsonObject

	private fun write(workspace: SessionWorkspace, path: String, text: String): File {
		val file = File(workspace.rootPath(), path)
		file.parentFile?.mkdirs()
		file.writeText(text)
		return file
	}

	@Test
	fun `rp_format returns the published format`() {
		val result = runBlocking { RoleplayFormatTool().execute(arguments("{}")) }

		assertFalse(result.isError)
		assertEquals(RoleplaySpec.text, result.content)
		assertEquals(RoleplayFormatTool.NAME, "rp_format")
		assertNotNull(result.persistedContent)
		assertTrue(result.persistedContent!!.contains(RoleplaySpec.FORMAT))
	}

	@Test
	fun `a valid card file is VALID and does not echo its prose`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("valid"))
		write(workspace, "rp/story/card.json", RoleplaySpec.exampleCard)

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"rp/story/card.json","kind":"card"}"""),
			)
		}

		assertFalse(result.isError)
		assertTrue(result.content.startsWith("VALID"))
		assertFalse("the result must not quote card text", result.content.contains("Archivist"))
		assertTrue(result.persistedContent!!.contains("valid"))
	}

	@Test
	fun `an invalid card file lists the blocking field`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("invalid"))
		write(
			workspace,
			"rp/card.json",
			"""{"description": "${"x".repeat(RoleplayLimits.DESCRIPTION_MAX + 1)}"}""",
		)

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"rp/card.json"}"""),
			)
		}

		assertFalse(result.isError)
		assertTrue(result.content.startsWith("PROBLEMS"))
		assertTrue(result.content.contains("description"))
		assertTrue(result.persistedContent!!.contains("blocking"))
	}

	@Test
	fun `a missing file is a tool error, not an invalid file`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("missing"))

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"rp/nope/card.json","kind":"card"}"""),
			)
		}

		assertTrue(result.isError)
		assertTrue(result.content.contains("no such file"))
	}

	@Test
	fun `a path outside the workspace is refused`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("outside"))

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"../outside.json","kind":"card"}"""),
			)
		}

		assertTrue(result.isError)
	}

	@Test
	fun `a story folder needs card json and accepts an optional scene`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("story"))
		write(workspace, "rp/story/card.json", RoleplaySpec.exampleCard)
		write(workspace, "rp/story/scene.json", RoleplaySpec.exampleScene)
		val tool = RoleplayValidateTool(workspace)

		val withScene = runBlocking {
			tool.execute(arguments("""{"path":"rp/story","kind":"story"}"""))
		}
		assertFalse(withScene.isError)
		assertTrue(withScene.content.startsWith("VALID"))
		assertTrue(withScene.content.contains("card+scene") || withScene.content.contains("scene.json"))

		val withoutScene = SessionWorkspace.open(tmp.newFolder("story-only-card"))
		write(withoutScene, "rp/story/card.json", RoleplaySpec.exampleCard)
		val cardOnly = runBlocking {
			RoleplayValidateTool(withoutScene).execute(
				arguments("""{"path":"rp/story","kind":"story"}"""),
			)
		}
		assertFalse(cardOnly.isError)
		assertTrue(cardOnly.content.startsWith("VALID"))
		assertTrue(cardOnly.content.contains("scene.json absent"))
	}

	@Test
	fun `a story folder without card json is a problem, not a crash`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("no-card"))
		write(workspace, "rp/story/scene.json", RoleplaySpec.exampleScene)

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"rp/story","kind":"story"}"""),
			)
		}

		assertFalse(result.isError)
		assertTrue(result.content.contains("missing card.json"))
	}

	@Test
	fun `an unknown kind is refused with the allowed values`() {
		val workspace = SessionWorkspace.open(tmp.newFolder("kind"))
		write(workspace, "rp/card.json", RoleplaySpec.exampleCard)

		val result = runBlocking {
			RoleplayValidateTool(workspace).execute(
				arguments("""{"path":"rp/card.json","kind":"nonsense"}"""),
			)
		}

		assertTrue(result.isError)
		assertTrue(result.content.contains("card, scene or story"))
	}
}
