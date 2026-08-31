package io.pzstorm.storm.popman;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader and writer for {@code zpop_<cellX>_<cellY>.bin} — one popman cell (32x32 chunks), always
 * exactly 1024 chunk records in row-major {@code y * 32 + x} order, big-endian throughout. Layout
 * verified against 651 real save files; see {@code docs/re-popman/04-persistence.md} §2.2.
 *
 * <p>{@link DataInput}/{@link DataOutput} are big-endian by definition, which is exactly the file's
 * byte order — note this differs from the ByteBuffer wire protocol, which also disagrees with disk
 * on the order of the state and outfit fields.
 */
public final class ZpopCell {

    /** The version the native writer always emits. */
    public static final int WRITE_VERSION = 6;

    /**
     * The outfit-name table appeared at version 4. Versions 1..3 are refused rather than guessed:
     * their zombie record has no outfit id and its size depends on a runtime server flag, so a
     * wrong guess would not fail, it would shift every subsequent byte.
     */
    private static final int MIN_SUPPORTED_VERSION = 4;

    /** Version 5 widened the per-zombie state from a legacy enum byte to the modern bitfield. */
    private static final int FIRST_WIDE_STATE_VERSION = 5;

    public int version = WRITE_VERSION;

    /** World-age hours at the cell's last repopulation pass. Native {@code cell+0x30}. */
    public float lastRepopTime;

    /** World-age hours at the cell's last redistribution pass. Native {@code cell+0xec}. */
    public float lastRedistributeTime;

    /**
     * The outfit-name table as it stood in the file, kept so a read can be written back exactly.
     */
    public List<String> outfitNames = new ArrayList<>();

    public final List<ZpopChunk> chunks = new ArrayList<>(ZpopChunk.CHUNKS_PER_CELL);

    public ZpopCell() {
        for (int i = 0; i < ZpopChunk.CHUNKS_PER_CELL; i++) {
            chunks.add(new ZpopChunk());
        }
    }

    public ZpopChunk chunk(int x, int y) {
        return chunks.get(y * ZpopChunk.CHUNKS_PER_CELL_SIDE + x);
    }

    /**
     * @param runtimeOutfitNames lower-cased outfit names in the order {@code n_setOutfitNames}
     *     supplied them; a zombie whose stored outfit name is missing from this list loses its
     *     outfit, exactly as the native loader does. Pass {@code null} to keep every stored
     *     descriptor verbatim, which is what a byte-exact round-trip needs.
     */
    public static ZpopCell read(DataInput in, List<String> runtimeOutfitNames) throws IOException {
        ZpopCell cell = new ZpopCell();
        cell.version = in.readUnsignedShort();

        if (cell.version < MIN_SUPPORTED_VERSION || cell.version > WRITE_VERSION) {
            throw new IOException(
                    "unsupported zpop version "
                            + cell.version
                            + "; only "
                            + MIN_SUPPORTED_VERSION
                            + ".."
                            + WRITE_VERSION
                            + " have a recovered layout");
        }

        cell.outfitNames = readNameTable(in);

        Map<String, Integer> runtimeIndex = buildRuntimeIndex(runtimeOutfitNames);

        cell.lastRepopTime = in.readFloat();
        if (cell.version > 2) {
            cell.lastRedistributeTime = in.readFloat();
        }

        for (ZpopChunk chunk : cell.chunks) {
            chunk.basePop = in.readShort();
            int count = in.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                chunk.zombies.add(readZombie(in, cell.version, cell.outfitNames, runtimeIndex));
            }
            chunk.lastSeenTime = in.readFloat();
            chunk.lastRepopTime = in.readFloat();
        }
        return cell;
    }

    public void write(DataOutput out, List<String> runtimeOutfitNames) throws IOException {
        out.writeShort(WRITE_VERSION);

        writeNameTable(out, runtimeOutfitNames);

        out.writeFloat(lastRepopTime);
        out.writeFloat(lastRedistributeTime);

        for (ZpopChunk chunk : chunks) {
            out.writeShort(chunk.basePop);
            out.writeShort(chunk.zombies.size());
            for (PopManZombie z : chunk.zombies) {
                writeZombie(out, z);
            }
            out.writeFloat(chunk.lastSeenTime);
            out.writeFloat(chunk.lastRepopTime);
        }
    }

    /** {@code null} means "keep every stored descriptor verbatim", which round-trips exactly. */
    static Map<String, Integer> buildRuntimeIndex(List<String> runtimeOutfitNames) {
        if (runtimeOutfitNames == null) {
            return null;
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < runtimeOutfitNames.size(); i++) {
            index.putIfAbsent(runtimeOutfitNames.get(i), i);
        }
        return index;
    }

    static void writeNameTable(DataOutput out, List<String> names) throws IOException {
        out.writeShort(names.size());
        for (String name : names) {
            byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
            out.writeShort(bytes.length);
            out.write(bytes);
        }
    }

    static List<String> readNameTable(DataInput in) throws IOException {
        int nameCount = in.readUnsignedShort();
        List<String> names = new ArrayList<>(nameCount);
        for (int i = 0; i < nameCount; i++) {
            byte[] bytes = new byte[in.readUnsignedShort()];
            in.readFully(bytes);
            names.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return names;
    }

    static PopManZombie readZombie(
            DataInput in,
            int version,
            List<String> fileOutfitNames,
            Map<String, Integer> runtimeIndex)
            throws IOException {
        PopManZombie z = new PopManZombie();
        z.x = in.readFloat();
        z.y = in.readFloat();
        z.z = in.readByte();
        z.dir = clampDirection(in.readUnsignedByte());
        z.stateFlags =
                version < FIRST_WIDE_STATE_VERSION
                        ? expandLegacyState(in.readUnsignedByte())
                        : in.readInt();
        int storedID = in.readInt();
        z.descriptorID =
                runtimeIndex == null
                        ? storedID
                        : remapOutfitID(storedID, fileOutfitNames, runtimeIndex);
        return z;
    }

    static void writeZombie(DataOutput out, PopManZombie z) throws IOException {
        out.writeFloat(z.x);
        out.writeFloat(z.y);
        out.writeByte((byte) Math.floor(z.z));
        out.writeByte(z.dir);
        out.writeInt(z.stateFlags);
        out.writeInt(z.descriptorID);
    }

    /** Anything past the eight real {@code IsoDirections} clamps to {@code Max}. */
    /** Version 4 and earlier stored a small enum where the modern format stores a bitfield. */
    static int expandLegacyState(int legacy) {
        return switch (legacy) {
            case 1 -> 6;
            case 2 -> 8;
            case 3 -> 2;
            case 4 -> 4;
            default -> 0;
        };
    }

    private static byte clampDirection(int dir) {
        return (byte) Math.min(dir, 8);
    }

    /**
     * An outfit index is only meaningful against the outfit list that was loaded when the file was
     * written, and mods shift that list. The stored index is resolved to a name through the file's
     * own table and then back to whatever index that name holds now; an outfit that no longer
     * exists drops the zombie's whole descriptor to 0. The female bit and the low 16 bits (variant
     * and hat-fallen) survive the remap.
     */
    static int remapOutfitID(
            int id, List<String> fileOutfitNames, Map<String, Integer> runtimeIndex) {
        if (id == 0) {
            return 0;
        }
        int index = (id & 0x7fffffff) >> 16;
        if (index >= fileOutfitNames.size()) {
            return 0;
        }
        Integer resolved = runtimeIndex.get(fileOutfitNames.get(index));
        if (resolved == null) {
            return 0;
        }
        return (resolved << 16) | (id & 0xffff) | (id & 0x80000000);
    }
}
