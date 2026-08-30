package io.pzstorm.storm.connection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Stable identity → table-slot assignments for {@link SteamPlayerListReconciler}: each user the
 * server is holding gets one slot in the native Steam user table, held from first sight (login
 * queue) through spawn to disconnect, so the advertised entry never changes ids mid-session.
 *
 * <p>Slots are allocated lowest-free-first and stay assigned while the identity remains present —
 * including while the entry is clamped out at {@code MaxPlayers} — so entries never churn slots
 * between sweeps. {@link #retainAll(Set)} releases the slots of departed identities.
 *
 * <p>Deliberately references no {@code zombie.*} types so unit tests can exercise it in a bare JVM.
 */
final class SteamEntrySlotAllocator {

    private final HashMap<String, Short> slotByIdentity = new HashMap<>();
    private final String[] identityBySlot;

    SteamEntrySlotAllocator(int tableSize) {
        this.identityBySlot = new String[tableSize];
    }

    /**
     * Returns this identity's slot, allocating the lowest free one on first sight. {@code -1} when
     * the table is full (defensive only in practice: the table outnumbers the connection pool).
     */
    short acquire(String identity) {
        Short existing = slotByIdentity.get(identity);
        if (existing != null) {
            return existing;
        }
        for (int slot = 0; slot < identityBySlot.length; slot++) {
            if (identityBySlot[slot] == null) {
                identityBySlot[slot] = identity;
                slotByIdentity.put(identity, (short) slot);
                return (short) slot;
            }
        }
        return -1;
    }

    /** Releases the slots of every identity not in {@code live}. */
    void retainAll(Set<String> live) {
        if (slotByIdentity.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Short>> it = slotByIdentity.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Short> entry = it.next();
            if (!live.contains(entry.getKey())) {
                identityBySlot[entry.getValue()] = null;
                it.remove();
            }
        }
    }

    void clear() {
        slotByIdentity.clear();
        java.util.Arrays.fill(identityBySlot, null);
    }

    int size() {
        return slotByIdentity.size();
    }
}
