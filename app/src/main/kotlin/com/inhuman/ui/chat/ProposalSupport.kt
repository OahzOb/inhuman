package com.inhuman.ui.chat

import com.inhuman.core.engine.roleplay.RoleplayValidator

/**
 * The two rendering helpers every proposal shares.
 *
 * A proposal card is one line in a transcript, not a document: the preview is
 * collapsed and capped so a full card or prompt cannot become a second copy of
 * itself in the chat. The rejection renderer never quotes a field value -- the
 * same rule `rp_validate` follows -- so a rejected proposal cannot become a read
 * path for text the user did not hand over.
 *
 * Extracted when the workshop started producing cards too: two
 * copies of these rules would drift, and the second copy is never the one that
 * gets fixed.
 */
internal fun previewLine(text: String, maxChars: Int = 160): String =
    text.trim().replace(WHITESPACE, " ").take(maxChars)

/** Every blocking finding, one per line; warnings are ignored. */
internal fun blockingProblems(subject: String, issues: List<RoleplayValidator.Issue>): String =
    buildString {
        append(subject).append(" cannot be used as role-play material:")
        for (issue in issues) {
            if (issue.warning) continue
            append("\n- ").append(issue.render())
        }
    }

private val WHITESPACE = Regex("\\s+")
