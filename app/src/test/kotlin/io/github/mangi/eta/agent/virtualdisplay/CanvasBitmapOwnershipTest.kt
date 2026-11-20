package io.github.mangi.eta.agent.virtualdisplay

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Needs Robolectric Android bitmap implementation; pure JVM policy tests are separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CanvasBitmapOwnershipTest {
    @Test fun callerRecycleDoesNotRecycleUiOwnedBitmap() {
        val uiFrame = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        uiFrame.eraseColor(0xff123456.toInt())
        val caller = requireNotNull(CanvasBitmapOwnership.copyForCaller(uiFrame))
        assertNotSame(uiFrame, caller)
        assertEquals(uiFrame.getPixel(0, 0), caller.getPixel(0, 0))
        caller.recycle()
        assertFalse(uiFrame.isRecycled)
        assertEquals(0xff123456.toInt(), uiFrame.getPixel(0, 0))
        uiFrame.recycle()
    }
    @Test fun recycledSourceIsRejected() {
        val source = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        source.recycle()
        assertNull(CanvasBitmapOwnership.copyForCaller(source))
    }
}
