package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

/**
 * 一条被 @ 引用的历史消息。
 *
 * 保存原文快照和可选精确选区；统一供目录、菜单和选字追问使用。
 */
@Immutable
data class MessageReferenceUi(
    val messageId: String,
    /** 在消息流里的序号（1 基），只用于 chip 上显示 #3 这种标记。 */
    val sequence: Int,
    val fromUser: Boolean,
    /** chip 与面板里的预览，取正文前 60 字。 */
    val preview: String,
    /** 全文匹配用的正文（截断到 4000 字），不显示。 */
    val searchText: String = "",
    /** Exact selected text, or null for the whole original message. Never re-resolve a selection. */
    val selectedText: String? = null,
    /** Original text snapshot also survives history paging and edits. */
    val originalText: String = searchText,
) {
    /** Whole-message and distinct excerpts can coexist; identical excerpts are deduplicated. */
    val referenceKey: String get() = messageId + ":" + (selectedText?.let { "excerpt:$it" } ?: "whole")
}

/** Original body snapshots, never UUIDs or implementation markers, are sent with the draft. */
internal fun renderMessageReferences(
    references: List<MessageReferenceUi>,
    fallback: (String) -> String? = { null },
): String = references.mapNotNull { reference ->
    val snapshot = reference.originalText.takeIf { it.isNotBlank() }
        ?: fallback(reference.messageId) ?: reference.searchText
    // Also clean legacy whole-user snapshots. Never parse or trim an exact native selection.
    val body = reference.selectedText ?: if (reference.fromUser)
        io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.parse(snapshot).request else snapshot
    if (body.isBlank()) null else buildString {
        append(if (reference.fromUser) "[引用 · 我]\n" else "[引用 · Eta]\n")
        append(body.replace("\r\n", "\n").replace('\r', '\n').lineSequence().joinToString("\n") { "> $it" })
        append('\n')
    }
}.joinToString("\n")

internal enum class MessageReferenceDecision { Attach, Duplicate, Limit }
internal fun messageReferenceDecision(current: List<MessageReferenceUi>, incoming: MessageReferenceUi, limit: Int = 3): MessageReferenceDecision = when {
    current.any { it.referenceKey == incoming.referenceKey } -> MessageReferenceDecision.Duplicate
    current.size >= limit -> MessageReferenceDecision.Limit
    else -> MessageReferenceDecision.Attach
}
