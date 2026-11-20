package io.github.mangi.eta.ui.components.custom;

/** Mirrors Foundation 1.13 SelectionRegistrar's row/column order, including baseline-height differences. */
public final class MessageSelectionLayoutOrder {
    private MessageSelectionLayoutOrder() {}
    public static int compare(float ax, float ay, float aw, float ah, float bx, float by, float bw, float bh) {
        float vertical = Math.max(0, Math.min(ay + ah, by + bh) - Math.max(ay, by));
        float horizontal = Math.max(0, Math.min(ax + aw, bx + bw) - Math.max(ax, bx));
        boolean sameRow = (vertical >= ah * .5f || vertical >= bh * .5f)
                && horizontal < aw * .5f && horizontal < bw * .5f;
        return Float.compare(sameRow ? ax : ay, sameRow ? bx : by);
    }
}
