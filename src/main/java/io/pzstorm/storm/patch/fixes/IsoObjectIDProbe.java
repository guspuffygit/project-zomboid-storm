package io.pzstorm.storm.patch.fixes;

import java.util.Map;

/**
 * Pure algorithm behind {@link IsoObjectIDAllocateFixPatch}: probe a {@link Map} keyed by {@code
 * short} for a slot not currently in use, starting from a caller-supplied "next" cursor and
 * advancing through the entire 16-bit short space (skipping the sentinel {@code -1}).
 *
 * <p>Vanilla {@code zombie.network.IsoObjectID.allocateID()} is a free-running 16-bit counter with
 * no uniqueness check. With the live zombie population uncapped (e.g. {@code
 * -Dstorm.disableZombieCull=true}), the count of live entries can grow into the same order of
 * magnitude as the address space and collisions on the {@code put(short, T)} silently overwrite an
 * existing live entry, leaving its holder reachable in {@code cell.zombieList} but unreachable by
 * ID. The downstream symptom is client-side zombie duplication ("mitosis") when the server starts
 * broadcasting that overwritten holder under a freshly allocated ID and the client's {@code
 * IDToZombieMap.get(id)} miss path spawns a brand-new client zombie via {@code
 * createRealZombieAlways}.
 *
 * <p>The probe walks the short range exactly once. Each iteration advances by 1, with one extra
 * step over {@code -1} (the {@code ID_INVALID} sentinel). When a free slot is found the new cursor
 * is returned; when every one of the {@value #VALID_ID_COUNT} usable slots is occupied {@link
 * #ID_INVALID} is returned, which existing callsites ({@code VirtualZombieManager}, {@code
 * ReanimatedPlayers}, {@code IsoDeadBody.reanimate}) already interpret as "pool exhausted, abort".
 *
 * <p>Factored into a dedicated helper so the algorithm is unit-testable without bytecode rewriting.
 */
public final class IsoObjectIDProbe {

    /** Reserved "no such ID" sentinel; matches {@code zombie.network.IsoObjectID.incorrect}. */
    public static final short ID_INVALID = -1;

    /** Total slots in the 16-bit short range. */
    public static final int SHORT_RANGE = 65536;

    /** Usable slot count after excluding the {@link #ID_INVALID} sentinel. */
    public static final int VALID_ID_COUNT = SHORT_RANGE - 1;

    private IsoObjectIDProbe() {}

    /**
     * Walk the short space starting from {@code startNextId} and return the first ID not present in
     * {@code idToObjectMap}, or {@link #ID_INVALID} if every usable slot is occupied.
     *
     * @param startNextId the caller's current {@code nextId} cursor; the search begins at {@code
     *     startNextId + 1}.
     * @param idToObjectMap live map of occupied IDs. Reads are point-in-time; concurrent mutation
     *     by another thread is tolerated because the only consumer (game server main thread) calls
     *     {@code allocateID} and the subsequent {@code put} on the same thread.
     * @return the first free ID (also the caller's new {@code nextId} cursor), or {@link
     *     #ID_INVALID} when the address space is exhausted.
     */
    public static short nextFreeId(short startNextId, Map<Short, ?> idToObjectMap) {
        short id = startNextId;
        for (int attempts = 0; attempts < VALID_ID_COUNT; attempts++) {
            id++;
            if (id == ID_INVALID) {
                id++;
            }
            if (!idToObjectMap.containsKey(id)) {
                return id;
            }
        }
        return ID_INVALID;
    }
}
