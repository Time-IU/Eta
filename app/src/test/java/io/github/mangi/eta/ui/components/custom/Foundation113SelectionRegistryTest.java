package io.github.mangi.eta.ui.components.custom;

import java.lang.reflect.Proxy;
import java.util.*;
import androidx.compose.foundation.text.selection.*;
import androidx.compose.ui.layout.LayoutCoordinates;
import androidx.compose.ui.text.AnnotatedString;
import androidx.compose.ui.text.style.ResolvedTextDirection;
import kotlin.Unit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real dependency registry/manager/extraction. Proxies implement ONLY public interfaces as headless fixtures. */
public class Foundation113SelectionRegistryTest {
    private static Selection.AnchorInfo anchor(long id, int offset) {
        return new Selection.AnchorInfo(ResolvedTextDirection.Ltr, offset, id);
    }
    private static Selectable selectable(long id, String text) {
        AnnotatedString annotated = new AnnotatedString(text, Collections.emptyList(), Collections.emptyList());
        return (Selectable) Proxy.newProxyInstance(Selectable.class.getClassLoader(), new Class<?>[]{Selectable.class}, (p,m,args) -> {
            switch(m.getName()) {
                case "getSelectableId": return id;
                case "getText": return annotated;
                case "getSelectAllSelection": return new Selection(anchor(id,0), anchor(id,text.length()),false);
                case "getLayoutCoordinates": case "textLayoutResult": case "getPinnableContainer": case "getBringIntoViewRequester": return null;
                case "hashCode": return Long.hashCode(id);
                case "equals": return p == args[0];
                case "toString": return "headless-selectable-"+id;
                default: throw new AssertionError("Unexpected fixture method: "+m.getName());
            }
        });
    }
    private static class Fixture {
        final SelectionRegistrarImpl registry = new SelectionRegistrarImpl();
        final SelectionManager manager = new SelectionManager(registry);
        final SelectionState state = new SelectionState();
        final List<String> texts;
        Fixture(String... values) {
            texts = Arrays.asList(values);
            for(int i=0;i<values.length;i++) registry.subscribe(selectable(i+1, values[i]));
            // Geometry order is separately covered by layout and Compose tests. Real registry cache
            // accepts these explicitly ordered fixtures, so native TextLayout is not needed here.
            registry.setSorted$foundation(true);
            LayoutCoordinates coordinates = (LayoutCoordinates) Proxy.newProxyInstance(LayoutCoordinates.class.getClassLoader(), new Class<?>[]{LayoutCoordinates.class}, (p,m,args) -> {
                if(m.getName().equals("isAttached")) return true;
                throw new AssertionError("Unexpected coordinates method: "+m.getName());
            });
            manager.setContainerLayoutCoordinates(coordinates);
            state.setManager$foundation(manager);
            manager.setOnSelectionChange(selection -> {
                manager.setSelection(selection);
                state.setSelection$foundation(selection);
                state.updateSelectedTexts$foundation(manager.getSelectedTexts$foundation());
                return Unit.INSTANCE;
            });
        }
        void seedAndFix(int index, int end) {
            long id=index+1;
            // This is the same intra-node [1,end] seed public global select resolves to.
            // Public select's actual layout/focus preconditions are covered by Compose scenes.
            manager.setSelection(new Selection(anchor(id,1),anchor(id,end),false));
            assertTrue(Foundation113SelectionAnchors.anchorSeed(state,texts.get(index),0,end));
            assertArrayEquals(new long[]{id,0,id,end},Foundation113SelectionAnchors.anchors(state));
            assertEquals(Collections.singletonList(texts.get(index).substring(0,end)),
                state.getSelectedTexts().stream().map(AnnotatedString::getText).toList());
            assertEquals(1,registry.getSubselections().getSize());
            assertFalse(registry.getSubselections().containsKey(id-1));
            Selection sub=registry.getSubselections().get(id);
            assertEquals(0,sub.getStart().getOffset()); assertEquals(end,sub.getEnd().getOffset());
            assertEquals(id,sub.getStart().getSelectableId()); assertEquals(id,sub.getEnd().getSelectableId());
            System.out.println("registry anchors="+Arrays.toString(Foundation113SelectionAnchors.anchors(state))
                    +" selectedTexts="+state.getSelectedTexts().stream().map(AnnotatedString::getText).toList()
                    +" subSelectionCount="+registry.getSubselections().getSize());
        }
    }
    @Test public void secondParagraphFirstSentenceHasTargetAnchorsAndNoLeadingEmpty() {
        new Fixture("前段。","目标句！后句。").seedAndFix(1,4);
    }
    @Test public void orderedListFirstSentenceExcludesMarkerSelectable() {
        new Fixture("前段。","1.","目标句！后句。").seedAndFix(2,4);
    }
    @Test public void firstSentenceAfterCodeExcludesLanguageAndCodeNodes() {
        new Fixture("text","someCode()\n","目标句！后句。").seedAndFix(2,4);
    }
    @Test public void duplicateTextUsesSeedSelectableIdentity() {
        new Fixture("目标句！后句。","目标句！后句。").seedAndFix(1,4);
    }
    @Test public void oneCharacterSentenceCanUseCollapsedInteriorSeed() {
        new Fixture("前段。","好").seedAndFix(1,1);
    }
    @Test public void noTrimOfRealWhitespace() {
        new Fixture("前段。","  好  ").seedAndFix(1,5);
    }
    @Test public void mismatchedOrCrossNodeSeedIsRejected() {
        Fixture f=new Fixture("前段。","目标句！");
        f.manager.setSelection(new Selection(anchor(1,2),anchor(2,4),false));
        assertFalse(Foundation113SelectionAnchors.anchorSeed(f.state,"目标句！",0,4));
        f.manager.setSelection(new Selection(anchor(2,1),anchor(2,4),false));
        assertFalse(Foundation113SelectionAnchors.anchorSeed(f.state,"changed!",0,4));
    }
    @Test public void originalBugRetainedAsNegativeControl() {
        Fixture f=new Fixture("前段。","目标句！后句。");
        Selection bad=new Selection(anchor(1,3),anchor(2,4),false);
        f.manager.updateSubSelections$foundation(bad);f.manager.getOnSelectionChange().invoke(bad);
        assertEquals(Arrays.asList("","目标句！"),f.state.getSelectedTexts().stream().map(AnnotatedString::getText).toList());
        assertArrayEquals(new long[]{1,3,2,4},Foundation113SelectionAnchors.anchors(f.state));
    }
}
