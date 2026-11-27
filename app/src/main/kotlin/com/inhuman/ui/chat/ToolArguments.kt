package com.inhuman.ui.chat

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The two argument readers the proposal tools share.
 *
 * Extracted when the workshop started using the same shape as
 * `rp_create_scene`: a second copy of "read a string, refuse a number" would be
 * two answers to the same question, and the refusal is the interesting part --
 * the schema declares the type, so a number or object where a name belongs is a
 * misread call, not a value to stringify.
 */
internal fun JsonObject.stringArgument(name: String): String? {
	val element = this[name] ?: return null
	if (element is JsonNull) return null
	val primitive = element as? JsonPrimitive
	if (primitive == null || !primitive.isString) {
		throw IllegalArgumentException("`$name` must be a string")
	}
	return primitive.content
}

/** An object argument, or null when it is absent or JSON null. */
internal fun JsonObject.objectArgument(name: String): JsonObject? {
	val element = this[name] ?: return null
	if (element is JsonNull) return null
	return element as? JsonObject
		?: throw IllegalArgumentException("`$name` must be a JSON object")
}
