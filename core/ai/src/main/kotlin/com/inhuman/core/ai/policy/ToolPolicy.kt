package com.inhuman.core.ai.policy

/**
 * What the agent may do with a tool call, as configured.
 *
 * ## Why the default is [Free] and why that is not a downgrade
 *
 * Safety in this app comes from **structure, not from prompts**. The agent can
 * only see and change a conversation's own directory, and what the user brings
 * in is a *copy* -- the original never moves and is never in the directory. So
 * everything inside is already reversible, and asking "are you sure?" where the
 * answer is always yes is not a safety measure: it is friction, and friction is
 * what trains people to click through the prompt that eventually matters.
 *
 * The policies below are therefore a **configurable tightening**, not a set of
 * gates the app imposes. That ordering matters for reading the rest of this
 * package: nothing here can grant access, because access is not this layer's to
 * grant.
 */
enum class ToolPolicy {
	/** Run it. The default, and what every tool does with no configuration. */
	Free,

	/** Ask the user first, per call. */
	Ask,

	/** Refuse. The agent is told, and told why, rather than being ignored. */
	Deny,

	/**
	 * Refuse, and do not show it to the agent at all.
	 *
	 * Stronger than [Deny] on purpose. A denial answers "may I?" and a curious
	 * model then asks about the thing; blindness means the file is not in
	 * `list_files` and cannot be named, because **a filename can leak on its
	 * own**. A private note called `divorce-lawyer.md` has told the model
	 * something already.
	 */
	Blind,
	;

	/**
	 * How much this policy restricts, for composing two of them.
	 *
	 * Ordered rather than compared by name so that "take the strictest" is a
	 * single `maxOf` at every call site. If a future policy is added, its
	 * position here is the only decision that has to be made, and it cannot be
	 * forgotten at a call site the way a hand-written `when` could be.
	 */
	internal val strictness: Int
		get() = when (this) {
			Free -> 0
			Ask -> 1
			Deny -> 2
			Blind -> 3
		}

	companion object {
		/**
		 * The stricter of two policies.
		 *
		 * This is the **whole** composition rule. Global, per-assistant and
		 * per-path rules do not have a precedence order between them -- there is
		 * no "the assistant's rule wins" -- because a precedence order is a way
		 * to accidentally relax something. Taking the maximum makes the
		 * property checkable by reading one line: no combination of rules can
		 * produce a result looser than the loosest input's strictest member.
		 */
		fun strictest(a: ToolPolicy, b: ToolPolicy): ToolPolicy =
			if (a.strictness >= b.strictness) a else b

		/** The strictest of all of them; [Free] when there are none. */
		fun strictest(vararg policies: ToolPolicy?): ToolPolicy =
			policies.filterNotNull().maxByOrNull { it.strictness } ?: Free

		/**
		 * Parse a stored value, falling back to [Free].
		 *
		 * Tolerant on purpose: a value this build does not recognise is a value
		 * written by a newer one, and refusing to open a conversation because of
		 * a settings row would be a worse failure than ignoring the row.
		 */
		fun parse(stored: String?): ToolPolicy =
			entries.firstOrNull { it.name.equals(stored?.trim(), ignoreCase = true) } ?: Free
	}
}
