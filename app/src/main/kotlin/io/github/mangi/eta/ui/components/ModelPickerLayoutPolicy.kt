package io.github.mangi.eta.ui.components

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit

/** A native COUI menu owns fixed-height rows and its slide-selection hit testing assumes those
 * heights. Only use that host when the *complete, unconstrained-height* text fits its content box.
 * Increasing the outer popup height or drawing outside the text box cannot fix a clipped row. */
internal fun modelPickerMenuRowFits(
    measuredWidthPx: Int,
    measuredHeightPx: Int,
    contentWidthPx: Int,
    contentHeightPx: Int,
    hasVisualOverflow: Boolean,
): Boolean = contentWidthPx > 0 && contentHeightPx > 0 &&
    measuredWidthPx <= contentWidthPx && measuredHeightPx <= contentHeightPx && !hasVisualOverflow

/** Keep the theme's typeface/weight/size but let Android measure the actual font (including
 * fallback glyphs). A fixed sp line height or trimmed font padding is unsafe inside clipped menus. */
internal fun modelPickerTextStyle(base: TextStyle): TextStyle = base.copy(
    lineHeight = TextUnit.Unspecified,
    platformStyle = PlatformTextStyle(includeFontPadding = true),
)
