package io.github.mangi.eta.ui.components

/** Pure pixel arithmetic shared with the explicit read-only body/footer layout. */
internal fun readOnlyBodyMaxHeight(availableHeight: Int, footerHeight: Int, gap: Int): Int {
    require(availableHeight >= 0 && footerHeight >= 0 && gap >= 0)
    return (availableHeight.toLong() - footerHeight - gap).coerceAtLeast(0).toInt()
}

/** Widths include the themed button's actual text, padding and minimum touch size. */
internal fun stackReadOnlyActions(availableWidth: Int, buttonWidths: List<Int>, gap: Int): Boolean {
    require(availableWidth >= 0 && gap >= 0 && buttonWidths.size == 2 && buttonWidths.all { it >= 0 })
    // Equal-width columns must both accommodate the widest label, including odd-pixel splits.
    return buttonWidths.max().toLong() * 2 + gap > availableWidth
}
