package com.inhuman.core.ai.model

/**
 * Recognises the provider failures that mean "this is not a chat model".
 *
 * ## Why this is a guess too, and a narrow one
 *
 * The app cannot know a model's wire contract before calling it, and the
 * providers do not have a common error shape. What they do have is a family of
 * 400s that talk about `messages`, roles or content not being what the endpoint
 * expects -- the real one, from Qwen called with `wan2.7-image`, was:
 *
 * ```
 * HTTP 400 Bad Request: {"code":"InvalidParameter","message":"Input should be
 * 'user': input.messages.0.role & Input should be a valid list:
 * input.messages.0.content ..."}
 * ```
 *
 * Telling a user "this model may not be a chat model" on a *different* 400 (an
 * over-long prompt, a bad temperature) would be worse than saying nothing, so
 * every rule here needs both a validation word and a message-shaped word. A
 * false negative just leaves the raw error, which is what the app showed for
 * every failure before this existed.
 */
object ModelRejection {

	/** Validation words that appear when a request shape is rejected. */
	private val validationWords = listOf(
		"input should be",
		"invalidparameter",
		"invalid parameter",
		"invalid value",
	)

	/** Words that make it a *message* problem rather than, say, a temperature. */
	private val chatWords = listOf("messages", "message", "role", "content")

	/** Unambiguous "wrong kind of model" phrasings. */
	private val modelWords = listOf(
		"not a chat model",
		"model_not_supported",
		"unsupported model",
		"model is not supported",
		"does not support chat",
		"does not support messages",
	)

	fun looksLikeModelRejection(message: String): Boolean {
		val text = message.lowercase()
		if (modelWords.any { it in text }) return true
		return validationWords.any { it in text } && chatWords.any { it in text }
	}
}