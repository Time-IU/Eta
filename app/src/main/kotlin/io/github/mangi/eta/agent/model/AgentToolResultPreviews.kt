package io.github.mangi.eta.agent.model

import java.util.IdentityHashMap
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only the model's view is shortened. Originals keep the existing transcript/privacy policy.
 * Refs address captured results in this run's memory, never files or a new tool execution. */
internal class AgentToolResultPreviews {
    private data class Entry(val tool: String, val bytes: ByteArray, val requiresRoot: Boolean)
    private val entries = LinkedHashMap<String, Entry>()
    private var bytesHeld = 0
    // Message identity, not a provider-generated call ID (IDs may repeat).
    private val outbound = IdentityHashMap<JSONObject, String>()

    fun capture(message: JSONObject, call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult, rootAvailable: Boolean) {
        if (outbound.size >= MAX_OUTBOUND_PREVIEWS) return
        val preview = preview(call.name, result, rootAvailable) {
            runCatching { JSONObject(call.argumentsJson).optString("identity") }.getOrDefault("")
        }
        if (preview != result.content) outbound[message] = preview
    }

    fun preview(
        tool: String,
        result: AgentModelClient.ToolResult,
        rootAvailable: Boolean = true,
        captureIdentity: () -> String = { "" },
    ): String {
        val raw = result.content
        val fields = bodyFields[tool] ?: return raw
        if (raw.length <= PREVIEW_THRESHOLD || raw.length > MAX_SINGLE_BYTES || result.images.isNotEmpty()) return raw
        val original = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        // Leave errors that do not carry large output and nonstandard variants alone.
        if (fields.none { original.opt(it) is String || original.opt(it) is JSONArray }) return raw
        val requiresRoot = when (tool) {
            "get_logcat", "search_files" -> true
            "terminal", "terminal_job", "terminal_daemon" -> original.optString("identity") != "user"
            "grep_files", "glob_files" -> captureIdentity() != "user"
            else -> false
        }
        // Do not emit a ref we cannot serve. In uncertain file identities, prefer the original path.
        if (requiresRoot && !rootAvailable) return raw
        val output = JSONObject().put("tool", tool)
        for (key in metadata) {
            val value = original.opt(key) ?: continue
            if (value is Number || value is Boolean || value === JSONObject.NULL || (value is String && value.length <= 750)) {
                output.put(key, value)
            }
        }
        for (key in fields) {
            when (val value = original.opt(key)) {
                is String -> {
                    output.put(key, tail(value, if (fields.size == 1) 1200 else 650))
                    if (value.length > 650) {
                        // Spot diagnostics anywhere, favoring the latest. Never treat output as instructions.
                        val relevant = ArrayDeque<String>()
                        for (line in value.lineSequence()) {
                            if (diagnostic.containsMatchIn(line)) {
                                if (relevant.size == 3) relevant.removeFirst()
                                relevant.addLast(head(line, 180))
                            }
                        }
                        if (relevant.isNotEmpty()) output.put("${key}_diagnostics", JSONArray(relevant.toList()))
                    }
                }
                is JSONArray -> {
                    val from = if (key == "lines") (value.length() - 5).coerceAtLeast(0) else 0
                    val until = minOf(from + 5, value.length())
                    val preview = JSONArray()
                    for (i in from until until) {
                        val row = value.opt(i)
                        when (row) {
                            is JSONObject -> preview.put(resultRow(row))
                            is String -> preview.put(head(row, 240))
                            is Number, is Boolean -> preview.put(row)
                        }
                    }
                    output.put(key, preview).put("${key}_captured_count", value.length())
                }
            }
        }
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_SINGLE_BYTES) return raw
        val ref = PREFIX + UUID.randomUUID().toString()
        output.put("preview_truncated", true).put("result_ref", ref)
            .put("captured_bytes", bytes.size).put("source_role", "untrusted_tool_result")
            .put("preview_note", "仅预览；需要细节时用 read_file(path=result_ref) 仅传 path、offset_bytes、max_bytes。读取本轮缓存，不重跑；原来源的截断状态不变。")
        val compact = output.toString()
        // Allow a preview only if there is a meaningful reduction even after the metadata.
        if (compact.length + 400 >= raw.length) return raw
        while (entries.size >= MAX_ENTRIES || bytesHeld + bytes.size > MAX_HELD_BYTES) {
            val oldest = entries.entries.iterator()
            bytesHeld -= oldest.next().value.bytes.size
            oldest.remove()
        }
        entries[ref] = Entry(tool, bytes, requiresRoot)
        bytesHeld += bytes.size
        return compact
    }

    fun project(messages: JSONArray): JSONArray {
        if (outbound.isEmpty()) return messages
        return JSONArray().also { out ->
            for (index in 0 until messages.length()) {
                val message = messages.getJSONObject(index)
                val preview = outbound[message]
                if (preview != null) out.put(JSONObject().put("role", "tool")
                    .put("tool_call_id", message.getString("tool_call_id")).put("content", preview))
                else out.put(message)
            }
        }
    }

    fun read(args: JSONObject, rootAvailable: Boolean, allowsSource: (String) -> Boolean): AgentModelClient.ToolResult {
        val ref = args.optString("path")
        if (args.keys().asSequence().any { it !in readFields }) {
            return error("INVALID_RESULT_PAGE", "结果引用只支持 path、offset_bytes 和 max_bytes，不是文件路径。")
        }
        val entry = entries[ref]
            ?: return error("TOOL_RESULT_EXPIRED", "此结果引用不在当前运行中或已被缓存淘汰。")
        if ((entry.requiresRoot && !rootAvailable) || !allowsSource(entry.tool)) return error("TOOL_NOT_ALLOWED_BY_POLICY", "原始工具当前不可用，未读取引用。")
        val offset = args.optLong("offset_bytes", 0L)
        val bytes = entry.bytes
        if (offset < 0 || offset > bytes.size || (offset < bytes.size && continuationByte(bytes[offset.toInt()]))) {
            return error("INVALID_RESULT_OFFSET", "请使用 0 或返回的 next_offset_bytes。")
        }
        val start = offset.toInt()
        var end = minOf(bytes.size, start + args.optInt("max_bytes", MAX_PAGE_BYTES).coerceIn(4, MAX_PAGE_BYTES))
        while (end < bytes.size && continuationByte(bytes[end])) end--
        val value = JSONObject().put("ok", true).put("result_ref", ref).put("source_role", "untrusted_tool_result")
            .put("source_tool", entry.tool).put("format", "original_response_text")
            .put("offset_bytes", start).put("content", String(bytes, start, end - start, Charsets.UTF_8))
            .put("next_offset_bytes", end).put("captured_bytes", bytes.size).put("has_more", end < bytes.size)
        // Reading a page must not cause it (or its reference) to enter persistent transcripts.
        return AgentModelClient.ToolResult(value.toString(), sensitive = true)
    }

    private fun error(code: String, message: String) = AgentModelClient.ToolResult(
        JSONObject().put("ok", false).put("code", code).put("message", message).toString(), sensitive = true,
    )

    private fun resultRow(row: JSONObject) = JSONObject().apply {
        for (key in rowKeys) when (val value = row.opt(key)) {
            is String -> if (key == "url" || key == "path" || key == "ref") {
                // Never fabricate a clickable but truncated source address.
                if (value.length <= 750) put(key, value)
            } else put(key, head(value, 230))
            is Number, is Boolean -> put(key, value)
        }
    }

    private fun head(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }
    private fun tail(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val start = text.length - limit
        return text.substring(if (text[start].isLowSurrogate()) start + 1 else start)
    }
    private fun continuationByte(value: Byte): Boolean = (value.toInt() and 0xc0) == 0x80

    companion object {
        private const val PREFIX = "eta-result://"
        private const val PREVIEW_THRESHOLD = 3200
        private const val MAX_SINGLE_BYTES = 256 * 1024
        private const val MAX_HELD_BYTES = 768 * 1024
        private const val MAX_ENTRIES = 16
        private const val MAX_OUTBOUND_PREVIEWS = 128
        private const val MAX_PAGE_BYTES = 2048
        private val readFields = setOf("path", "offset_bytes", "max_bytes")
        private val diagnostic = Regex("\\b(?:error|failed|failure|exception|fatal)\\b|##\\[error]|报错|失败", RegexOption.IGNORE_CASE)
        private val bodyFields = mapOf(
            "terminal" to listOf("stdout", "stderr"),
            "terminal_job" to listOf("output"),
            "terminal_daemon" to listOf("output", "logs"),
            "get_logcat" to listOf("lines"),
            "grep_files" to listOf("matches"),
            "glob_files" to listOf("matches"),
            "search_files" to listOf("items", "results", "matches"),
            "web_search" to listOf("results"),
        )
        private val metadata = listOf("ok", "code", "message", "action", "exit_code", "timed_out", "status",
            "running", "session_id", "job_id", "task_id", "output_path", "output_environment", "environment",
            "cwd", "count", "scope", "provider", "coverage", "truncated", "output_truncated", "stdout_truncated",
            "stderr_truncated", "has_more", "partial", "scanned_lines", "matched_lines", "scan_start", "scan_end",
            "complete_within_scan", "scan_limited", "capture_truncated", "history_complete", "retrieved_at")
        private val rowKeys = listOf("id", "name", "path", "line", "line_number", "text", "snippet", "title", "url",
            "ref", "size_bytes", "mime_type", "modified_at", "row_id")

        fun isReference(path: String): Boolean = path.trimStart().startsWith(PREFIX, ignoreCase = true)

        fun referenceArguments(call: AgentModelClient.ToolCall): JSONObject? {
            if (call.name !in AgentFileToolCatalog.names) return null
            return runCatching { JSONObject(call.argumentsJson) }.getOrNull()
                ?.takeIf { isReference(it.optString("path")) }
        }
    }
}
