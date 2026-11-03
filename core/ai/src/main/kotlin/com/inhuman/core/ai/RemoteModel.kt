package com.inhuman.core.ai

/**
 * A model as the provider describes it.
 *
 * Populated from `GET /models` rather than hardcoded, which is not merely
 * tidier: DeepSeek reports `context_window`, `max_output_tokens`,
 * `input_modalities` and the supported reasoning effort levels per model, and
 * those differ between models. `deepseek-v4-pro` does not accept images;
 * `deepseek-flash` does. Hardcoding a list would mean the app offers an image
 * attachment on a model that will reject it, and silently breaks each time the
 * provider ships a model.
 */
data class RemoteModel(
	val id: String,
	val displayName: String? = null,
	val contextWindow: Int? = null,
	val maxOutputTokens: Int? = null,
	val inputModalities: List<String> = emptyList(),
	val supportedEffortLevels: List<String> = emptyList(),
	val defaultEffortLevel: String? = null,
) {
	/**
	 * Whether this model accepts image input.
	 *
	 * Both spellings are real: DeepSeek reports the broad modality `"image"`,
	 * while OpenCode's gateway reports MIME types (`"image/jpeg"`, `"image/png"`,
	 * ...). Checking only the broad word made every OpenCode vision model look
	 * text-only, and the composer refused an attachment the model would have
	 * accepted. The parser normalises these too; this is the second reader that
	 * must not disagree with it.
	 */
	val supportsVision: Boolean
		get() = inputModalities.any { it == "image" || it.startsWith("image/") }
}
