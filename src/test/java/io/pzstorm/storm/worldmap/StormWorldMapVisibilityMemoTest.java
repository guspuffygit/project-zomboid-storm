package io.pzstorm.storm.worldmap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo.Member;
import io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo.Tables;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import zombie.characters.Faction;

class StormWorldMapVisibilityMemoTest {

    private static Faction faction(String owner, String... players) {
        Faction f = new Faction();
        f.setOwner(owner);
        f.getPlayers().addAll(Arrays.asList(players));
        return f;
    }

    @Test
    void factionResolvesToFirstMatchInListOrder() {
        Faction first = faction("alice", "bob");
        Faction second = faction("carol", "bob", "dave");
        Tables tables = new Tables();
        tables.indexFaction(first);
        tables.indexFaction(second);
        assertSame(first, tables.get("alice").faction);
        assertSame(first, tables.get("bob").faction);
        assertSame(second, tables.get("carol").faction);
        assertSame(second, tables.get("dave").faction);
        assertNull(tables.get("erin"));
        assertNull(tables.get(null));
    }

    @Test
    void sameFactionRequiresIdenticalNonNullFaction() {
        Tables tables = new Tables();
        tables.indexFaction(faction("alice", "bob"));
        tables.indexFaction(faction("carol"));
        tables.indexSafehouse(0, "erin", new ArrayList<>());
        assertTrue(Tables.sameFaction(tables.get("alice"), tables.get("bob")));
        assertFalse(Tables.sameFaction(tables.get("alice"), tables.get("carol")));
        assertFalse(Tables.sameFaction(tables.get("erin"), tables.get("erin")));
        assertFalse(Tables.sameFaction(null, tables.get("alice")));
        assertFalse(Tables.sameFaction(tables.get("alice"), null));
    }

    @Test
    void safehouseMembershipIsOwnerOrListedPlayer() {
        Tables tables = new Tables();
        tables.indexSafehouse(0, "alice", list("bob"));
        tables.indexSafehouse(1, "carol", list("dave", "bob"));
        tables.indexSafehouse(2, "erin", list());
        assertTrue(Tables.shareSafehouse(tables.get("alice"), tables.get("bob")));
        assertTrue(Tables.shareSafehouse(tables.get("bob"), tables.get("dave")));
        assertFalse(Tables.shareSafehouse(tables.get("alice"), tables.get("dave")));
        assertFalse(Tables.shareSafehouse(tables.get("erin"), tables.get("alice")));
        assertFalse(Tables.shareSafehouse(tables.get("alice"), null));
        assertFalse(Tables.shareSafehouse(null, tables.get("alice")));
        assertTrue(Tables.shareSafehouse(tables.get("alice"), tables.get("alice")));
    }

    @Test
    void memberSafehouseListGrows() {
        Member member = new Member();
        for (int i = 0; i < 40; i++) {
            member.addSafehouse(i);
        }
        Tables tables = new Tables();
        for (int i = 0; i < 40; i++) {
            tables.indexSafehouse(i, "alice", list("bob" + i));
        }
        assertTrue(Tables.shareSafehouse(tables.get("alice"), tables.get("bob39")));
        assertFalse(Tables.shareSafehouse(tables.get("bob3"), tables.get("bob39")));
    }

    @Test
    void nullNamesAreIgnored() {
        Tables tables = new Tables();
        tables.indexFaction(faction(null, "bob"));
        tables.indexSafehouse(0, null, list((String) null, "bob"));
        assertNull(tables.get(null));
        assertSame(1, tables.size());
        assertTrue(Tables.shareSafehouse(tables.get("bob"), tables.get("bob")));
    }

    @Test
    void evaluateOutsideBatchDefersToVanilla() {
        StormWorldMapVisibilityMemo.resetForTest();
        assertFalse(StormWorldMapVisibilityMemo.isActiveForTest());
        assertSame(-1, StormWorldMapVisibilityMemo.evaluate(null, null));
    }

    private static List<String> list(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }
}
