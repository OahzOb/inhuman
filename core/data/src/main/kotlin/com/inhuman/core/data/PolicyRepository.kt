package com.inhuman.core.data

import com.inhuman.core.ai.policy.PolicySet
import com.inhuman.core.ai.policy.ToolPolicy
import com.inhuman.core.data.db.InhumanDatabase
import com.inhuman.core.data.db.AgentApprovalEntity
import com.inhuman.core.data.db.ApprovalDefaultEntity
import com.inhuman.core.data.db.PathPolicyEntity

/**
 * Reads the three approval-rule sources into one [PolicySet].
 *
 * The composition itself lives in `PolicySet` as a pure function with its own
 * tests; this class only loads. The split is deliberate: "take the strictest" is
 * the rule that must not be wrong, and a rule that can only be exercised against
 * a database is a rule that gets tested less.
 *
 * Nothing here writes. Writing is three `@Insert`/`@Query` calls on `PolicyDao`
 * away, and is not added until a screen needs it -- an API with no caller is a
 * shape guessed in advance.
 */
class PolicyRepository(private val db: InhumanDatabase) {

	/**
	 * Every rule in force for one conversation's assistant.
	 *
	 * Three queries rather than a join, because the three tables have different
	 * keys and the join would be a `UNION` with nullable columns -- work in SQL
	 * to avoid two round trips on a database that is local and small.
	 */
	suspend fun forConversation(conversationId: String, agentId: String?): PolicySet {
		val defaults = db.policies().defaults()
		val agent = agentId?.let { db.policies().forAgent(it) }.orEmpty()
		val paths = db.policies().forConversation(conversationId)

		return PolicySet(
			global = defaults.associate { it.toolName to ToolPolicy.parse(it.policy) },
			assistant = agent.associate { it.toolName to ToolPolicy.parse(it.policy) },
			paths = paths.associate { it.relPath to ToolPolicy.parse(it.policy) },
		)
	}

	/** The global rules alone, which is what a settings screen edits. */
	suspend fun globalRules(): Map<String, ToolPolicy> =
		db.policies().defaults().associate { it.toolName to ToolPolicy.parse(it.policy) }

	suspend fun setGlobalRule(toolName: String, policy: ToolPolicy) {
		if (policy == ToolPolicy.Free) {
			db.policies().clearDefault(toolName)
		} else {
			db.policies().setDefault(ApprovalDefaultEntity(toolName, policy.name))
		}
	}

	suspend fun setAssistantRule(agentId: String, toolName: String, policy: ToolPolicy) {
		if (policy == ToolPolicy.Free) {
			db.policies().clearAgentRule(agentId, toolName)
		} else {
			db.policies().setAgentRule(AgentApprovalEntity(agentId, toolName, policy.name))
		}
	}

	/**
	 * Set a rule on one path.
	 *
	 * @param relPath already normalised and workspace-relative. The caller is
	 *   expected to have got it from `SessionWorkspace.relativize` on a resolved
	 *   path -- never from a model argument, which can contain `..` and would
	 *   store a rule that matches nothing while looking like it matches
	 *   something.
	 */
	suspend fun setPathRule(conversationId: String, relPath: String, policy: ToolPolicy) {
		val normalised = relPath.trim('/')
		if (policy == ToolPolicy.Free) {
			db.policies().clearPathRule(conversationId, normalised)
		} else {
			db.policies().setPathRule(PathPolicyEntity(conversationId, normalised, policy.name))
		}
	}
}
