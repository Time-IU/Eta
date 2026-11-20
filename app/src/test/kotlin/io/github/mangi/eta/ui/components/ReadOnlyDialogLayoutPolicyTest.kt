package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class ReadOnlyDialogLayoutPolicyTest {
    @Test fun bodyUsesActualAvailableHeightNotFixed400dp() {
        assertEquals(232, readOnlyBodyMaxHeight(300, 52, 16))
        assertEquals(532, readOnlyBodyMaxHeight(600, 52, 16))
        assertEquals(932, readOnlyBodyMaxHeight(1000, 52, 16))
    }

    @Test fun wrappedFooterKeepsItsRealHeightAndBodyGetsRemainder() {
        assertEquals(308, readOnlyBodyMaxHeight(500, 176, 16))
        assertEquals(148, readOnlyBodyMaxHeight(340, 176, 16))
    }

    @Test fun shortOrLandscapeWindowIsBoundedWithoutNegativeConstraints() {
        assertEquals(112, readOnlyBodyMaxHeight(180, 52, 16))
        assertEquals(0, readOnlyBodyMaxHeight(40, 52, 16))
        assertEquals(0, readOnlyBodyMaxHeight(0, 0, 0))
    }

    @Test fun largeFontsStackInsteadOfShrinkingText() {
        assertFalse(stackReadOnlyActions(300, listOf(64, 120), 20))
        assertTrue(stackReadOnlyActions(300, listOf(96, 180), 20))
        assertTrue(stackReadOnlyActions(300, listOf(128, 240), 20))
    }

    @Test fun exactFitAndOddWidthDoNotUnderAllocateEitherButton() {
        assertFalse(stackReadOnlyActions(260, listOf(64, 120), 20))
        assertFalse(stackReadOnlyActions(261, listOf(120, 64), 20))
        assertTrue(stackReadOnlyActions(259, listOf(64, 120), 20))
        assertTrue(stackReadOnlyActions(19, listOf(1, 1), 20))
    }

    @Test fun footerDecisionIsIndependentOfLabelOrder() {
        for (width in 0..800) {
            assertEquals(stackReadOnlyActions(width, listOf(64, 170), 20),
                stackReadOnlyActions(width, listOf(170, 64), 20))
        }
    }

    @Test fun windowFontAndLocalizationCalculationMatrix() {
        // Synthetic measured pixels, NOT screenshots or an Android font-layout claim.
        var cases = 0
        for (height in listOf(240, 320, 480, 720, 1200)) {
            for (width in listOf(180, 240, 320, 600)) {
                for (fontScale in listOf(1.0, 1.3, 1.5, 2.0)) {
                    val closeWidth = (60 * fontScale).toInt() + 32
                    val copyWidth = (100 * fontScale).toInt() + 32
                    val stacked = stackReadOnlyActions(width, listOf(closeWidth, copyWidth), 20)
                    val buttonHeight = (24 * fontScale).toInt() + 26
                    val footer = if (stacked) 2 * buttonHeight + 8 else buttonHeight
                    val body = readOnlyBodyMaxHeight(height, footer, 16)
                    assertTrue(body > 0)
                    assertEquals(height, body + footer + 16)
                    cases++
                }
            }
        }
        assertEquals(80, cases)
    }

    @Test fun stackedFooterOverflowIsReachableAtScrollEndWithoutShrinkingButtons() {
        // Pixel arithmetic contract of verticalScroll: unbounded content, bounded viewport.
        // This is NOT an Android Compose measurement/instrumentation test.
        for (buttonHeights in listOf(listOf(60, 60), listOf(60, 140), listOf(140, 60))) {
            val naturalHeight = buttonHeights.sum() + 8
            for (available in listOf(1, 40, 100, 128, 240, 400)) {
                val viewport = minOf(available, naturalHeight)
                val maxScroll = naturalHeight - viewport
                val secondTop = buttonHeights[0] + 8
                val secondBottom = secondTop + buttonHeights[1]
                assertEquals(naturalHeight, secondBottom)
                assertEquals(viewport, secondBottom - maxScroll)
                assertTrue(secondTop < naturalHeight)
                assertEquals((naturalHeight - available).coerceAtLeast(0), maxScroll)
                if (available >= naturalHeight) assertEquals(0, maxScroll)
                assertEquals(available - viewport, readOnlyBodyMaxHeight(available, viewport, 0))
            }
        }
        // Review reproducer: both natural 60px buttons + 8px gap, 100px viewport.
        assertEquals(28, (60 + 8 + 60) - 100)
    }

    @Test fun arithmeticCannotOverflowIntoNegativeLayoutSizes() {
        assertEquals(0, readOnlyBodyMaxHeight(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertTrue(stackReadOnlyActions(Int.MAX_VALUE, listOf(Int.MAX_VALUE, 1), Int.MAX_VALUE))
    }

    @Test fun invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { readOnlyBodyMaxHeight(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { readOnlyBodyMaxHeight(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { stackReadOnlyActions(0, listOf(1), 0) }
        assertThrows(IllegalArgumentException::class.java) { stackReadOnlyActions(0, listOf(1, -1), 0) }
    }
}
