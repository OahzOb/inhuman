package com.inhuman.desktop

import com.inhuman.core.ai.ChatImage
import com.inhuman.core.ai.ChatMessage
import com.inhuman.core.ai.ChatProvider
import com.inhuman.core.ai.Role
import com.inhuman.core.ai.ToolCall
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.ai.ssh.SshFileTransport
import com.inhuman.core.ai.ssh.SshLimits
import com.inhuman.core.ai.ssh.SshTransport
import com.inhuman.core.ai.tools.SshDownloadTool
import com.inhuman.core.ai.tools.SshExecTool
import com.inhuman.core.ai.tools.SshListTool
import com.inhuman.core.ai.tools.SshProbeTool
import com.inhuman.core.ai.tools.SshReadFileTool
import com.inhuman.core.ai.tools.SshUploadTool
import com.inhuman.core.ai.tools.SshWriteFileTool
import com.inhuman.core.ai.tools.TimeTool
import com.inhuman.core.ai.tools.ToolCatalog
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.StoredToolCall
import com.inhuman.core.data.db.ToolCallsColumn
import com.inhuman.core.engine.ChatEngine
import com.inhuman.core.engine.EngineDecision
import com.inhuman.core.engine.EngineEvent
import com.inhuman.core.engine.EngineTurn
import com.inhuman.core.engine.EngineUsage
import com.inhuman.core.files.tools.FileTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * One turn of the desktop chat, with tools, against the persisted conversation.
 *
 * The window used to build the request from the lines it was drawing, which was
 * fine while a turn was one text answer and is wrong the moment a tool runs:
 * the assistant `tool_calls` row and its tool results are separate rows in the
 * transcript, and the next request has to carry both in the order the provider
 * expects. This class builds the wire history from the database, lets
 * `ChatEngine` drive the loop, and writes every round and every tool result back
 * before the next round starts.
 *
 * It is deliberately UI-free: the window receives [Event]s and owns rendering,
 * while the test can drive the same class with `DemoProvider` and a temporary
 * data directory.
 */
