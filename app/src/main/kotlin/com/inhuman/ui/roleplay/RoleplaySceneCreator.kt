package com.inhuman.ui.roleplay

import com.inhuman.core.data.ConversationRepository
import com.inhuman.core.data.db.AgentEntity
import com.inhuman.core.data.db.ConversationEntity
import com.inhuman.core.engine.roleplay.RoleplayCard
import com.inhuman.core.engine.roleplay.RoleplayCodec
import com.inhuman.core.engine.roleplay.RoleplayPrompt
import com.inhuman.core.engine.roleplay.RoleplayScene
import com.inhuman.core.engine.roleplay.RoleplaySnapshot

/**
 * The one place a role-play scene conversation is written.
 *
 * Two callers create scenes: the New-scene dialog (a user form) and the RP
 * workshop's proposal card (the user taps "Create and open" on data an agent
 * prepared). They differ only in where the title, persona and workspace come
 * from; the parts that make a scene a scene must not exist twice:
 *
 * - `tools = emptyList()` writes the explicit `"[]"` selection. `null` would
 *   copy the assistant's tools and `""` would mean every tool, so a story would
 *   start with capabilities nobody chose (`docs/07-gotchas.md` A26).
 * - The persona is frozen into `rpSnapshotJson` and the greeting is written in
 *   the same transaction as the conversation, so a scene never opens with the
 *   user talking to nobody.
 * - The provider/model override lets a scene created from a conversation keep
 *   that conversation's model rather than the assistant row's, which is what the
 *   user is actually paying for.
 */
internal class RoleplaySceneCreator(
	private val repository: ConversationRepository,
) {

	suspend fun create(
		title: String,
		agent: AgentEntity?,
		workspaceId: String?,
		cardJson: String,
		card: RoleplayCard,
		characterName: String,
		systemPrompt: String,
		scene: RoleplayScene,
		sessionMode: String,
		providerId: String? = null,
		model: String? = null,
		reasoningEffort: String? = null,
		thinking: Boolean? = null,
	): ConversationEntity {
		val snapshot = RoleplaySnapshot(
			systemPrompt = systemPrompt,
			characterName = characterName,
			card = cardJson,
		)
		return repository.create(
			title = title,
			agent = agent,
			workspaceId = workspaceId,
			sessionMode = sessionMode,
			tools = emptyList(),
			sceneJson = RoleplayCodec.encodeScene(scene).ifEmpty { "{}" },
			rpSnapshotJson = RoleplayCodec.encodeSnapshot(snapshot),
			greeting = RoleplayPrompt.greeting(card, characterName).takeIf { it.isNotBlank() },
			providerId = providerId,
			model = model,
			reasoningEffort = reasoningEffort,
			thinking = thinking,
		)
	}
}
