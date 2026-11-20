package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.os.Build

internal object LiveUpdateContract {
    fun request(builder: Notification.Builder, shortText: String, enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 36 && enabled) {
            builder.setShortCriticalText(shortText).setRequestPromotedOngoing(true)
            // New uiRichOngoing platforms reject colorized payloads; older releases require
            // them. Ask this framework about the real completed payload, not an SDK guess.
            val colorized = liveUpdateRequiresColorization { candidate ->
                builder.setColorized(candidate).build().hasPromotableCharacteristics()
            }
            builder.setColorized(colorized)
        }
    }
}
