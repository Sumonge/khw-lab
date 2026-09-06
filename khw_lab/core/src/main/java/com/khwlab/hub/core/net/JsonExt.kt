package com.khwlab.hub.core.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 从动态 JSON 里取字段的小工具(固件返回的 JSON 结构不固定)。 */
fun JsonElement.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.content

fun JsonElement.int(key: String): Int? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.intOrNull

fun JsonElement.long(key: String): Long? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.longOrNull

fun JsonElement.bool(key: String): Boolean? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.booleanOrNull
