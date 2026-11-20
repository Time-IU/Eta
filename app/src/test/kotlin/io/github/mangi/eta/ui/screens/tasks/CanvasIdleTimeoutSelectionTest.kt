package io.github.mangi.eta.ui.screens.tasks

import io.github.mangi.eta.data.model.VirtualScreenIdleTimeout
import org.junit.Assert.*
import org.junit.Test

class CanvasIdleTimeoutSelectionTest {
    @Test fun canonicalOptionsStayInRuntimeOrder() {
        assertEquals(listOf(5, 10, 20, 60, 0), VirtualScreenIdleTimeout.options)
        VirtualScreenIdleTimeout.options.forEach { current ->
            assertEquals(VirtualScreenIdleTimeout.options, CanvasIdleTimeoutSelection(current).minutes)
        }
    }

    @Test fun everyCurrentValueDisplaysWithoutNormalization() {
        VirtualScreenIdleTimeout.options.forEach { current ->
            val selection = CanvasIdleTimeoutSelection(current)
            assertEquals(current, selection.minutes[selection.selectedIndex])
            assertNull(selection.changedMinutes(selection.selectedIndex, enabled = true))
        }
    }

    @Test fun everyChangedSelectionMapsToActualMinutes() {
        VirtualScreenIdleTimeout.options.forEach { current ->
            val selection = CanvasIdleTimeoutSelection(current)
            VirtualScreenIdleTimeout.options.forEachIndexed { index, target ->
                assertEquals(if (target == current) null else target,
                    selection.changedMinutes(index, enabled = true))
            }
        }
    }

    @Test fun neverIsZeroNotIndexOrFallback() {
        val selection = CanvasIdleTimeoutSelection(5)
        assertEquals(0, selection.changedMinutes(selection.minutes.indexOf(0), enabled = true))
    }

    @Test fun disabledGateRejectsEverySelection() {
        val selection = CanvasIdleTimeoutSelection(20)
        selection.minutes.indices.forEach { assertNull(selection.changedMinutes(it, enabled = false)) }
    }

    @Test fun invalidIndicesDoNotWrite() {
        val selection = CanvasIdleTimeoutSelection(10)
        listOf(-1, selection.minutes.size, Int.MAX_VALUE).forEach {
            assertNull(selection.changedMinutes(it, enabled = true))
        }
    }

    @Test fun unfamiliarSavedValueIsNotSilentlyCoercedOrPersisted() {
        val selection = CanvasIdleTimeoutSelection(17)
        assertEquals(17, selection.minutes[selection.selectedIndex])
        assertEquals(VirtualScreenIdleTimeout.options, selection.minutes.dropLast(1))
        assertNull(selection.changedMinutes(selection.selectedIndex, enabled = true))
        assertEquals(60, selection.changedMinutes(selection.minutes.indexOf(60), enabled = true))
    }
}
