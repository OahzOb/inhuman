package com.inhuman.core.ai.bilibili

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient accessors for the endpoint JSON.
 *
 * ## Why these are shared and internal
 *
 * Every reader in this package parses the same unofficial surface, and the
 * failure mode worth designing for is uniform: a renamed or missing field must
 * degrade to a value whose default means something (a count of zero), never to
 * an exception in the middle of a response. These are deliberately total:
 * `string` on a number returns null, `long` on a missing key returns zero.
 *
 * They became shared when the surface grew from five endpoints to a dozen. The
 * alternative -- a private copy in each parser -- is how one endpoint's "null
 * counts as zero" quietly becomes another's "null throws".
 */
internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.string(name: String): String? =
	(this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(name: String): Long =
	(this[name] as? JsonPrimitive)?.longOrNull ?: 0L

internal fun JsonObject.int(name: String): Int =
	(this[name] as? JsonPrimitive)?.intOrNull ?: 0

internal fun JsonObject.double(name: String): Double =
	(this[name] as? JsonPrimitive)?.doubleOrNull ?: 0.0

internal fun JsonObject.bool(name: String): Boolean =
	(this[name] as? JsonPrimitive)?.booleanOrNull ?: false
