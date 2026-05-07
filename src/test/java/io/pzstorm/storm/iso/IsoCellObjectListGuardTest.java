package io.pzstorm.storm.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IsoCellObjectListGuardTest {

    private static Set<String> populated() {
        Set<String> set = new HashSet<>();
        set.add("a");
        set.add("b");
        return set;
    }

    @Test
    @DisplayName("guard returns the original set on the client (regardless of safeToAdd)")
    void guard_clientReturnsOriginal() {
        Set<String> set = populated();
        assertSame(set, IsoCellObjectListGuard.guard(false, false, set));
        assertSame(set, IsoCellObjectListGuard.guard(false, true, set));
    }

    @Test
    @DisplayName("guard returns the original set on the server outside phase 4 (safeToAdd=true)")
    void guard_serverOutsidePhase4ReturnsOriginal() {
        Set<String> set = populated();
        Set<String> guarded = IsoCellObjectListGuard.guard(true, true, set);
        assertSame(set, guarded);
        guarded.add("c");
        assertEquals(3, guarded.size(), "set must remain mutable outside phase 4");
    }

    @Test
    @DisplayName(
            "guard returns an unmodifiable view on the server during phase 4 (safeToAdd=false)")
    void guard_serverPhase4ReturnsUnmodifiable() {
        Set<String> set = populated();
        Set<String> guarded = IsoCellObjectListGuard.guard(true, false, set);
        assertThrows(UnsupportedOperationException.class, () -> guarded.add("c"));
        assertThrows(UnsupportedOperationException.class, () -> guarded.remove("a"));
        assertThrows(UnsupportedOperationException.class, guarded::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> guarded.removeIf(s -> true),
                "removeIf must also be blocked");
    }

    @Test
    @DisplayName("guarded view blocks Iterator.remove() during phase 4")
    void guard_iteratorRemoveBlocked() {
        Set<String> set = populated();
        Set<String> guarded = IsoCellObjectListGuard.guard(true, false, set);
        Iterator<String> it = guarded.iterator();
        it.next();
        assertThrows(UnsupportedOperationException.class, it::remove);
    }

    @Test
    @DisplayName("guarded view still supports read-only iteration and lookup")
    void guard_readOperationsStillWork() {
        Set<String> set = populated();
        Set<String> guarded = IsoCellObjectListGuard.guard(true, false, set);

        int seen = 0;
        for (String s : guarded) {
            seen++;
        }
        assertEquals(2, seen, "iteration must still work");
        assertEquals(2, guarded.size());
    }

    @Test
    @DisplayName("guard reflects later mutations of the underlying set (it's a view, not a copy)")
    void guard_isLiveView() {
        Set<String> set = populated();
        Set<String> guarded = IsoCellObjectListGuard.guard(true, false, set);
        assertEquals(2, guarded.size());
        set.add("c");
        assertEquals(
                3,
                guarded.size(),
                "guarded view must reflect updates to the backing set so the unmodifiable wrap"
                        + " doesn't snapshot");
    }
}
