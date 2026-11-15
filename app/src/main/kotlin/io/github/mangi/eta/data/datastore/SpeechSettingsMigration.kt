package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.data.model.AsrProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Pure ASR-only disk migration; preserve every other JSON value, including unknown fields. */
internal fun migratedLocalAsrJson(json: Json, raw: String): String? {
    val fields = json.parseToJsonElement(raw).jsonObject
    val name = fields["asr"]?.jsonPrimitive?.content
    if (name != AsrProvider.SHERPA_NCNN.name && name != AsrProvider.QWEN3_LOCAL.name) return null
    return JsonObject(fields.toMutableMap().apply {
        put("asr", JsonPrimitive(AsrProvider.LOCAL.name))
    }).toString()
}
