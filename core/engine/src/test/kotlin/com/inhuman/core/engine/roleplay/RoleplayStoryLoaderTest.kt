package com.inhuman.core.engine.roleplay

import com.inhuman.core.files.SessionWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reading a story folder is the user-facing twin of `rp_validate`, so these
 * tests pin the parts a dialog relies on: a card is required, every refusal
 * names the file or field at fault, unknown fields survive as warnings, and a
 * story without a scene is legal.
 */
class RoleplayStoryLoaderTest {

	@get:Rule
	val tmp = TemporaryFolder()

	private fun workspace(): Pair<SessionWorkspace, File> {
		val root = tmp.newFolder("workspace")
		return SessionWorkspace.open(root) to root
	}

	private fun story(root: File, folder: String, card: String, scene: String? = null): String {
		val dir = File(root, folder).apply { mkdirs() }
		File(dir, RoleplayStoryFiles.CARD).writeText(card)
		if (scene != null) File(dir, RoleplayStoryFiles.SCENE).writeText(scene)
		return folder
	}

	private val card = """{"name":"Archivist","description":"OLD-CARD","greeting":"You are late."}"""

	@Test
	fun `a folder with a card and a scene loads both`() {
		val (workspace, root) = workspace()
		val folder = story(
			root,
			"rp/archivist",
			card,
			"""{"world":"LEGACY","premise":"a flood"}""",
		)

		val outcome = RoleplayStoryLoader.load(workspace, folder) as StoryOutcome.Ok

		assertEquals("Archivist", outcome.story.card.name)
		assertEquals("LEGACY", outcome.story.scene?.world)
		assertEquals("OLD-CARD", outcome.story.card.description)
		assertTrue(outcome.story.sceneJson.contains("LEGACY"))
		assertTrue(outcome.story.warnings.isEmpty())
	}

	@Test
	fun `a scene file is optional and an absent one becomes an empty scene`() {
		val (workspace, root) = workspace()
		val folder = story(root, "rp/plain", card)

		val outcome = RoleplayStoryLoader.load(workspace, folder) as StoryOutcome.Ok

		assertNull(outcome.story.scene)
		assertEquals("{}", outcome.story.sceneJson)
	}

	@Test
	fun `a card file is required and the refusal names it`() {
		val (workspace, root) = workspace()
		val dir = File(root, "rp/empty").apply { mkdirs() }
		File(dir, RoleplayStoryFiles.SCENE).writeText("""{"world":"w"}""")

		val outcome = RoleplayStoryLoader.load(workspace, "rp/empty")

		assertTrue(outcome is StoryOutcome.Failed)
		assertTrue((outcome as StoryOutcome.Failed).reason.contains("card.json"))
	}

	@Test
	fun `malformed card JSON is invalid and does not echo the input`() {
		val (workspace, root) = workspace()
		val folder = story(root, "rp/bad", """{"description": ["TOP-SECRET"]}""")

		val outcome = RoleplayStoryLoader.load(workspace, folder) as StoryOutcome.Invalid
		val rendered = outcome.issues.joinToString { it.render() }

		assertTrue(
			rendered.contains("not valid JSON") || rendered.contains("JSON type") ||
				rendered.contains("JSON shape"),
		)
		assertTrue(!rendered.contains("TOP-SECRET"))
	}

	@Test
	fun `an over-limit card is invalid and names the field`() {
		val (workspace, root) = workspace()
		val huge = """{"description": "${"x".repeat(RoleplayLimits.DESCRIPTION_MAX + 1)}"}"""
		val folder = story(root, "rp/long", huge)

		val outcome = RoleplayStoryLoader.load(workspace, folder) as StoryOutcome.Invalid

		assertTrue(outcome.issues.any { it.field == "description" && !it.warning })
	}

	@Test
	fun `an unknown card field is a warning but the story loads`() {
		val (workspace, root) = workspace()
		val folder = story(
			root,
			"rp/newer",
			"""{"greeting":"hi","futureField":"kept by the copy"}""",
		)

		val outcome = RoleplayStoryLoader.load(workspace, folder) as StoryOutcome.Ok

		assertTrue(outcome.story.warnings.any { it.field == "futureField" })
		assertTrue(outcome.story.cardJson.contains("futureField"))
	}

	@Test
	fun `a malformed scene file invalidates the story`() {
		val (workspace, root) = workspace()
		val folder = story(root, "rp/badscene", card, """{"world": 3}""")

		val outcome = RoleplayStoryLoader.load(workspace, folder)

		assertTrue(outcome is StoryOutcome.Invalid)
	}

	@Test
	fun `listing shows folders, marks the ones with a card, and can go up`() {
		val (workspace, root) = workspace()
		story(root, "rp/archivist", card)
		File(root, "rp/notes").mkdirs()
		File(root, "rp/readme.txt").writeText("not a folder")

		val rootListing = RoleplayStoryLoader.list(workspace, "") as StoryListing.Ok
		assertTrue(rootListing.entries.any { it.path == "rp" && !it.hasCard })
		assertTrue(rootListing.entries.none { it.name == "readme.txt" })

		val rpListing = RoleplayStoryLoader.list(workspace, "rp") as StoryListing.Ok
		assertEquals("rp", rpListing.path)
		assertTrue(rpListing.entries.single { it.name == "archivist" }.hasCard)
		assertTrue(rpListing.entries.single { it.name == "notes" }.hasCard.not())

		val folderListing = RoleplayStoryLoader.list(workspace, "rp/archivist") as StoryListing.Ok
		assertTrue(folderListing.hasCardHere)
		assertTrue(RoleplayStoryLoader.hasCard(workspace, "rp/archivist"))
	}

	@Test
	fun `a missing folder fails instead of listing the root`() {
		val (workspace, _) = workspace()

		assertTrue(RoleplayStoryLoader.list(workspace, "rp/nope") is StoryListing.Failed)
		assertTrue(RoleplayStoryLoader.load(workspace, "rp/nope") is StoryOutcome.Failed)
	}
}
