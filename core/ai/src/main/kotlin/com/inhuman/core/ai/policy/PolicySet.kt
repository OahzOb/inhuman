package com.inhuman.core.ai.policy

/**
 * The rules in force, and the one question asked of them.
 *
 * Three sources exist -- a global default per tool, a per-assistant override,
 * and a per-path rule -- and they compose by **taking the strictest**. There is
 * deliberately no precedence order between them: "the assistant's rule wins" is
 * a way to accidentally relax something, while a maximum cannot be.
 *
 * ## What this type is not allowed to decide
 *
 * It answers questions about **the agent's** tool calls. It does not answer
 * questions about the user's own actions, and the distinction is load-bearing: a
 * file marked [ToolPolicy.Deny] is one the *agent* may not touch, not one the
 * user cannot open, rename or delete from the app. The user owns the directory.
 * Reading this as a general permission system is the mistake to avoid, which is
 * why [PolicySet] is constructed with the agent's tool names and consult it
 * through [effective] rather than being asked about paths in general.
 *
 * @param global per-tool defaults, absent meaning [ToolPolicy.Free]
 * @param assistant per-tool overrides for this conversation's assistant
 * @param paths per-path rules, keyed by **workspace-relative** path
 */
data class PolicySet(
	val global: Map<String, ToolPolicy> = emptyMap(),
	val assistant: Map<String, ToolPolicy> = emptyMap(),
	val paths: Map<String, ToolPolicy> = emptyMap(),
) {

	/**
	 * The policy for one call.
	 *
	 * @param tool the tool's name, as the model calls it.
	 * @param relativePath the workspace-relative path the call names, or null
	 *   for a tool that does not take one (`read_time`, `web_search`). Only
	 *   [ToolPolicy.Deny] and [ToolPolicy.Blind] are worth setting on those, and
	 *   a path rule simply does not apply to them.
	 *
	 * The path is expected **already normalised** -- produced by
	 * `SessionWorkspace.resolve` and then relativised, never assembled from
	 * model input. A string-prefix test is what `a/../b` walks straight through,
	 * so this function must not be handed a raw argument.
	 */
	fun effective(tool: String, relativePath: String? = null): ToolPolicy =
		ToolPolicy.strictest(
			global[tool],
			assistant[tool],
			relativePath?.let { pathPolicyFor(it) },
		)

	/**
	 * The strictest rule covering [relativePath], including its ancestors.
	 *
	 * A rule on a directory covers everything inside it, which is what makes
	 * `read-only` on a folder mean what a user expects. Ancestors are compared
	 * segment-wise on the normalised path, so `notes` covers `notes/todo.md` and
	 * `notes-old` is a different directory -- a prefix test on the string would
	 * confuse the two.
	 */
	private fun pathPolicyFor(relativePath: String): ToolPolicy? {
		val normalised = relativePath.trim('/')
		if (normalised.isEmpty()) return paths[""]

		var strictest: ToolPolicy? = null
		var prefix = ""
		for (segment in normalised.split('/')) {
			prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
			paths[prefix]?.let { rule ->
				strictest = strictest?.let { ToolPolicy.strictest(it, rule) } ?: rule
			}
		}
		return strictest
	}

	/** Whether anything at all is configured. Lets callers skip work entirely. */
	val isEmpty: Boolean get() = global.isEmpty() && assistant.isEmpty() && paths.isEmpty()

	companion object {
		val None = PolicySet()
	}
}

/**
 * The policy for a call that has no arguments worth restricting.
 *
 * A separate entry point rather than a default parameter on
 * [PolicySet.effective], so that a call site which *could* pass a path and does
 * not is visible when reading it. Forgetting a path means a path rule silently
 * stops applying, and that is exactly the kind of omission that is invisible in
 * a diff.
 */
fun PolicySet.effectiveFor(tool: String): ToolPolicy = effective(tool, relativePath = null)
