package io.pzstorm.storm.map;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.zip.Deflater;
import zombie.SandboxOptions;
import zombie.core.network.ByteBufferWriter;
import zombie.network.IConnection;
import zombie.worldMap.WorldMapVisitedServer;

/**
 * Restores {@code Map.MapAllKnown} for multiplayer clients, broken by 42.20.3.
 *
 * <p>Until 42.20.2 the server pushed a joining player's visited data with {@code
 * PlayerVisitedPacket}, and its {@code processClient} re-applied the sandbox option after the
 * transfer:
 *
 * <pre>
 *     instance.processDataChunk(...);
 *     if (SandboxOptions.getInstance().map.mapAllKnown.getValue())
 *         instance.setKnownInCells(metaGrid.minX, ..., metaGrid.maxY);
 * </pre>
 *
 * <p>42.20.3 replaced that packet with {@code RequestDataPacket.RequestID.PlayerVisited} → {@code
 * WorldMapVisited.receiveRequestData}, which overwrites the client's whole {@code visited} array
 * with the server's per-user bytes and <b>dropped</b> the re-apply. The client's own all-known pass
 * ({@code WorldMapVisited.getInstance}) has already run by then, so the incoming bytes wipe it: the
 * map falls back to showing only where the player has actually been.
 *
 * <p>The fix runs server-side, so it works for vanilla clients too: when the option is on, the
 * bytes sent to the client get {@code BIT_KNOWN} set for every unit. The visited bits are kept (OR,
 * exactly like vanilla's {@code setKnownInCells}), and the copy is transient — the server's stored
 * per-user data is never touched, so disabling the option again returns players to their real
 * explored map.
 */
public final class StormMapAllKnownSend {

    /** {@code WorldMapVisited.BIT_KNOWN} (2) in each of the four 2-bit units packed per byte. */
    private static final byte ALL_KNOWN = (byte) 0xAA;

    private static Field dictionaryField;
    private static boolean disabled;

    private StormMapAllKnownSend() {}

    /**
     * Replacement for {@code WorldMapVisitedServer.sendRequestData} while {@code Map.MapAllKnown}
     * is on. Returns {@code true} when the response has been written and the vanilla body must be
     * skipped, {@code false} to leave the vanilla body untouched — nothing is written to {@code
     * writerObj} before the last point where {@code false} can still be returned.
     */
    public static boolean send(Object serverObj, Object connectionObj, Object writerObj) {
        if (disabled) {
            return false;
        }
        byte[] entity;
        byte[] compressed;
        int compressedLength;
        try {
            if (!SandboxOptions.getInstance().map.mapAllKnown.getValue()) {
                return false;
            }
            WorldMapVisitedServer server = (WorldMapVisitedServer) serverObj;
            IConnection connection = (IConnection) connectionObj;
            server.loadUser(connection);

            byte[] stored = dictionary(server).get(connection.getUserName());
            if (stored == null || stored.length == 0) {
                return false;
            }
            entity = allKnown(stored);
            compressed = new byte[entity.length + entity.length / 8 + 64];
            compressedLength = deflate(entity, compressed);
            if (compressedLength <= 0) {
                return false;
            }
        } catch (Throwable t) {
            disabled = true;
            LOGGER.error(
                    "Storm: unable to apply Map.MapAllKnown to visited map data, players will see"
                            + " only what they explored (falling back to vanilla for the rest of"
                            + " this run)",
                    t);
            return false;
        }
        ByteBufferWriter writer = (ByteBufferWriter) writerObj;
        writer.putInt(entity.length);
        writer.putInt(compressedLength);
        writer.put(compressed, 0, compressedLength);
        return true;
    }

    /**
     * Copy of the stored per-user data with {@code BIT_KNOWN} set on every unit, leaving {@code
     * BIT_VISITED} as it was — the same OR semantics as vanilla's {@code setKnownInCells} over the
     * whole grid. The array has no padding: one row is {@code widthInCells * 8} units at 2 bits
     * each, exactly {@code getSpan()} bytes.
     */
    static byte[] allKnown(byte[] stored) {
        byte[] entity = new byte[stored.length];
        for (int i = 0; i < stored.length; i++) {
            entity[i] = (byte) (stored[i] | ALL_KNOWN);
        }
        return entity;
    }

    /** Same framing as vanilla's {@code sendRequestData}; 0 means "give up, run vanilla". */
    static int deflate(byte[] entity, byte[] out) {
        Deflater deflater = new Deflater();
        int length = 0;
        try {
            deflater.setInput(entity);
            deflater.finish();
            while (!deflater.finished() && length < out.length) {
                int count = deflater.deflate(out, length, out.length - length);
                if (count == 0) {
                    break;
                }
                length += count;
            }
            return deflater.finished() ? length : 0;
        } finally {
            deflater.end();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, byte[]> dictionary(WorldMapVisitedServer server) throws Exception {
        Field field = dictionaryField;
        if (field == null) {
            field = WorldMapVisitedServer.class.getDeclaredField("dictionary");
            field.setAccessible(true);
            dictionaryField = field;
        }
        return (Map<String, byte[]>) field.get(server);
    }
}
