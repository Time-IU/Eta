package io.github.mangi.eta.ui.components.custom;

import org.junit.Test;
import static org.junit.Assert.*;

public class SentenceSelectionTest {
    private void hit(String text, String needle, String expected) {
        int offset = text.indexOf(needle);
        assertTrue(offset >= 0);
        int[] range = SentenceSelection.range(text, offset);
        assertEquals(expected, text.substring(range[0], range[1]));
    }
    @Test public void simplifiedChineseNotWholeParagraph() { hit("第一句。第二句！第三句？", "二", "第二句！"); }
    @Test public void traditionalChinese() { hit("這是前句。這是後句！還有一句。", "後", "這是後句！"); }
    @Test public void english() { hit("First sentence. The second sentence! Last?", "second", "The second sentence!"); }
    @Test public void mixedLanguage() { hit("你好。Hello world!再見。", "world", "Hello world!"); }
    @Test public void chineseQuotes() { hit("前句。他說：「你好！」後句。", "好", "他說：「你好！」"); }
    @Test public void englishQuotes() { hit("First. \"Next?\" Last.", "Next", "\"Next?\""); }
    @Test public void newlineEvenWithoutPunctuation() { hit("第一行\n第二行\n第三行", "二", "第二行"); }
    @Test public void crlfAndBlankLines() { hit("One\r\n\r\n  Two\r\nThree", "Two", "Two"); }
    @Test public void consecutivePunctuationAndClosingQuotes() { hit("前句。『真的？！』下一句。", "真", "『真的？！』"); }
    @Test public void ellipsis() { hit("等等……好了。下一句。", "等", "等等……"); }
    @Test public void decimal() { hit("Value is 3.14 today. Next.", "14", "Value is 3.14 today."); }
    @Test public void domain() { hit("Visit example.com now. Next.", "com", "Visit example.com now."); }
    @Test public void abbreviation() { hit("Dr. Smith is here. Next.", "Smith", "Dr. Smith is here."); }
    @Test public void latinAbbreviation() { hit("Try e.g. this option. Next.", "option", "Try e.g. this option."); }
    @Test public void repeatedSentencesUseOffsetNotIndexOf() {
        String text = "重复。中间。重复。尾句。";
        int[] r = SentenceSelection.range(text, text.lastIndexOf("重复") + 1);
        assertArrayEquals(new int[]{6, 9}, r);
    }
    @Test public void supplementaryCodePointNotSplit() { hit("前句。😀很好！后句。", "😀", "😀很好！"); }
    @Test public void lowSurrogateHitUsesSameSentence() {
        assertArrayEquals(SentenceSelection.range("😀!Next.", 0), SentenceSelection.range("😀!Next.", 1));
    }
    @Test public void noPunctuationIsOneActualSentence() { hit("  A sentence without a period  ", "without", "A sentence without a period"); }
    @Test public void offsetsClampAndEmptyIsEmpty() {
        assertArrayEquals(new int[]{0, 0}, SentenceSelection.range("", 500));
        assertArrayEquals(new int[]{0, 4}, SentenceSelection.range("A.B!", -8));
        assertArrayEquals(new int[]{0, 4}, SentenceSelection.range("A.B!", 100));
    }
    @Test public void punctuationBelongsToSentenceBeforeIt() { hit("First. Next.", ".", "First."); }
    @Test public void trailingWhitespaceNoFakeParagraphFallback() {
        assertArrayEquals(new int[]{4,4}, SentenceSelection.range("A.  ", 3));
    }
    @Test public void everyUtf16OffsetHasSafeBounds() {
        String s = "首句。‘Second?!’\n最後一句😀。";
        for (int i = -1; i <= s.length()+1; i++) {
            int[] r = SentenceSelection.range(s, i);
            assertTrue(r[0] >= 0 && r[1] >= r[0] && r[1] <= s.length());
            if (r[0] < s.length()) assertFalse(Character.isLowSurrogate(s.charAt(r[0])));
        }
    }
}