class DesktopChat(
	private val store: DesktopStore,
	private val conversationId: String,
	private val assistant: AgentEntity?,
	private val ssh: SshTransport? = null,
	private val sshFiles: SshFileTransport? = null,
	private val sshConsent: (suspend (SshConsentRequest) -> Boolean)? = null,
) {

	sealed interface Event {
		data class UserMessage(val line: StoredLine) : Event
		data class AssistantStarted(val id: String) : Event
		data class AssistantDelta(val text: String, val reasoning: Boolean) : Event
		data class AssistantFinished(val line: StoredLine) : Event
		data class ToolResult(val line: StoredLine) : Event
		data class Error(val message: String) : Event
	}

	/**
	 * One call the configured policy wants the user to see.
	 *
	 * [argumentsJson] is the normalised call as the tool will run it. It can
	 * contain paths and file content, which is the point: the user is being
	 * asked about *this* call. SSH credentials are not shown here because the
	 * desktop does not offer SSH yet; when it does, this must carry the
	 * redacted details rather than the raw arguments.
	 */
	data class ApprovalRequest(
		val toolName: String,
		val argumentsJson: String,
		val description: String,
	)

	/**
	 * The first remote call in a conversation.
	 *
	 * It carries only host, user and a short operation summary. A password or
	 * private key must never reach this dialogue; the model supplied them, and
	 * the user does not need to read them back.
	 */
	data class SshConsentRequest(
		val toolName: String,
		val host: String,
		val username: String,
		val operation: String,
	)

	/**
	 * Run one turn and return the error, or null on a clean finish.
	 *
	 * [approve] is consulted only when the policy says `Ask` or the tool's own
	 * `needsApproval` says yes. Returning false is a user refusal, which ends
	 * the turn with a `[declined]` result rather than a failed one.
	 */
	suspend fun send(
		userText: String,
		provider: ChatProvider,
		model: String,
		attachments: List<DesktopAttachment> = emptyList(),
		approve: (suspend (ApprovalRequest) -> Boolean)? = null,
		onEvent: (Event) -> Unit,
	): String? {
		val conversation = store.conversation(conversationId) ?: return "Conversation not found."
		val entity = conversation.entity
		val agent = entity.agentId?.let { store.assistant(it) } ?: assistant
		val parentId = store.lastMessageId(conversationId)
		val firstMessage = parentId == null

		val userLine = store.appendUser(
			conversationId = conversationId,
			text = userText,
			parentId = parentId,
			attachments = attachments,
		)
		onEvent(Event.UserMessage(userLine))
		if (firstMessage) {
			store.autoTitle(conversationId, userText.ifBlank { "Image" })
		}
		// The checkpoint is keyed by the user message id and taken before any
		// tool runs. A failure only makes this turn unrewindable.
		store.captureTurnStart(conversationId, userLine.id)

		val workspace = runCatching { store.workspace(entity) }.getOrElse { failure ->
			val message = "Workspace could not be opened: ${failure.message}"
			onEvent(Event.Error(message))
			return message
		}
		val selected = entity.tools ?: agent?.tools
		val sshEnabled = store.sshEnabled()
		val limits = store.sshLimits()
		val tools = buildList {
			addAll(FileTools.all(workspace))
			add(TimeTool())
			if (sshEnabled) {
				val readIdentity: (String) -> ByteArray? = { path ->
					when (val resolved = workspace.resolve(path)) {
						is com.inhuman.core.files.SessionWorkspace.Resolution.Ok ->
							resolved.file
								.takeIf { it.isFile && it.length() <= MAX_SSH_IDENTITY_BYTES }
								?.readBytes()
						else -> null
					}
				}
				val readUpload: (String) -> ByteArray? = { path ->
					when (val resolved = workspace.resolve(path)) {
						is com.inhuman.core.files.SessionWorkspace.Resolution.Ok ->
							resolved.file
								.takeIf { it.isFile && it.length() <= MAX_SSH_UPLOAD_READ_BYTES }
								?.readBytes()
						else -> null
					}
				}
				if (ssh != null && selected?.contains("ssh_exec") == true) {
					add(SshExecTool(ssh, limits, readIdentity))
				}
				if (ssh != null && selected?.contains("ssh_probe") == true) {
					add(SshProbeTool(ssh, limits, readIdentity))
				}
				if (sshFiles != null && selected?.contains("ssh_list") == true) {
					add(
						SshListTool(
							transport = sshFiles,
							readWorkspaceFile = readIdentity,
							maxEntries = limits.listCeilingEntries,
						),
					)
				}
				if (sshFiles != null && selected?.contains("ssh_read_file") == true) {
					add(
						SshReadFileTool(
							transport = sshFiles,
							readWorkspaceFile = readIdentity,
							maxBytes = limits.readCeilingBytes,
						),
					)
				}
				if (sshFiles != null && selected?.contains("ssh_upload") == true) {
					add(
						SshUploadTool(
							transport = sshFiles,
							readWorkspaceFile = readUpload,
							maxBytes = workspace.limits.maxReadBytes,
						),
					)
				}
				if (sshFiles != null && selected?.contains("ssh_write_file") == true) {
					add(
						SshWriteFileTool(
							transport = sshFiles,
							readWorkspaceFile = readIdentity,
						),
					)
				}
				if (sshFiles != null && selected?.contains("ssh_download") == true) {
					add(
						SshDownloadTool(
							transport = sshFiles,
							readWorkspaceFile = readIdentity,
							saveWorkspaceFile = { path, bytes, overwrite ->
								saveDownload(workspace, path, bytes, overwrite)
							},
							maxBytes = workspace.limits.maxWriteBytes,
						),
					)
				}
			}
		}.filter { ToolCatalog.permits(selected, it.name) }
		val policies = store.policies(conversationId, entity.agentId)

		var lastId: String? = userLine.id
		var current: Round? = null
		var error: String? = null
		var cancelled = false

		val engine = ChatEngine(
			provider = provider,
			approval = { tool, call ->
				val parsed = runCatching {
					Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject
				}.getOrNull()
				val policy = policies.effective(tool.name)
				val remote = ToolCatalog.all.firstOrNull { it.name == tool.name }?.remote == true

				var remoteRefusal: EngineDecision? = null
				if (
					parsed != null &&
					remote &&
					policy != ToolPolicy.Deny &&
					policy != ToolPolicy.Blind
				) {
					if (!store.sshEnabled()) {
						remoteRefusal = EngineDecision.Deny(
							"$REFUSED SSH is disabled in Settings.",
							stopTurn = false,
						)
					} else if (!store.sshAllowed(conversationId)) {
						val request = SshConsentRequest(
							toolName = tool.name,
							host = parsed.string("host").orEmpty(),
							username = parsed.string("username").orEmpty(),
							operation = sshOperation(tool.name, parsed),
						)
						val allowed = sshConsent?.invoke(request) ?: false
						if (!allowed) {
							remoteRefusal = EngineDecision.Deny(
								"$DECLINED The user declined SSH access for this conversation.",
								stopTurn = true,
								declined = true,
							)
						} else {
							store.setSshAllowed(conversationId, true)
							if (!store.sshEnabled()) {
								remoteRefusal = EngineDecision.Deny(
									"$REFUSED SSH was disabled in Settings before the call ran.",
									stopTurn = false,
								)
							}
						}
					}
				}

				when {
					remoteRefusal != null -> remoteRefusal

					parsed == null -> EngineDecision.Deny(
						"$REFUSED invalid tool arguments",
						stopTurn = false,
					)

					policy == ToolPolicy.Deny || policy == ToolPolicy.Blind ->
						EngineDecision.Deny(
							"$REFUSED This tool or path is not available in this conversation.",
							stopTurn = false,
						)

					// The policy can ask; the window supplies the dialogue. With
					// no dialogue wired (a test or a future headless host), the
					// answer is no -- never an implicit yes.
					policy == ToolPolicy.Ask || tool.needsApproval(parsed) -> {
						val allowed = approve?.invoke(
							ApprovalRequest(
								toolName = tool.name,
								argumentsJson = call.argumentsJson,
								description = tool.description,
							),
						) ?: false
						if (allowed) {
							EngineDecision.Allow
						} else {
							EngineDecision.Deny(
								"$DECLINED The user declined this call.",
								stopTurn = true,
								declined = true,
							)
						}
					}

					else -> EngineDecision.Allow
				}
			},
		)

		try {
			engine.run(
				EngineTurn(
					model = model,
					messages = history(agent, workspace),
					tools = tools,
					maxRounds = DEFAULT_TOOL_ROUNDS,
					maxCalls = DEFAULT_TOOL_CALLS,
					thinking = agent?.thinking ?: false,
					reasoningEffort = agent?.reasoningEffort,
					userId = conversationId,
				),
			).collect { event ->
				when (event) {
					is EngineEvent.RoundStarted -> {
						val round = Round(
							id = UUID.randomUUID().toString(),
							parentId = lastId,
						)
						current = round
						onEvent(Event.AssistantStarted(round.id))
					}

					is EngineEvent.ReasoningDelta -> {
						current?.reasoning?.append(event.text)
						onEvent(Event.AssistantDelta(event.text, reasoning = true))
					}

					is EngineEvent.TextDelta -> {
						current?.text?.append(event.text)
						onEvent(Event.AssistantDelta(event.text, reasoning = false))
					}

					is EngineEvent.AssistantMessage -> {
						val round = current ?: return@collect
						round.reasoningSignature = event.message.reasoningSignature
						event.message.toolCalls.forEachIndexed { index, call ->
							round.calls[index] = StoredToolCall(
								index = index,
								id = call.id,
								name = call.name,
								argumentsJson = call.argumentsJson,
								thoughtSignature = call.thoughtSignature,
							)
						}
					}

					is EngineEvent.Usage -> current?.usage = event.usage

					is EngineEvent.ToolCallResult -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = event.result.content,
							isError = event.result.isError,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.ToolCallSkipped -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = "$SKIPPED ${event.reason}",
							isError = true,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.ToolCallDeclined -> {
						val line = store.appendTool(
							conversationId = conversationId,
							toolCallId = event.call.id,
							toolName = event.call.name,
							content = event.message,
							isError = true,
							parentId = lastId,
						)
						lastId = line.id
						onEvent(Event.ToolResult(line))
					}

					is EngineEvent.RoundFinished -> {
						val round = current ?: return@collect
						val line = round.persist(finishReason = event.reason.name)
						if (line != null) {
							lastId = line.id
							onEvent(Event.AssistantFinished(line))
						}
						current = null
					}

					is EngineEvent.Failed -> {
						error = event.message
						onEvent(Event.Error(event.message))
					}

					is EngineEvent.Finished -> Unit
					is EngineEvent.ToolCallReady -> Unit
				}
			}
		} catch (cancelledTurn: CancellationException) {
			cancelled = true
			throw cancelledTurn
		} catch (failure: Exception) {
			error = failure.message ?: failure::class.simpleName
			onEvent(Event.Error(error!!))
		} finally {
			val round = current
			if (round != null && !round.persisted) {
				withContext(NonCancellable) {
					val note = if (cancelled) "Stopped before the reply finished." else error
					val line = round.persist(error = note)
					if (line != null) onEvent(Event.AssistantFinished(line))
				}
			}
		}
		return error
	}

	/**
	 * The transcript as the provider needs it.
	 *
	 * A dangling assistant `tool_calls` row is fatal to every later request in
	 * the conversation: the API rejects it with HTTP 400 and the conversation
	 * cannot continue. A process death while a call was running is enough to
	 * produce one, so missing results are closed here rather than left for the
	 * next turn to discover.
	 */
	private suspend fun history(
		agent: AgentEntity?,
		workspace: com.inhuman.core.files.SessionWorkspace,
	): List<ChatMessage> {
		val out = mutableListOf<ChatMessage>()
		val messages = store.messages(conversationId)
		val summary = store.contextSummary(conversationId)
		val boundary = summary?.let { active ->
			messages.indexOfFirst { it.id == active.throughMessageId }
		} ?: -1
		// A stale summary from another branch, or one that already covers the
		// whole transcript, is ignored rather than applied to the wrong prefix.
		val activeSummary = summary.takeIf { boundary >= 0 && boundary < messages.lastIndex }

		val system = buildString {
			agent?.systemPrompt?.takeIf { it.isNotBlank() }?.let { append(it) }
			if (activeSummary != null) {
				if (isNotEmpty()) append("\n\n")
				append("Earlier conversation summary (the messages it covers are omitted from this request):\n")
				append(activeSummary.summary.trim())
			}
		}
		if (system.isNotBlank()) out += ChatMessage(Role.System, system)

		var index = if (activeSummary != null) boundary + 1 else 0
		while (index < messages.size) {
			val message = messages[index]
			when (message.role) {
				"user" -> {
					val images = DesktopAttachmentCodec.decode(message.attachmentsJson)
						.mapNotNull { attachment ->
							val file = when (
								val resolved = workspace.resolve(attachment.relativePath)
							) {
								is com.inhuman.core.files.SessionWorkspace.Resolution.Ok ->
									resolved.file
								is com.inhuman.core.files.SessionWorkspace.Resolution.Rejected ->
									return@mapNotNull null
							}
							if (!file.isFile || file.length() > workspace.limits.maxReadBytes) {
								return@mapNotNull null
							}
							runCatching {
								ChatImage(attachment.mimeType, file.readBytes())
							}.getOrNull()
						}
					out += ChatMessage(
						Role.User,
						message.content.ifBlank { if (images.isNotEmpty()) " " else "" },
						images = images,
					)
				}
				"assistant" -> {
					val calls = ToolCallsColumn.decode(message.toolCallsJson)
						.mapNotNull { call ->
							val id = call.id ?: return@mapNotNull null
							ToolCall(id, call.name.orEmpty(), call.argumentsJson, call.thoughtSignature)
						}
					out += ChatMessage(
						role = Role.Assistant,
						content = message.content,
						reasoning = message.reasoning?.takeIf { it.isNotBlank() },
						reasoningSignature = message.reasoningSignature,
						toolCalls = calls,
					)
					if (calls.isNotEmpty()) {
						val answered = mutableSetOf<String>()
						var next = index + 1
						while (next < messages.size && messages[next].role == "tool") {
							messages[next].toolCallId?.let(answered::add)
							next++
						}
						for (missing in calls.map { it.id } - answered) {
							out += ChatMessage(
								role = Role.Tool,
								content = "[interrupted] The app stopped before this call finished; it was not run.",
								toolCallId = missing,
							)
						}
						index = next
						continue
					}
				}

				"tool" -> out += ChatMessage(
					role = Role.Tool,
					content = message.content,
					toolCallId = message.toolCallId,
				)
			}
			index++
		}
		return out
	}

	private fun JsonObject.string(key: String): String? =
		(this[key] as? JsonPrimitive)?.contentOrNull

	private fun sshOperation(toolName: String, arguments: JsonObject): String = when (toolName) {
		"ssh_exec" -> "run: " + arguments.string("command").orEmpty().take(160)
		"ssh_probe" -> "inspect the host"
		"ssh_list" -> "list " + arguments.string("path").orEmpty().ifBlank { "." }
		"ssh_read_file" -> "read " + arguments.string("path").orEmpty().ifBlank { "?" }
		"ssh_write_file" -> "write " + arguments.string("remotePath").orEmpty().ifBlank { "?" }
		"ssh_upload" -> "upload to " + arguments.string("remotePath").orEmpty().ifBlank { "?" }
		"ssh_download" -> "download " + arguments.string("remotePath").orEmpty().ifBlank { "?" }
		else -> "use the remote host"
	}

	/**
	 * Put a downloaded file into the workspace under the same quota rules as
	 * import. The model cannot replace an existing file unless it explicitly
	 * asked to overwrite.
	 */
	private fun saveDownload(
		workspace: com.inhuman.core.files.SessionWorkspace,
		path: String,
		bytes: ByteArray,
		overwrite: Boolean,
	): String {
		val file = when (val resolved = workspace.resolve(path)) {
			is com.inhuman.core.files.SessionWorkspace.Resolution.Ok -> resolved.file
			is com.inhuman.core.files.SessionWorkspace.Resolution.Rejected -> return resolved.reason
		}
		val existed = file.isFile
		if (existed && !overwrite) {
			return "refused: $path already exists; pass overwrite=true to replace it"
		}
		val refusal = workspace.quotaRefusal(
			stagingBytes = 0,
			stagingFiles = 0,
			replacedBytes = if (existed) file.length() else 0,
			addsFiles = if (existed) 0 else 1,
			newBytes = bytes.size.toLong(),
		)
		if (refusal != null) return "refused: $refusal"
		runCatching {
			file.parentFile?.mkdirs()
			file.writeBytes(bytes)
		}.onFailure { return "could not write $path: ${it.message}" }
		return "downloaded $path (${bytes.size} bytes)"
	}

	private inner class Round(
		val id: String,
		val parentId: String?,
	) {
		val text = StringBuilder()
		val reasoning = StringBuilder()
		var reasoningSignature: String? = null
		val calls = LinkedHashMap<Int, StoredToolCall>()
		var usage: EngineUsage? = null
		var persisted = false

		suspend fun persist(error: String? = null, finishReason: String? = null): StoredLine? {
			if (persisted) return null
			val hasPayload = text.isNotEmpty() || reasoning.isNotEmpty() || calls.isNotEmpty()
			if (!hasPayload && error == null) return null
			persisted = true
			return store.appendAssistant(
				conversationId = conversationId,
				text = text.toString(),
				reasoning = reasoning.toString(),
				reasoningSignature = reasoningSignature,
				parentId = parentId,
				toolCalls = calls.values.toList(),
				error = error,
				finishReason = finishReason,
				promptTokens = usage?.promptTokens,
				completionTokens = usage?.completionTokens,
				cachedPromptTokens = usage?.cachedPromptTokens,
				reasoningTokens = usage?.reasoningTokens,
			)
		}
	}

	private companion object {
		const val DEFAULT_TOOL_ROUNDS = 8
		const val DEFAULT_TOOL_CALLS = 32
		const val REFUSED = "[refused]"
		const val SKIPPED = "[skipped]"
		const val DECLINED = "[declined]"
		const val MAX_SSH_IDENTITY_BYTES = 256L * 1024L
		const val MAX_SSH_UPLOAD_READ_BYTES = 8L * 1024L * 1024L
	}
}
