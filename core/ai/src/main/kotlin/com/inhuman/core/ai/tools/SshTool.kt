package com.inhuman.core.ai.tools

import com.inhuman.core.ai.Tool
import kotlinx.serialization.json.JsonObject

/**
 * A tool whose arguments travel to another machine.
 *
 * All seven SSH tools share the same argument quirk: a model may wrap the
 * connection object in a string field named `arguments`, or call the host
 * `hostname`. Unwrapping it here, once, is what lets the same call be shown in
 * the consent dialogue, judged by the policy layer and executed by the tool.
 *
 * The alternative -- normalising inside `execute` only -- is what the legacy
 * turn loop did, and it meant the gate and the tool could disagree about what
 * a call was. A consent dialogue that describes a different call from the one
 * that runs is worse than no dialogue.
 */
interface SshTool : Tool {
	override fun normalize(arguments: JsonObject): JsonObject = SshArguments.normalize(arguments)
}
