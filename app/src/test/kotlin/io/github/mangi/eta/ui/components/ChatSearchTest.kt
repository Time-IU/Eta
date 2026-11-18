package io.github.mangi.eta.ui.components

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    private fun hits(body: String, query: String) =
        findChatSearchHits(listOf(ChatSearchDocument("message", body)), query)

    @Test fun emptyDocumentsAndBlankQueriesProduceNoMatches() {
        assertTrue(findChatSearchHits(emptyList(), "hello").isEmpty())
        listOf("", " ", "\n\t").forEach { assertTrue(hits("正文", it).isEmpty()) }
        assertTrue(hits("", "正文").isEmpty())
    }

    @Test fun searchesEveryDocumentNotJustTheLatestOrVisibleOne() {
        val found = findChatSearchHits(listOf(
            ChatSearchDocument("offscreen-user", "查找内容"),
            ChatSearchDocument("offscreen-assistant", "内容和内容"),
            ChatSearchDocument("latest", "不匹配"),
        ), "内容")
        assertEquals(listOf("offscreen-user", "offscreen-assistant", "offscreen-assistant"), found.map { it.messageId })
        assertEquals(listOf(2, 0, 3), found.map { it.start })
    }

    @Test fun countsEveryOccurrenceIncludingOverlaps() {
        assertEquals(listOf(0, 1, 2), hits("aaaa", "aa").map { it.start })
        assertEquals(listOf(0, 2, 4), hits("哈哈哈哈哈哈", "哈哈").filter { it.start % 2 == 0 }.map { it.start })
        assertEquals(5, hits("哈哈哈哈哈哈", "哈哈").size)
    }

    @Test fun matchesChineseAndMixedCaseWithoutChangingOffsets() {
        val body = "中文Eta，ETA 与 eta"
        val found = hits(body, "eTa")
        assertEquals(listOf(2, 6, 12), found.map { it.start })
        assertTrue(found.all { body.substring(it.start, it.end).equals("eta", ignoreCase = true) })
        assertEquals(1, hits(body, "中文").size)
    }

    @Test fun matchingIsLiteralNotRegex() {
        assertEquals(2, hits("[a].* and [a].*", "[a].*").size)
        assertTrue(hits("abc", ".*").isEmpty())
    }

    @Test fun nonblankQueryRetainsMeaningfulWhitespace() {
        assertEquals(1, hits("a b a  b", "a b").size)
        assertEquals(1, hits("a b a  b", "a  b").size)
    }

    @Test fun caseMatchingDoesNotDependOnDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(listOf(0, 2), hits("I i", "i").map { it.start })
        } finally { Locale.setDefault(before) }
    }

    @Test fun preservesUtf16OffsetsBesideEmoji() {
        val body = "😀目标😀目标"
        val found = hits(body, "目标")
        assertEquals(listOf(2, 6), found.map { it.start })
        assertEquals(listOf(0, 4), hits(body, "😀").map { it.start })
        found.forEach { assertEquals("目标", body.substring(it.start, it.end)) }
    }

    @Test fun queryLongerThanBodyHasNoMatches() {
        assertTrue(hits("a", "aaaa").isEmpty())
    }

    @Test fun previousAndNextWrapWithNoDivideByZero() {
        assertEquals(-1, nextChatSearchIndex(-1, 0, 1))
        assertEquals(2, nextChatSearchIndex(0, 3, -1))
        assertEquals(0, nextChatSearchIndex(2, 3, 1))
        assertEquals(0, nextChatSearchIndex(0, 1, -1))
    }

    @Test fun prependingHistoryPreservesTheExactOccurrence() {
        val old = hits("find find", "find")
        val selected = old[1].anchor
        val updated = findChatSearchHits(listOf(
            ChatSearchDocument("earlier", "find find find"),
            ChatSearchDocument("message", "find find"),
        ), "find")
        assertEquals(4, resolveChatSearchIndex(updated, selected))
        assertEquals(selected, updated[resolveChatSearchIndex(updated, selected)].anchor)
    }

    @Test fun streamingAppendUpdatesCountWithoutMovingSelection() {
        val initial = hits("one one", "one")
        val updated = hits("one one one", "one")
        assertEquals(3, updated.size)
        assertEquals(1, resolveChatSearchIndex(updated, initial[1].anchor))
    }

    @Test fun removedOrRewrittenMatchHasSafeFallback() {
        assertEquals(-1, resolveChatSearchIndex(emptyList(), ChatSearchAnchor("missing", 0)))
        assertEquals(0, resolveChatSearchIndex(hits("one", "one"), ChatSearchAnchor("missing", 40)))
        val updated = hits("a target b target", "target")
        assertEquals(1, resolveChatSearchIndex(updated, ChatSearchAnchor("message", 3)))
    }

    @Test fun changingQueryStartsFromFirstMatchWhenSelectionIsReset() {
        assertEquals(0, resolveChatSearchIndex(hits("hello hello", "hello"), null))
    }

    @Test fun previewShowsHitDeepInsideHugeMultilineMessage() {
        val body = "line of context\n".repeat(10000) + "唯一目标" + "\ntrailing context".repeat(10000)
        val hit = hits(body, "唯一目标").single()
        val preview = chatSearchPreview(body, hit)
        assertEquals("唯一目标", preview.match)
        assertTrue(preview.before.startsWith("…"))
        assertTrue(preview.after.endsWith("…"))
        assertFalse(preview.before.contains('\n'))
        assertFalse(preview.after.contains('\n'))
        assertTrue(preview.before.length < 100)
        assertTrue(preview.after.length < 140)
    }

    @Test fun previewHasSafeEdgesAndLimitsVeryLongQueries() {
        assertEquals(ChatSearchPreview("", "目标", ""), chatSearchPreview("目标", hits("目标", "目标").single()))
        val long = "匹".repeat(4000)
        val preview = chatSearchPreview(long, hits(long, long).single())
        assertEquals(161, preview.match.length)
        assertTrue(preview.match.endsWith("…"))
        assertEquals("", preview.after)
    }

    @Test fun previewDoesNotCutEmojiIntoUnpairedSurrogates() {
        val body = "😀".repeat(200) + "X" + "😀".repeat(200)
        val preview = chatSearchPreview(body, hits(body, "X").single())
        listOf(preview.before, preview.match, preview.after).forEach { text ->
            var i = 0
            while (i < text.length) {
                assertFalse(Character.isLowSurrogate(text[i]))
                i += if (Character.isHighSurrogate(text[i])) {
                    assertTrue(i + 1 < text.length && Character.isLowSurrogate(text[i + 1]))
                    2
                } else 1
            }
        }
    }

    @Test(expected = InterruptedException::class)
    fun largeRepeatedSearchChecksCancellationDuringScan() {
        var checks = 0
        findChatSearchHits(listOf(ChatSearchDocument("large", "a".repeat(10000))), "a") {
            if (++checks == 2) throw InterruptedException("cancelled")
        }
    }
}
