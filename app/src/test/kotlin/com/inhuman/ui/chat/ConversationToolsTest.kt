package com.inhuman.ui.chat

import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.ui.common.ToolFamily
import com.inhuman.ui.common.toolFamilyOf
import com.inhuman.ui.common.toolGroups
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
		assertFalse("read_pdf_vision is opt-in", ToolCatalog.PDF_VISION_TOOL in plan.selected)
		// Every opt-in tool is offered but unchecked: the SSH ones because a
		// null ceiling predates them, `ssh_hosts` and the paid follow-ups
		// because they are chosen, never inherited.
		val optIn = ToolCatalog.all.filter { it.optIn }.map { it.name }.toSet()
		assertTrue("ssh_hosts is opt-in", "ssh_hosts" in optIn)
		assertEquals(names(plan).toSet() - optIn, plan.selected)
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

	@Test
	fun `bilibili reads are hidden while their switch is off and never inherited`() {
		val off = conversationToolPlan(sshEnabled = false, agentTools = null, conversationTools = null)
		assertFalse("bili_search" in names(off))

		val on = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
			bilibiliEnabled = true,
		)
		val bilibili = ToolCatalog.all
			.filter { it.gatedByBilibili && !it.bilibiliLoginRequired }
			.map { it.name }
			.toSet()
		assertEquals("the anonymous read rows appear only with the switch", bilibili, names(on).toSet() - names(off).toSet())
		// A null "all" is not consent to a new external read: the rows are
		// offered, but unchecked, and only a stored name can turn one on.
		assertTrue(bilibili.none { it in on.selected })
	}

	@Test
	fun `transcript row appears only with the switch and the login`() {
		val withoutLogin = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
			bilibiliEnabled = true,
		)
		assertFalse("bili_transcript" in names(withoutLogin))

		val withLogin = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
			bilibiliEnabled = true,
			bilibiliLoggedIn = true,
		)
		assertTrue("bili_transcript" in names(withLogin))
		assertFalse("the opt-in rule still applies", "bili_transcript" in withLogin.selected)
	}

	@Test
	fun `download row appears only while its global switch is on and is never inherited`() {
		val off = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
		)
		assertFalse("a switched-off capability must be absent, not greyed", "download_url" in names(off))

		val on = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
			downloadEnabled = true,
		)
		assertTrue("the row appears after enabling the switch", "download_url" in names(on))
		// The assistant's null ceiling means "all tools", but a new opt-in
		// external path arriving switched on would let an untouched assistant
		// start making requests because a release added a name.
		assertFalse("a null ceiling must not enable a download", "download_url" in on.selected)

		val selected = conversationToolPlan(
			sshEnabled = false,
			agentTools = listOf("read_file", "download_url"),
			conversationTools = listOf("read_file", "download_url"),
			downloadEnabled = true,
		)
		assertTrue("an explicit selection stays selected", "download_url" in selected.selected)
	}

	@Test
	fun `an unconfigured media tool is absent from the sheet, not greyed`() {
		val hidden = conversationToolPlan(
			sshEnabled = false,
			agentTools = null,
			conversationTools = null,
			mediaConfigured = emptySet(),
		)
		for (name in ToolCatalog.mediaConfigGatedNames) {
			assertFalse(name, name in names(hidden))
		}
		// Non-media rows are untouched by the gate.
		assertTrue("read_file" in names(hidden))
	}

	@Test
	fun `a configured media tool is offered and selectable`() {
		val plan = conversationToolPlan(
			sshEnabled = false,
			agentTools = listOf("generate_image"),
			conversationTools = listOf("generate_image"),
			mediaConfigured = setOf("generate_image"),
		)
		assertTrue("generate_image" in names(plan))
		assertTrue("generate_image" in plan.selected)
	}

	@Test
	fun `a stored media selection survives while its backend is unconfigured`() {
		// The sheet cannot show the row, but applying it must not forget the
		// name: configuring the backend later restores the selection without
		// asking the user to remember it (the same rule SSH and download
		// follow when their switches are off).
		val plan = conversationToolPlan(
			sshEnabled = false,
			agentTools = listOf("generate_image"),
			conversationTools = listOf("generate_image"),
			mediaConfigured = emptySet(),
		)
		val merged = mergeConversationTools(
			current = listOf("generate_image"),
			available = plan.available,
			checked = emptySet(),
		)
		assertTrue("generate_image" in merged)
	}

	@Test
	fun `the sheet groups its rows by family without dropping or repeating any`() {
		val plan = conversationToolPlan(
			sshEnabled = true,
			agentTools = null,
			conversationTools = null,
			mediaConfigured = ToolCatalog.mediaConfigGatedNames,
		)
		val groups = toolGroups(plan.available)
		val flat = groups.flatMap { group -> group.entries.map { it.name } }
		assertEquals(
			"grouping is a partition of the offered rows",
			plan.available.map { it.name }.sorted(),
			flat.sorted(),
		)
		// Families render in their declared order, and each row is in one.
		assertEquals(groups.map { it.family }, groups.map { it.family }.sortedBy { it.ordinal })
		val byName = { name: String -> ToolCatalog.all.first { it.name == name } }
		assertEquals(ToolFamily.Read, toolFamilyOf(byName("read_file")))
		assertEquals(ToolFamily.Write, toolFamilyOf(byName("write_file")))
		assertEquals(ToolFamily.SshRead, toolFamilyOf(byName("ssh_list")))
		assertEquals(ToolFamily.SshRead, toolFamilyOf(byName("ssh_hosts")))
		assertEquals(ToolFamily.SshWrite, toolFamilyOf(byName("ssh_exec")))
		assertEquals(ToolFamily.Web, toolFamilyOf(byName("web_search")))
		assertEquals(ToolFamily.Download, toolFamilyOf(byName("download_url")))
		assertEquals(ToolFamily.Bilibili, toolFamilyOf(byName("bili_search")))
		assertEquals(ToolFamily.Media, toolFamilyOf(byName("generate_image")))
		assertEquals(ToolFamily.Other, toolFamilyOf(byName("unavailable_tools")))
	}
}
