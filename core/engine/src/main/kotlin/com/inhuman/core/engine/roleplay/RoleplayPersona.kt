package com.inhuman.core.engine.roleplay

/**
 * The static persona a role-play request should use: prompt, character name and
 * card.
 *
 * A pure function rather than three fallbacks scattered through the view model,
 * because the rule is a promise: once a scene is pinned, the live assistant row
 * is **not** consulted -- not for the prompt, not for the name, and not for the
 * card. A fallback would make a cleared card resurrect, and a partly-resolved
 * persona (snapshot card, live prompt) would be a state the conversation never
 * had. Keeping it here also lets the rule be tested on the JVM; the device
 * cannot observe which row a system message was built from.
 */
data class RoleplayPersona(
	val systemPrompt: String,
	val characterName: String,
	val card: RoleplayCard?,
)

object RoleplayPersonaResolver {

	/**
	 * @param snapshot the conversation's frozen persona, or null when it has
	 *   never been pinned (a preview, or a scene from before schema v27).
	 * @param liveSystemPrompt / @param liveName / @param liveCard the assistant
	 *   row, used only when there is no snapshot.
	 */
	fun resolve(
		snapshot: RoleplaySnapshot?,
		liveSystemPrompt: String?,
		liveName: String?,
		liveCard: String?,
	): RoleplayPersona {
		if (snapshot != null) {
			return RoleplayPersona(
				systemPrompt = snapshot.systemPrompt,
				characterName = snapshot.characterName,
				card = RoleplayCodec.decodeCard(snapshot.card),
			)
		}
		return RoleplayPersona(
			systemPrompt = liveSystemPrompt.orEmpty(),
			characterName = liveName.orEmpty(),
			card = RoleplayCodec.decodeCard(liveCard),
		)
	}
}
