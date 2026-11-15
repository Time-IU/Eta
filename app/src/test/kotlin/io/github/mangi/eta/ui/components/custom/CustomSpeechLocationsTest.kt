package io.github.mangi.eta.ui.components.custom

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomSpeechLocationsTest {
    @Test fun emptyTextHasNoSentence() {
        for (sentences in listOf(emptyList(), listOf("", ""))) {
            val map = CustomSpeechLocations(sentences)
            assertEquals(0, map.totalUtf16)
            assertEquals(0, map.startOffset(0))
            assertEquals(-1, map.sentenceAt(0))
        }
    }
    @Test fun boundariesClampAndEofSelectsLastSentence() {
        val map = CustomSpeechLocations(listOf("你好。", "OK!", "末句"))
        assertEquals(8, map.totalUtf16)
        assertEquals(0, map.sentenceAt(-100))
        assertEquals(0, map.sentenceAt(2))
        assertEquals(1, map.sentenceAt(3))
        assertEquals(2, map.sentenceAt(6))
        assertEquals(2, map.sentenceAt(8))
        assertEquals(2, map.sentenceAt(Int.MAX_VALUE))
        assertEquals(0, map.startOffset(-1))
        assertEquals(6, map.startOffset(100))
    }
    @Test fun surrogatePairsCountAsTwoUtf16Units() {
        val map = CustomSpeechLocations(listOf("😀。", "下句"))
        assertEquals(5, map.totalUtf16)
        assertEquals(0, map.sentenceAt(1))
        assertEquals(0, map.sentenceAt(2))
        assertEquals(1, map.sentenceAt(3))
        assertEquals(3, map.startOffset(1))
    }
    @Test fun emptySegmentsAreSkippedAtBoundariesAndEof() {
        val map = CustomSpeechLocations(listOf("", "甲", "", "乙乙", ""))
        assertEquals(1, map.sentenceAt(0))
        assertEquals(3, map.sentenceAt(1))
        assertEquals(3, map.sentenceAt(3))
    }
    @Test fun singleSentenceNeverMapsOutsideItsIndex() {
        val map = CustomSpeechLocations(listOf("a"))
        for (offset in listOf(-1, 0, 1, Int.MAX_VALUE)) assertEquals(0, map.sentenceAt(offset))
    }
    @Test fun everySentenceStartRoundTripsAndOffsetsAreMonotone() {
        val sentences = listOf("第一句。", "Second sentence!", "😀第三句", "z")
        val map = CustomSpeechLocations(sentences)
        sentences.indices.forEach { assertEquals(it, map.sentenceAt(map.startOffset(it))) }
        var previous = 0
        for (offset in 0..map.totalUtf16) {
            val current = map.sentenceAt(offset)
            org.junit.Assert.assertTrue(current >= previous)
            previous = current
        }
    }
}
