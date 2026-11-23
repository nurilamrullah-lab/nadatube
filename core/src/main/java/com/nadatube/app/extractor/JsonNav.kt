package com.nadatube.app.extractor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Null-safe navigation over InnerTube's deeply nested JSON. */
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

internal operator fun JsonElement?.get(index: Int): JsonElement? = (this as? JsonArray)?.getOrNull(index)

internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString || it.toString() != "null" }?.content

internal val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()

internal val JsonElement?.long: Long? get() = str?.toLongOrNull()

internal val JsonElement?.int: Int? get() = str?.toIntOrNull()

internal fun JsonElement?.path(vararg keys: String): JsonElement? = keys.fold(this) { acc, k -> acc[k] }

/** Joins all text runs: `{"runs":[{"text":"a"},{"text":"b"}]}` or `{"simpleText":"ab"}`. */
internal fun JsonElement?.text(): String? =
    this["simpleText"].str ?: this["runs"].arr.mapNotNull { it["text"].str }.joinToString("").ifEmpty { null }

/** Depth-first collection of every object stored under [key], anywhere in the tree. */
internal fun JsonElement?.collect(key: String, out: MutableList<JsonElement> = mutableListOf()): List<JsonElement> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key) out += v
            v.collect(key, out)
        }
        is JsonArray -> forEach { it.collect(key, out) }
        else -> Unit
    }
    return out
}
