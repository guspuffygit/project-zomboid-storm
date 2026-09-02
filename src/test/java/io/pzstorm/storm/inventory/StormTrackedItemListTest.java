package io.pzstorm.storm.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import org.junit.jupiter.api.Test;

/** Every content mutation path of {@link StormTrackedItemList} must report exactly once. */
class StormTrackedItemListTest implements UnitTest {

    private static final class Counting extends StormTrackedItemList<String> {
        int mutations;

        Counting(List<String> initial) {
            super("container", initial);
        }

        @Override
        protected void onMutate() {
            mutations++;
        }
    }

    @Test
    void constructionCopiesWithoutReporting() {
        Counting list = new Counting(Arrays.asList("a", "b"));
        assertEquals(Arrays.asList("a", "b"), list);
        assertEquals(0, list.mutations);
        assertEquals("container", list.getContainer());
    }

    @Test
    void directMutatorsReportOnce() {
        Counting list = new Counting(new ArrayList<>());
        list.add("a"); // [a]
        list.add(0, "b"); // [b a]
        list.addAll(Arrays.asList("c", "d")); // [b a c d]
        list.addAll(1, Arrays.asList("e")); // [b e a c d]
        list.set(0, "z"); // [z e a c d]
        list.remove(0); // [e a c d]
        list.remove("e"); // [a c d]
        list.removeAll(Arrays.asList("c")); // [a d]
        list.retainAll(Arrays.asList("a")); // [a]
        list.replaceAll(String::toUpperCase); // [A]
        list.add("b"); // [A b]
        list.removeIf("A"::equals); // [b]
        list.clear(); // []
        assertEquals(13, list.mutations);
        assertTrue(list.isEmpty());
    }

    @Test
    void noOpMutatorsDoNotReport() {
        Counting list = new Counting(Arrays.asList("a"));
        assertFalse(list.remove("x"));
        assertFalse(list.addAll(new ArrayList<>()));
        assertFalse(list.removeAll(Arrays.asList("x")));
        assertFalse(list.retainAll(Arrays.asList("a")));
        assertFalse(list.removeIf("x"::equals));
        assertEquals(0, list.mutations);
        list.clear();
        assertEquals(1, list.mutations);
        list.clear();
        assertEquals(1, list.mutations, "clearing an empty list is not a mutation");
    }

    @Test
    void iteratorAndSubListPathsReport() {
        Counting list = new Counting(Arrays.asList("a", "b", "c", "d"));
        Iterator<String> it = list.iterator();
        it.next();
        it.remove();
        assertEquals(1, list.mutations);
        ListIterator<String> li = list.listIterator();
        li.next();
        li.set("x");
        li.add("y");
        assertEquals(3, list.mutations);
        list.subList(0, 2).clear();
        assertEquals(4, list.mutations);
        assertEquals(Arrays.asList("c", "d"), list);
    }
}
