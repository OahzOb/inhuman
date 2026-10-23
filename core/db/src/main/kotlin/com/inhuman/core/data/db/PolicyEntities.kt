package com.inhuman.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * The three places an approval rule can live.
 *
 * One entity per source rather than one table with a nullable scope column,
 * because the three have genuinely different keys -- a tool name, a tool name
 * scoped to an assistant, and a path scoped to a conversation -- and folding
 * them together would need a discriminator plus two nullable columns that are
 * never both meaningful. The composition rule that reads them is in
 * `com.inhuman.core.ai.policy.PolicySet`, and it is a maximum, so nothing here
 * has to encode precedence.
 *
 * `policy` is a `ToolPolicy` **name**, stored as text rather than an ordinal:
 * an ordinal silently changes meaning if the enum is ever reordered, and these
 * rows are small enough that the readability is free. `ToolPolicy.parse` treats
 * an unrecognised value as `Free`, so a row written by a newer build is ignored
 * rather than fatal.
 */
@Entity(tableName = "approval_defaults")
data class ApprovalDefaultEntity(
	@androidx.room.PrimaryKey val toolName: String,
	val policy: String,
)

/**
 * A rule on one assistant.
 *
 * Keyed by assistant, not by conversation: capability is a property of the
 * assistant, which is what the user picks before a conversation exists. A
 * conversation does not carry its own tool rules.
 */
@Entity(tableName = "agent_approval", primaryKeys = ["agentId", "toolName"])
data class AgentApprovalEntity(
	val agentId: String,
	val toolName: String,
	val policy: String,
)

/**
 * A rule on one file or directory inside a conversation's workspace.
 *
 * `relPath` is **workspace-relative** and normalised before it is stored or
 * looked up -- produced by `SessionWorkspace.resolve` and relativised, never
 * assembled from model input. Absolute paths are not used because a workspace
 * can be moved wholesale, and because comparing them means a string-prefix test,
 * which is what `a/../b` walks through. The empty string is the workspace root.
 *
 * A rule on a directory covers everything beneath it, compared segment-wise;
 * see `PolicySet.pathPolicyFor`.
 */
@Entity(tableName = "path_policy", primaryKeys = ["conversationId", "relPath"])
data class PathPolicyEntity(
	val conversationId: String,
	@ColumnInfo(name = "relPath") val relPath: String,
	val policy: String,
)
