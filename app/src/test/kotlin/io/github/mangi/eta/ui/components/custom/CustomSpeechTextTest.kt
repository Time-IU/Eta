package io.github.mangi.eta.ui.components.custom

import org.junit.Assert.*
import org.junit.Test

class CustomSpeechTextTest {
    @Test fun sentenceLocationsKeepChinesePunctuation() {
        assertEquals(listOf("第一句。", "第二句！", "第三句？", "尾句"),
            CustomSpeechText.splitReadable("第一句。第二句！第三句？尾句"))
    }

    @Test fun englishAndNewlinesRemainOrdered() {
        assertEquals(listOf("Hello.", "Next!", "第三句。", "尾句"),
            CustomSpeechText.splitReadable("Hello. Next!\n第三句。\n尾句"))
    }

    @Test fun decimalsAreNotSentenceBoundaries() {
        assertEquals(listOf("价格 3.14 元。", "版本 v3.4.3 保持。"),
            CustomSpeechText.splitReadable("价格 3.14 元。版本 v3.4.3 保持。"))
    }

    @Test fun longSentencesKeepSurrogatesAndContent() {
        val input = "甲😀乙".repeat(300)
        val segments = CustomSpeechText.splitReadable(input)
        assertEquals(input, segments.joinToString(""))
        assertTrue(segments.all { it.length <= 320 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }

    @Test fun emptySpeechHasNoLocations() {
        assertTrue(CustomSpeechText.splitReadable(" \n\t ").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedReadableTextIsRejected() {
        CustomSpeechText.splitReadable("甲".repeat(30_001))
    }

    @Test fun officialFormatterKeepsLabelsButOmitsUrlsAndTables() {
        val markdown = "查看 [帮助](https://example.invalid)。\n\n| 项目 | 数量 |\n| --- | --- |\n| 秘密项 | 42 |\n\n最后。"
        val result = CustomSpeechText.sentences(markdown).joinToString("")
        assertTrue(result.contains("帮助"))
        assertTrue(result.contains("最后"))
        assertFalse(result.contains("https"))
        assertFalse(result.contains("秘密项"))
        assertFalse(result.contains("42"))
    }

    @Test fun selectedTextIsTheOnlyInputNotFullMessage() {
        assertEquals(listOf("只读这句。"), CustomSpeechText.sentences("只读这句。"))
    }
}
