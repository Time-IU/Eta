package io.github.mangi.eta.ui.components.custom;
import org.junit.Test;
import static org.junit.Assert.*;
public class MessageSelectionLayoutOrderTest {
    @Test public void sideBySideWithDifferentBaselineUsesX() {
        assertTrue(MessageSelectionLayoutOrder.compare(0, 8, 20, 30, 24, 0, 100, 50) < 0);
    }
    @Test public void sequentialParagraphUsesY() {
        assertTrue(MessageSelectionLayoutOrder.compare(40, 0, 80, 20, 0, 24, 80, 20) < 0);
    }
    @Test public void tableColumnsUseX() {
        assertTrue(MessageSelectionLayoutOrder.compare(0, 10, 50, 60, 60, 0, 50, 120) < 0);
    }
    @Test public void overlappingColumnDoesNotBecomeSameRow() {
        assertTrue(MessageSelectionLayoutOrder.compare(20, 20, 100, 40, 0, 0, 100, 100) > 0);
    }
    @Test public void identicalGeometryIsStable() {
        assertEquals(0, MessageSelectionLayoutOrder.compare(0, 0, 100, 20, 0, 0, 100, 20));
    }
}
