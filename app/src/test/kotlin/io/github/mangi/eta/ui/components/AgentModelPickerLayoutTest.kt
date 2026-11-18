package io.github.mangi.eta.ui.components

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contracts complement the measured-geometry tests: increasing a popup's height alone
 * must not reintroduce single-line truncation or a fixed-height row below the font metrics. */
class AgentModelPickerLayoutTest {
    private fun source(): String {
        val relative = "src/main/kotlin/io/github/mangi/eta/ui/components/AgentModelPicker.kt"
        return listOf(File(relative), File("app/$relative"))
            .first { it.isFile }.readText()
    }

    @Test
    fun modelNamesInThePopupAndSheetCanWrapWithoutEllipsis() {
        val row = source().substringAfter("private fun ModelPickerRow(")
            .substringBefore("internal fun ModelBrandMark(")
        assertFalse("A full model name must not be limited to one line", row.contains("maxLines = 1"))
        assertFalse("The picker is the place to read the complete name", row.contains("TextOverflow.Ellipsis"))
        assertFalse("Font scaling must grow rows, not crop them", Regex("\\.height\\(").containsMatchIn(row))
    }

    @Test
    fun providerNamesCanWrapAsWell() {
        val label = source().substringAfter("private fun ModelProviderLabel(")
            .substringBefore("private fun ModelPickerRow(")
        assertFalse(label.contains("maxLines = 1"))
        assertFalse(label.contains("TextOverflow.Ellipsis"))
    }

    @Test
    fun fontMetricsAreMeasuredBeforeUsingTheFixedHeightCouiMenu() {
        val text = source()
        assertTrue(text.contains("state.modelCount <= ModelPopupLimit && compactRowsFit"))
        assertTrue(text.contains("constraints = Constraints(maxWidth = contentWidth.coerceAtLeast(0))"))
        assertTrue(text.contains("hasVisualOverflow = layout.hasVisualOverflow"))
        assertTrue(text.contains("style = menuStyle.itemTextStyle"))
        assertTrue(text.contains("style = menuStyle,"))
        assertTrue(text.contains("style.copy(itemTextStyle = modelPickerTextStyle(style.itemTextStyle))"))
    }

    @Test
    fun popupScrollViewportRespectsTheNativeSafeWindowBounds() {
        val text = source()
        val popup = text.substringAfter("                WindowListPopup(")
            .substringBefore("                WindowBottomSheet(")
        assertFalse(popup.contains("maxHeight = 360.dp"))
        val quickList = text.substringAfter("private fun ModelQuickList(")
            .substringBefore("private sealed interface ModelSheetEntry")
        assertTrue(quickList.contains(".heightIn(max = 360.dp)\n            .verticalScroll"))
    }

    @Test
    fun compactNativeRowAcceptsOnlyTheCompleteMeasuredText() {
        // alpha11: 248dp menu minus 2*(8+12)dp; 40dp row minus 2*(1.5+6.5)dp.
        assertTrue(modelPickerMenuRowFits(208, 24, 208, 24, false))
        assertFalse("One pixel of fallback-font overflow matters", modelPickerMenuRowFits(208, 25, 208, 24, false))
        assertFalse("Never clip a second line", modelPickerMenuRowFits(208, 40, 208, 24, false))
        assertFalse("Large fonts must use the wrapping sheet", modelPickerMenuRowFits(200, 48, 208, 24, false))
        assertFalse("A longer unbroken name must remain readable", modelPickerMenuRowFits(209, 20, 208, 24, false))
        assertFalse("A layout overflow flag cannot be ignored", modelPickerMenuRowFits(200, 20, 208, 24, true))
        assertFalse(modelPickerMenuRowFits(0, 20, 0, 24, false))
        assertFalse(modelPickerMenuRowFits(20, 0, 208, 0, false))
    }

    @Test
    fun nativeFontMetricsReplaceFixedLineHeightWithoutChangingThemeTypeface() {
        // Both library typography and host overrides may carry fixed line height/no font padding.
        val base = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
        val actual = modelPickerTextStyle(base)
        assertEquals(TextUnit.Unspecified, actual.lineHeight)
        assertEquals(true, actual.platformStyle?.paragraphStyle?.includeFontPadding)
        assertEquals(base.fontSize, actual.fontSize)
        assertEquals(base.fontFamily, actual.fontFamily)
        assertEquals(base.fontWeight, actual.fontWeight)
        assertEquals(base.letterSpacing, actual.letterSpacing)
    }

    @Test
    fun fixedNativeRowBudgetMustBeReauditedWhenCouiIsUpgraded() {
        // These dimensions are private to the library, including slide-selection hit testing.
        // An upgrade must re-audit them instead of silently restoring the old clipping bug.
        val versions = listOf(File("gradle/libs.versions.toml"), File("../gradle/libs.versions.toml"))
            .first { it.isFile }.readText()
        assertTrue("Re-audit COUI Menu.kt row/content insets before updating this guard",
            versions.lineSequence().any { it.trim() == "coui = \"0.0.1-alpha11\"" })
    }

    @Test
    fun searchAndDeferredConfirmationAreNotRemovedByTheLayoutFix() {
        val text = source()
        assertTrue(text.contains("state.filterGroups(query)"))
        assertTrue(text.contains("val select = rememberConfirmedSelection(onModelSelected)"))
        assertTrue(text.contains("delay(SelectionConfirmMs)"))
        assertTrue(text.contains("private const val SelectionConfirmMs = 140L"))
    }
}
