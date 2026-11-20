package io.github.mangi.eta.ui.components.custom;

import androidx.compose.foundation.text.selection.Selectable;
import androidx.compose.foundation.text.selection.Selection;
import androidx.compose.foundation.text.selection.SelectionManager;
import androidx.compose.foundation.text.selection.SelectionState;
import androidx.compose.ui.text.TextLayoutResult;
import androidx.compose.ui.text.style.ResolvedTextDirection;

/**
 * Foundation 1.13.0-alpha02 compatibility boundary. The public Kotlin select(TextRange)
 * API cannot address the start of a later selectable: its global lookup is left-biased.
 * These are direct calls to exported public JVM methods of Kotlin-internal classes, NOT
 * stable public Kotlin API. No reflection/private fields/synthetic accessors are used.
 * Recheck this adapter and the registry tests when changing the Foundation version.
 */
public final class Foundation113SelectionAnchors {
    private Foundation113SelectionAnchors() {}

    /** Called synchronously after public select seeded a range strictly inside the target node. */
    public static boolean anchorSeed(SelectionState state, String expectedText, int start, int end) {
        SelectionManager manager = state.getManager$foundation();
        if (manager == null) return false;
        Selection seed = manager.getSelection();
        if (seed == null || seed.getHandlesCrossed()
                || seed.getStart().getSelectableId() != seed.getEnd().getSelectableId()) return false;
        Selectable target = manager.getAnchorSelectable$foundation(seed.getStart());
        if (target == null || !expectedText.equals(target.getText().getText())
                || start < 0 || end <= start || end > expectedText.length()) return false;
        TextLayoutResult layout = target.textLayoutResult();
        ResolvedTextDirection startDirection = layout == null ? seed.getStart().getDirection()
                : layout.getBidiRunDirection(start);
        ResolvedTextDirection endDirection = layout == null ? seed.getEnd().getDirection()
                : layout.getBidiRunDirection(end - 1);
        long id = target.getSelectableId();
        Selection exact = new Selection(new Selection.AnchorInfo(startDirection, start, id),
                new Selection.AnchorInfo(endDirection, end, id), false);
        // Same update sequence as SelectionManager.setSelection(Selection), without repeating focus:
        // public select already acquired focus and enabled the native handles/toolbar this turn.
        manager.updateSubSelections$foundation(exact);
        manager.getOnSelectionChange().invoke(exact);
        manager.setPreviousSelectionLayout$foundation(null);
        return true;
    }

    /** Read-only anchors for contract tests; does not expose an application-global selection hook. */
    public static long[] anchors(SelectionState state) {
        SelectionManager manager = state.getManager$foundation();
        Selection s = manager == null ? null : manager.getSelection();
        return s == null ? new long[0] : new long[]{s.getStart().getSelectableId(), s.getStart().getOffset(),
                s.getEnd().getSelectableId(), s.getEnd().getOffset()};
    }
}
