package io.github.mangi.eta.ui.model

/** Incrementally estimates generated tokens from append-only streaming text without rescanning history. */
internal class StreamingTokenEstimateTracker {
    private var messageId: String? = null
    private var scannedLength = 0
    private var cjkCharacters = 0
    private var otherCharacters = 0

    fun update(activeMessageId: String?, text: String?): Int {
        if (activeMessageId == null || text == null) {
            reset()
            return 0
        }
        if (messageId != activeMessageId || text.length < scannedLength) {
            messageId = activeMessageId
            scannedLength = 0
            cjkCharacters = 0
            otherCharacters = 0
        }
        for (index in scannedLength until text.length) {
            if (text[index].code >= 0x2E80) cjkCharacters++ else otherCharacters++
        }
        scannedLength = text.length
        return cjkCharacters + (otherCharacters + 3) / 4
    }

    private fun reset() {
        messageId = null
        scannedLength = 0
        cjkCharacters = 0
        otherCharacters = 0
    }
}
