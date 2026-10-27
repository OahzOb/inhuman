package com.inhuman.ui.chat

import com.inhuman.core.ai.tools.ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversation-tool ceiling and merge.
 *
 * These are the rules that decide whether a tool added to an assistant reaches
 * an existing conversation, and whether switching SSH off can silently drop a
 * stored remote selection. Both are pure; no Compose or database is involved.
 */
class ConversationToolsTest {

	private fun names(plan: ConversationToolPlan) = plan.available.map { it.name }

	@Test
	fun `a null assistant ceiling means every non-remote tool`() {
		val plan = conversationToolPlan(sshEnabled = true, agentTools = null, conversationTools = null)
		assertTrue("read_file" in names(plan))
		assertTrue("write_file" in names(plan))
		assertFalse("ssh_exec is not part of a null ceiling", "ssh_exec" in names(plan))
		assertEquals(names(plan).toSet(), plan.selected)
	}

	@Test
	fun `an assistant tool becomes available to an existing conversation`() {
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = listOf("read_file", "ssh_upload"),
			conversationTools = listOf("read_file"),
		)
		assertTrue("ssh_upload is in the assistant's explicit set", "ssh_upload" in names(plan))
		assertFalse("an unrelated tool is not invented", "write_file" in names(plan))
		assertEquals(setOf("read_file"), plan.selected)
	}

	@Test
	fun `a conversation keeps a tool the assistant no longer has`() {
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = listOf("read_file"),
			conversationTools = listOf("read_file", "write_file"),
		)
		assertTrue("write_file was already in the conversation", "write_file" in names(plan))
		assertEquals(setOf("read_file", "write_file"), plan.selected)
	}

	@Test
	fun `a null conversation selection selects every non-remote tool`() {
		val plan = conversationToolPlan(sshEnabled = true, agentTools = null, conversationTools = null)
		assertFalse("ssh_exec" in plan.selected)
		assertTrue("read_file" in plan.selected)
		assertTrue("write_file" in plan.selected)
	}

	@Test
	fun `remote tools are available only when explicitly named`() {
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = listOf("read_file", "ssh_exec"),
			conversationTools = listOf("read_file", "ssh_exec"),
		)
		assertTrue("ssh_exec" in names(plan))
		assertTrue("ssh_exec" in plan.selected)
	}

	@Test
	fun `ssh off hides remote rows but keeps the stored name`() {
		val current = listOf("read_file", "ssh_exec")
		val plan = conversationToolPlan(
			sshEnabled = false,
			agentTools = listOf("ssh_exec", "write_file"),
			conversationTools = current,
		)
		assertFalse("ssh_exec must not be shown while disabled", "ssh_exec" in names(plan))

		val merged = mergeConversationTools(
			current = current,
			available = plan.available,
			checked = setOf("read_file", "write_file"),
		)
		assertTrue("the hidden remote selection survives", "ssh_exec" in merged)
		assertTrue("the new local tool is stored", "write_file" in merged)
	}

	@Test
	fun `merge stores an explicit selection in catalogue order`() {
		val available = conversationToolPlan(true, null, null).available
		val merged = mergeConversationTools(
			current = null,
			available = available,
			checked = setOf("write_file", "read_file"),
		)
		assertEquals(ToolCatalog.names.filter { it in merged }, merged)
		assertEquals(setOf("read_file", "write_file"), merged.toSet())
	}

	@Test
	fun `empty selection is an intentional chat-only conversation`() {
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = listOf("read_file", "write_file"),
			conversationTools = emptyList(),
		)
		assertTrue(plan.selected.isEmpty())
		val merged = mergeConversationTools(emptyList(), plan.available, emptySet())
		assertTrue(merged.isEmpty())
	}

	@Test
	fun `a failed fence check hides workspace tools but keeps their stored names`() {
		val current = listOf("read_file", "ssh_exec", "web_search")
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = null,
			conversationTools = current,
			workspaceToolsEnabled = false,
		)

		assertFalse("read_file needs the workspace", "read_file" in names(plan))
		assertFalse("SSH transfers go through the workspace", "ssh_exec" in names(plan))
		assertTrue("the web does not need the workspace", "web_search" in names(plan))

		val merged = mergeConversationTools(
			current = current,
			available = plan.available,
			checked = setOf("web_search"),
		)
		assertTrue("the hidden file tool survives", "read_file" in merged)
		assertTrue("the hidden remote tool survives", "ssh_exec" in merged)
	}

	@Test
	fun `adding a read-only ssh tool does not reset consent`() {
		val before = listOf("ssh_download")
		assertFalse(widensDangerousSsh(before, before + "ssh_list"))
		assertFalse(widensDangerousSsh(before, before + "ssh_read_file"))
	}

	@Test
	fun `adding upload, write or exec resets consent`() {
		val before = listOf("ssh_download")
		assertTrue(widensDangerousSsh(before, before + "ssh_upload"))
		assertTrue(widensDangerousSsh(before, before + "ssh_write_file"))
		assertTrue(widensDangerousSsh(before, before + "ssh_exec"))
		assertTrue(widensDangerousSsh(before, listOf("ssh_upload", "ssh_exec")))
	}

	@Test
	fun `removing or repeating a tool does not reset consent`() {
		val before = listOf("ssh_exec", "ssh_upload", "ssh_write_file", "ssh_download")
		assertFalse(widensDangerousSsh(before, listOf("ssh_download")))
		assertFalse(widensDangerousSsh(before, before))
	}

	@Test
	fun `a null selection permits no remote tool`() {
		assertTrue(permittedRemoteTools(null).isEmpty())
		assertEquals(
			setOf("ssh_download", "ssh_list"),
			permittedRemoteTools(listOf("ssh_download", "ssh_list", "read_file")),
		)
	}
}
