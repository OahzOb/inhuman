package com.inhuman.ui.chat

import com.inhuman.core.data.Conversation
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.data.db.MessageEntity
import com.inhuman.core.data.db.StoredToolCall
import com.inhuman.core.data.db.ToolCallsColumn
import com.inhuman.ui.common.exportFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ConversationMarkdownTest {

	private val labels = MarkdownLabels(
		user = "You",
		assistant = "Assistant",
		system = "System",
		reasoning = "Reasoning",
		toolCalls = "Tool calls",
		toolResult = "Tool result",
		error = "Error",
		attachments = "Attachments",
		exported = "Exported",
		model = "Model",
		toolsIncluded = "Tool calls and results included.",
		toolsOmitted = "Tool calls and results omitted.",
		attachmentsNotIncluded = "Attachment files are not included in this Markdown file.",
	)

	private val exportedAt = Instant.parse("2026-10-26T11:20:00Z") // 19:20 Shanghai.
	private val zone = ZoneId.of("Asia/Shanghai")

	/** The same document in the language the app now starts in. */
	private val chineseLabels = MarkdownLabels(
		user = "你",
		assistant = "助手",
		system = "系统",
		reasoning = "推理过程",
		toolCalls = "工具调用",
		toolResult = "工具结果",
		error = "错误",
		attachments = "附件",
		exported = "导出时间",
		model = "模型",
		toolsIncluded = "包含工具调用和结果。",
		toolsOmitted = "未包含工具调用和结果。",
		attachmentsNotIncluded = "附件文件不包含在这个 Markdown 文件里。",
	)

	@Test
	fun `the header names the conversation, time, model and tool policy`() {
		val markdown = render(
			listOf(
				message("u1", "user", "hello"),
				message("a1", "assistant", "hi"),
			),
			includeTools = true,
		)

		assertTrue(markdown.startsWith("# Test conversation\n"))
		assertTrue(markdown.contains("- Exported: 2026-10-26 19:20"))
		assertTrue(markdown.contains("- Model: deepseek-chat"))
		assertTrue(markdown.contains("- Tool calls and results included."))
		assertTrue(markdown.contains("## You\n\nhello"))
		assertTrue(markdown.contains("## Assistant\n\nhi"))
	}

	@Test
	fun `tool calls and results are absent when the user omitted them`() {
		val markdown = render(
			listOf(
				message("u1", "user", "read it"),
				assistantWithCall("a1", "looking"),
				toolResult("t1", "read_file", "file body"),
			),
			includeTools = false,
		)

		assertTrue(markdown.contains("- Tool calls and results omitted."))
		assertFalse(markdown.contains("#### `read_file`"))
		assertFalse(markdown.contains("Tool result"))
		assertFalse(markdown.contains("read_file"))
		assertFalse(markdown.contains("file body"))
	}

	@Test
	fun `tool calls keep their arguments and results in fences`() {
		val markdown = render(
			listOf(
				message("u1", "user", "read it"),
				assistantWithCall("a1", "looking"),
				toolResult("t1", "read_file", "file body"),
			),
			includeTools = true,
		)

		assertTrue(markdown.contains("#### `read_file`"))
		assertTrue(markdown.contains("```json\n{\"path\":\"a.txt\"}\n```"))
		assertTrue(markdown.contains("### Tool result: `read_file`"))
		assertTrue(markdown.contains("```text\nfile body\n```"))
	}

	@Test
	fun `reasoning is a blockquote`() {
		val markdown = render(
			listOf(
				message("a1", "assistant", "answer", reasoning = "line one\n\nline two"),
			),
			includeTools = true,
		)

		assertTrue(markdown.contains("> **Reasoning**"))
		assertTrue(markdown.contains("> line one\n>\n> line two"))
	}

	@Test
	fun `an attachment is named but its bytes are not claimed to be present`() {
		val markdown = render(
			listOf(
				message(
					"u1",
					"user",
					"what is this?",
					attachmentsJson = """[{"path":"attachments/cat.webp","mime":"image/webp"}]""",
				),
			),
			includeTools = true,
		)

		assertTrue(markdown.contains("Attachment files are not included"))
		assertTrue(markdown.contains("**Attachments**"))
		assertTrue(markdown.contains("`attachments/cat.webp` (image/webp)"))
	}

	@Test
	fun `an error follows the answer`() {
		val markdown = render(
			listOf(message("a1", "assistant", "partial", error = "connection reset")),
			includeTools = true,
		)

		assertTrue(markdown.contains("> **Error:** connection reset"))
	}

	@Test
	fun `a tool result containing a triple fence gets a longer fence`() {
		val markdown = render(
			listOf(toolResult("t1", "read_file", "```\ninner\n```")),
			includeTools = true,
		)

		assertTrue(markdown.contains("````text\n```\ninner\n```\n````"))
		assertEquals("````\n```\n````", fenced("```", ""))
	}

	@Test
	fun `file names lose characters a filesystem refuses and keep unicode`() {
		val name = exportFileName("a/b:c* 笔记", "md")

		assertTrue(name.endsWith(".md"))
		assertFalse(name.contains('/'))
		assertFalse(name.contains(':'))
		assertFalse(name.contains('*'))
		assertTrue(name.contains("笔记"))
		assertEquals("conversation.md", exportFileName("///", "md"))
		assertTrue(exportFileName("x".repeat(200), "zip").length <= 64)
	}

	@Test
	fun `a chinese document keeps its headings and labels`() {
		val markdown = render(
			messages = listOf(
				message("u1", "user", "你好"),
				message("a1", "assistant", "答复", reasoning = "先想一想"),
				toolResult("t1", "read_file", "文件内容"),
			),
			includeTools = true,
			labels = chineseLabels,
			title = "会议记录",
		)

		assertTrue(markdown.startsWith("# 会议记录\n"))
		assertTrue(markdown.contains("- 导出时间: 2026-10-26 19:20"))
		assertTrue(markdown.contains("## 你\n\n你好"))
		assertTrue(markdown.contains("## 助手\n\n> **推理过程**"))
		assertTrue(markdown.contains("\n\n答复\n"))
		assertTrue(markdown.contains("> **推理过程**\n>\n> 先想一想"))
		assertTrue(markdown.contains("### 工具结果: `read_file`"))
		assertTrue(markdown.contains("文件内容"))
	}

	private fun render(
		messages: List<MessageEntity>,
		includeTools: Boolean,
		labels: MarkdownLabels = this.labels,
		title: String = "Test conversation",
	): String =
		conversationMarkdown(
			conversation = Conversation(
				entity = ConversationEntity(
					id = "c1",
					title = title,
					workspaceId = "w1",
					model = "deepseek-chat",
					createdAt = 0,
					updatedAt = 0,
				),
				messages = messages,
			),
			labels = labels,
			fallbackTitle = "Untitled",
			includeToolResults = includeTools,
			exportedAt = exportedAt,
			zone = zone,
		)

	private fun message(
		id: String,
		role: String,
		content: String,
		reasoning: String? = null,
		attachmentsJson: String? = null,
		error: String? = null,
	): MessageEntity = MessageEntity(
		id = id,
		conversationId = "c1",
		parentId = null,
		role = role,
		content = content,
		createdAt = 1L,
		reasoning = reasoning,
		attachmentsJson = attachmentsJson,
		error = error,
	)

	private fun assistantWithCall(id: String, content: String): MessageEntity = message(
		id = id,
		role = "assistant",
		content = content,
	).copy(
		toolCallsJson = ToolCallsColumn.encode(
			listOf(StoredToolCall(0, "call-1", "read_file", """{"path":"a.txt"}""")),
		),
	)

	private fun toolResult(id: String, name: String, content: String): MessageEntity = message(
		id = id,
		role = "tool",
		content = content,
	).copy(toolName = name, toolCallId = "call-1")
}