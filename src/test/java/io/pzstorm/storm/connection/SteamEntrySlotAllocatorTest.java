package io.pzstorm.storm.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SteamEntrySlotAllocatorTest {

    @Test
    void allocatesLowestFreeSlotFirst() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(8);
        assertEquals(0, allocator.acquire("a"));
        assertEquals(1, allocator.acquire("b"));
        assertEquals(2, allocator.acquire("c"));
    }

    @Test
    void assignmentIsStableAcrossRepeatedAcquires() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(8);
        short slot = allocator.acquire("a");
        allocator.acquire("b");
        assertEquals(slot, allocator.acquire("a"));
        assertEquals(slot, allocator.acquire("a"));
    }

    @Test
    void returnsMinusOneWhenTableIsFull() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(2);
        assertEquals(0, allocator.acquire("a"));
        assertEquals(1, allocator.acquire("b"));
        assertEquals(-1, allocator.acquire("c"));
        // Existing assignments still resolve at capacity.
        assertEquals(0, allocator.acquire("a"));
    }

    @Test
    void retainAllReleasesDepartedIdentitiesOnly() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(8);
        allocator.acquire("a");
        short slotB = allocator.acquire("b");
        allocator.acquire("c");

        Set<String> live = new HashSet<>();
        live.add("a");
        live.add("c");
        allocator.retainAll(live);

        assertEquals(2, allocator.size());
        assertEquals(0, allocator.acquire("a"));
        assertEquals(2, allocator.acquire("c"));
        // The departed identity's slot is the lowest free one for the next newcomer.
        assertEquals(slotB, allocator.acquire("d"));
    }

    @Test
    void reacquireAfterReleaseMayGetDifferentSlot() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(8);
        allocator.acquire("a");
        allocator.acquire("b");
        allocator.retainAll(Set.of("b"));
        // "a" departed and returns: it gets the lowest free slot, no memory of the old one.
        assertEquals(0, allocator.acquire("a"));
        assertNotEquals(allocator.acquire("a"), allocator.acquire("b"));
    }

    @Test
    void clearReleasesEverything() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(4);
        allocator.acquire("a");
        allocator.acquire("b");
        allocator.clear();
        assertEquals(0, allocator.size());
        assertEquals(0, allocator.acquire("z"));
    }

    @Test
    void neverHandsOutTheSameSlotToTwoLiveIdentities() {
        SteamEntrySlotAllocator allocator = new SteamEntrySlotAllocator(16);
        Set<Short> seen = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            assertTrue(seen.add(allocator.acquire("id" + i)));
        }
        assertEquals(-1, allocator.acquire("overflow"));
    }
}
