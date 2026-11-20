package io.github.mangi.eta.ui.components

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts complement (not replace) device Compose/layout verification. */
class ReadOnlyDialogContractTest {
    private fun source(relative: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/kotlin").isDirectory }
        return File(root, "app/src/main/kotlin/io/github/mangi/eta/$relative").readText()
    }

    @Test fun patrolReadOnlyViewerHasOneExplicitFooterAndCopiesUnmodifiedText() {
        val text = source("ui/screens/selfcheck/DiagnosticsPatrolScreen.kt")
            .substringAfter("val name = viewingName")
        assertTrue(text.contains("EtaReadOnlyTextDialog("))
        assertFalse(text.contains("400.dp"))
        assertFalse(text.contains("MiuixDialogActions("))
        assertTrue(text.contains("copyText(context, viewingText)"))
        assertTrue(text.contains("onDismissRequest = { viewingName = null }"))
    }

    @Test fun selectionKeepsSegmentToolsButMovesFullCopyAndCloseOutsideBody() {
        val text = source("ui/components/SelectionCopyDialog.kt")
        assertTrue(text.contains("EtaReadOnlyDialog("))
        assertTrue(text.contains("onCopyAll = { copy(0, text) }"))
        assertFalse(text.contains("460.dp"))
        assertFalse(text.contains("verticalScroll(")) // the shared body is the sole scroll owner
        assertTrue(text.contains("copy(index + 1, segment)"))
        assertTrue(text.contains("Icons.Rounded.ContentCopy"))
        assertTrue(text.contains("SelectionContainer"))
    }

    @Test fun readOnlyShellBypassesLegacyReverseMeasurementAndExtraClose() {
        val text = source("ui/components/PorUiCompatibility.kt")
        val readOnly = text.substringAfter("internal fun EtaReadOnlyDialog(", "")
            .substringBefore("internal fun EtaReadOnlyTextDialog(")
        assertTrue("Dedicated read-only shell missing", readOnly.isNotEmpty())
        assertFalse(readOnly.contains("PorDialogContent("))
        assertTrue(readOnly.contains("dismissAction = CouiDialogAction("))
        assertTrue(readOnly.contains("onClick = onDismissRequest"))
        assertTrue(readOnly.contains("onClick = onCopyAll"))
        assertTrue(readOnly.contains("ReadOnlyDialogContent("))
        assertTrue(readOnly.contains("rememberCouiDialogMaterial()"))
        assertTrue(readOnly.contains("rememberCouiWindowBackdrop()"))
    }

    @Test fun readOnlyLayoutKeepsIndependentBodyAndFooterScrollWithActualThemedButtonMetrics() {
        val text = source("ui/components/ReadOnlyDialogLayout.kt")
        assertEquals(2, Regex("verticalScroll\\(").findAll(text).count())
        assertTrue(text.contains("maxIntrinsicWidth(Constraints.Infinity)"))
        assertTrue(text.contains("readOnlyBodyMaxHeight("))
        assertFalse(text.contains("reversed()"))
        assertFalse(text.contains("heightIn("))
        assertFalse(text.contains("fontScale ="))
        assertFalse(text.contains("fontSize ="))
        assertFalse(text.contains("maxLines ="))
    }

    @Test fun stackedFooterMeasuresNaturalActionsInsideBoundedScrollViewport() {
        val actions = source("ui/components/ReadOnlyDialogLayout.kt")
            .substringAfter("private fun ReadOnlyDialogActions(")
        assertTrue(actions.contains("Modifier.fillMaxWidth().verticalScroll(rememberScrollState())"))
        assertTrue(actions.contains("maxHeight = Constraints.Infinity"))
        assertTrue(actions.contains("layout(constraints.maxWidth, height)"))
        assertFalse(actions.contains("constrainHeight(height)"))
        assertFalse(actions.contains("maxHeight = constraints.maxHeight"))
    }

    @Test fun textWrapperCopiesExactDocumentAndKeepsNativeDismissal() {
        val text = source("ui/components/PorUiCompatibility.kt")
            .substringAfter("internal fun EtaReadOnlyTextDialog(")
        assertTrue(text.contains("clipboard.setText(AnnotatedString(text))"))
        assertTrue(text.contains("SelectionContainer"))
        assertTrue(text.contains("onDismissRequest = onDismissRequest"))
        assertFalse(text.contains(".trim("))
        assertFalse(text.contains(".take("))
        assertFalse(text.contains(".substring("))
    }

    @Test fun editingDialogsStillHaveBothCancelAndConfirmActions() {
        val text = source("ui/screens/selfcheck/DiagnosticsPatrolScreen.kt")
            .substringBefore("val name = viewingName")
        assertEquals(2, Regex("MiuixDialogActions\\(").findAll(text).count())
        assertTrue(text.contains("onCancel = { showIntervalDialog = false }"))
        assertTrue(text.contains("onCancel = { showKeepDialog = false }"))
        val compatibility = source("ui/components/PorUiCompatibility.kt")
            .substringAfter("internal fun EtaWindowDialog(")
            .substringBefore("internal fun EtaOverlayDialog(")
        assertTrue(compatibility.contains("text = stringResource(R.string.action_close)"))
        assertTrue(compatibility.contains("enabled = onDismissRequest != null"))
    }
}
