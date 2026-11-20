package io.github.mangi.eta.agent.virtualdisplay

import android.graphics.Bitmap

/** Only this independent copy may escape to recycle-owning screenshot consumers. */
internal object CanvasBitmapOwnership {
    fun copyForCaller(source: Bitmap): Bitmap? =
        if (source.isRecycled) null else source.copy(Bitmap.Config.ARGB_8888, false)
}
