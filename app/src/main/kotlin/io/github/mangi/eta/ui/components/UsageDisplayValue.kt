package io.github.mangi.eta.ui.components

import java.text.NumberFormat
import java.util.Locale

/** Presentation only. Never recomputes or changes a token or accounting scope. */
internal fun usageDisplayValue(raw: String?, compact: Boolean = false, locale: Locale = Locale.getDefault()): String? {
    if (raw == null) return null
    val tokens = Regex("^([0-9]+) tok$").matchEntire(raw)?.groupValues?.get(1)?.toLongOrNull()
    if (tokens != null) {
        if (compact && tokens >= 1000) {
            val divisor = if (tokens >= 1000000) 1000000.0 else 1000.0
            val suffix = if (tokens >= 1000000) "M" else "K"
            val f = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }
            return f.format(tokens / divisor) + suffix
        }
        return NumberFormat.getIntegerInstance(locale).format(tokens) + " tok"
    }
    val ms = Regex("^([0-9]+) ms$").matchEntire(raw)?.groupValues?.get(1)?.toLongOrNull()
    if (ms != null) {
        if (ms < 1000) return "$ms ms"
        val sec = ms / 1000.0
        val f = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
        if (sec < 60) return f.format(sec) + " s"
        val mins = ms / 60000
        val remainder = ms / 1000 % 60
        return "$mins min $remainder s"
    }
    return raw
}
