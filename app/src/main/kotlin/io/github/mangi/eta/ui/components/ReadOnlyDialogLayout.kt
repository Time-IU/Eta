package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults

/** Explicit slots, not reverse-measuring arbitrary children from legacy dialogs. */
@Composable
internal fun ReadOnlyDialogContent(
    closeText: String,
    copyAllText: String,
    onDismissRequest: () -> Unit,
    onCopyAll: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            // The document scrolls independently of the fixed footer viewport.
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            ReadOnlyDialogActions(closeText, copyAllText, onDismissRequest, onCopyAll)
        },
    ) { slots, constraints ->
        check(constraints.hasBoundedHeight && constraints.hasBoundedWidth)
        val loose = constraints.copy(minHeight = 0)
        // These are two named roles, independent of how many children the document emits.
        // The footer's actual themed height (including wrapping/system font scale) is reserved.
        val footer = slots[1].measure(loose)
        val gap = 16.dp.roundToPx().coerceAtMost(
            readOnlyBodyMaxHeight(constraints.maxHeight, footer.height, 0),
        )
        val body = slots[0].measure(loose.copy(maxHeight = readOnlyBodyMaxHeight(
            constraints.maxHeight, footer.height, gap,
        )))
        val height = constraints.constrainHeight(body.height + gap + footer.height)
        layout(constraints.maxWidth, height) {
            body.placeRelative(0, 0)
            footer.placeRelative(0, body.height + gap)
        }
    }
}

/** Use intrinsic widths of the REAL themed buttons; no guessed character count/font reduction. */
@Composable
private fun ReadOnlyDialogActions(
    closeText: String,
    copyAllText: String,
    onDismissRequest: () -> Unit,
    onCopyAll: () -> Unit,
) {
    Layout(
        // verticalScroll bounds the footer viewport but measures the complete action
        // layout with unbounded height. Normal footers remain fixed (scroll range 0);
        // exceptionally short windows can scroll to either full-size action instead
        // of placing the second action outside a height-constrained parent.
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        content = {
            EtaTextButton(text = closeText, onClick = onDismissRequest)
            EtaTextButton(
                text = copyAllText,
                onClick = onCopyAll,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        },
    ) { buttons, constraints ->
        val rowGap = 20.dp.roundToPx()
        val stacked = stackReadOnlyActions(
            availableWidth = constraints.maxWidth,
            buttonWidths = buttons.map { it.maxIntrinsicWidth(Constraints.Infinity) },
            gap = rowGap,
        )
        val gap = if (stacked) 8.dp.roundToPx() else rowGap
        val width = if (stacked) constraints.maxWidth else (constraints.maxWidth - gap) / 2
        val measured = buttons.map {
            it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = Constraints.Infinity))
        }
        val height = if (stacked) measured.sumOf { it.height } + gap else measured.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            measured[0].placeRelative(0, 0)
            measured[1].placeRelative(
                if (stacked) 0 else width + gap,
                if (stacked) measured[0].height + gap else 0,
            )
        }
    }
}
