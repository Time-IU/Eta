package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class CompactMetricSizingTest {
    @Test fun enoughSpaceKeepsReferenceFont() = assertEquals(14f, compactMetricFontSize(240f, 200f), 0f)
    @Test fun usesCombinedLabelAndValueWidth() = assertEquals(11.2f, compactMetricFontSize(160f, 200f), 0.001f)
    @Test fun extremeAccessibilitySizesKeepReadableFloorAndFullOverflowContent() = assertEquals(10f, compactMetricFontSize(20f, 400f), 0f)
    @Test fun noTextNeverDividesByZero() = assertEquals(14f, compactMetricFontSize(100f, 0f), 0f)
}
