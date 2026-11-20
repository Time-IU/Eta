package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONArray
import org.json.JSONObject

/** Complete parameters, including content/edits/environment; ordering of object keys is irrelevant. */
internal fun canonicalApprovalJson(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        JSONObject.quote(it) + ":" + canonicalApprovalJson(value.get(it))
    }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalApprovalJson(value.get(it)) }
    is String -> JSONObject.quote(value)
    is Number, is Boolean -> value.toString()
    else -> JSONObject.quote(value.toString())
}
