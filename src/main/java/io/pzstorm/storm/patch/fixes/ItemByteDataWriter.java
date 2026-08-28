package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;

/**
 * Replacement body for {@code zombie.inventory.InventoryItem.storeInByteData(IsoObject)}, installed
 * by {@link InventoryItemStoreByteDataPatch}.
 *
 * <p>Vanilla serializes the object into a {@code static ByteBuffer tempBuffer =
 * ByteBuffer.allocate(20000)} and lets {@link BufferOverflowException} — a {@link
 * RuntimeException}, so not caught by the method's {@code catch (IOException)} — escape. {@code
 * IsoDeadBody.getItem()} throws before it assigns {@code createdCorpseItem}, so the throw is
 * permanent for that corpse: every retry re-serializes and re-throws.
 *
 * <p>The overflow is reachable because {@code IsoDeadBody} inherits the dead character's entire
 * mod-data table ({@code LuaManager.copyTable} in its constructor) and {@code IsoMovingObject.save}
 * writes that table first. On a heavily modded server a player's mod data alone can exceed 20 KB,
 * at which point their corpse can never be picked up, dragged, or stored by anyone — the client
 * logs {@code setJobType of non-table: null} from {@code ISGrabCorpseAction} and the corpse stays
 * on the ground forever.
 *
 * <p>This writer grows the scratch buffer instead of overflowing, and emits a throttled warning
 * naming the largest mod-data keys so the responsible mod is identifiable from a player's log
 * bundle.
 */
public final class ItemByteDataWriter {

    /** Vanilla {@code InventoryItem.tempBuffer} capacity; also the oversize reporting threshold. */
    public static final int VANILLA_CAPACITY = 20000;

    /** Refuse to grow past this; a corpse this large means something is badly wrong. */
    public static final int MAX_CAPACITY = 32 << 20;

    /** {@code 'W' 'V' 'E' 'R'} plus the world version int that vanilla prepends. */
    private static final int HEADER_BYTES = 8;

    private static final long WARN_INTERVAL_MS = 60_000L;

    private static final int WARN_TOP_KEYS = 8;

    private static ByteBuffer scratch = ByteBuffer.allocate(VANILLA_CAPACITY);

    private static long lastWarnAt;

    private ItemByteDataWriter() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Serializes {@code object} into an item-ready {@code byteData} buffer.
     *
     * @param object the {@link IsoObject} being stored, typed as {@code Object} so the advice never
     *     forces an early load of a PZ class.
     * @param existing the item's current {@code byteData}, reused when large enough — exactly as
     *     vanilla does.
     * @return the buffer to assign to {@code byteData}, or {@code null} to fall through to the
     *     vanilla body (which will fail the way it always has, but visibly).
     */
    public static ByteBuffer write(Object object, ByteBuffer existing) {
        try {
            IsoObject isoObject = (IsoObject) object;
            ByteBuffer saved = fill(out -> isoObject.save(out, false), isoObject.getClass());
            if (saved == null) {
                return null;
            }
            if (saved.limit() > VANILLA_CAPACITY) {
                warnOversize(isoObject, saved.limit());
            }
            return prependHeader(saved, existing);
        } catch (Throwable t) {
            LOGGER.error("Storm: failed to serialize object into item byte data", t);
            return null;
        }
    }

    /** What {@link #fill} drives; {@code IsoObject.save} in production, a stub under test. */
    interface Saver {
        void save(ByteBuffer output) throws IOException;
    }

    /**
     * Serializes into the shared scratch buffer, doubling it and retrying on overflow. Returns a
     * flipped buffer, or {@code null} when {@link #MAX_CAPACITY} is not enough.
     */
    static ByteBuffer fill(Saver saver, Class<?> what) {
        ByteBuffer buffer = scratch;
        while (true) {
            buffer.clear();
            try {
                saver.save(buffer);
                buffer.flip();
                return buffer;
            } catch (BufferOverflowException e) {
                int grown = buffer.capacity() * 2;
                if (grown > MAX_CAPACITY) {
                    LOGGER.error(
                            "Storm: {} exceeds {} bytes serialized; leaving it to vanilla",
                            what.getSimpleName(),
                            MAX_CAPACITY);
                    return null;
                }
                buffer = ByteBuffer.allocate(grown);
                scratch = buffer;
            } catch (IOException e) {
                // Vanilla logs and keeps whatever was written; preserve that behaviour.
                LOGGER.error("Storm: IO error serializing object into item byte data", e);
                buffer.flip();
                return buffer;
            }
        }
    }

    /**
     * Writes the {@code WVER} signature and world version ahead of the payload, skipping the two
     * leading bytes vanilla drops (the {@code Serialize()} flag and the class id, which {@code
     * loadFromByteData} supplies itself).
     */
    static ByteBuffer prependHeader(ByteBuffer saved, ByteBuffer existing) {
        int payload = saved.limit() - 2;
        int needed = payload + HEADER_BYTES;
        ByteBuffer out =
                existing == null || existing.capacity() < needed
                        ? ByteBuffer.allocate(needed)
                        : existing;

        saved.get();
        saved.get();
        out.clear();
        out.put((byte) 87);
        out.put((byte) 86);
        out.put((byte) 69);
        out.put((byte) 82);
        out.putInt(IsoWorld.WorldVersion);
        out.put(saved);
        out.flip();
        return out;
    }

    private static void warnOversize(IsoObject object, int size) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt < WARN_INTERVAL_MS) {
            return;
        }
        lastWarnAt = now;
        LOGGER.warn(
                "Storm: {} serializes to {} bytes, over the vanilla {} byte item limit"
                        + " (vanilla would throw BufferOverflowException here);"
                        + " largest mod data keys: {}",
                object.getClass().getSimpleName(),
                size,
                VANILLA_CAPACITY,
                describeModData(object));
    }

    /**
     * Renders the biggest top-level mod-data keys as {@code key=NNNNB}, largest first, so a log
     * bundle names the mod responsible for the bloat.
     */
    static String describeModData(IsoObject object) {
        if (!object.hasModData()) {
            return "none";
        }
        KahluaTable table = object.getModData();
        if (!(table instanceof KahluaTableImpl)) {
            return "unknown";
        }
        return describeTable((KahluaTableImpl) table);
    }

    static String describeTable(KahluaTableImpl table) {
        ByteBuffer probe = ByteBuffer.allocate(scratch.capacity());
        List<String> entries = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : table.delegate.entrySet()) {
            if (!(entry.getValue() instanceof KahluaTableImpl)) {
                continue;
            }
            int size = measure((KahluaTableImpl) entry.getValue(), probe);
            entries.add(String.valueOf(entry.getKey()));
            sizes.add(size);
        }
        if (entries.isEmpty()) {
            return "no nested tables";
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(sizes::get).reversed());

        StringBuilder sb = new StringBuilder();
        int shown = Math.min(WARN_TOP_KEYS, order.size());
        for (int i = 0; i < shown; i++) {
            int index = order.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(entries.get(index)).append('=').append(sizes.get(index)).append('B');
        }
        if (order.size() > shown) {
            sb.append(" (+").append(order.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    /** Serialized size of one nested table, or {@code -1} when it does not fit the probe buffer. */
    private static int measure(KahluaTableImpl table, ByteBuffer probe) {
        probe.clear();
        try {
            table.save(probe);
            return probe.position();
        } catch (BufferOverflowException e) {
            return -1;
        }
    }
}
