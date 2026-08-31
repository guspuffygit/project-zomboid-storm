package io.pzstorm.storm.popman;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The roaming hordes, saved alongside the per-cell files as {@code zpop_virtual.bin}. Same outfit
 * name table and same 18-byte zombie record as {@link ZpopCell}, but the zombies are grouped rather
 * than binned by chunk.
 *
 * <p>The header is verified against a real save; the group body is not — every {@code
 * zpop_virtual.bin} in the corpus has zero groups, so nothing has ever exercised it. Treat a
 * mismatch here as a layout bug before suspecting the caller.
 */
public final class ZpopVirtualFile {

    public static final int WRITE_VERSION = 6;

    /** Written clamped into {@code 1..0x7fff}. */
    public static final int MAX_GROUP_SIZE = 0x7fff;

    public int version = WRITE_VERSION;

    /** The table as it was found on disk, needed for a byte-exact rewrite. */
    public List<String> outfitNames = new ArrayList<>();

    public final List<Group> groups = new ArrayList<>();

    /** A horde — native {@code popman::VirtualZombie}, which is a group and not a single zombie. */
    public static final class Group {

        public int field0x04;
        public int field0x08;
        public final List<PopManZombie> members = new ArrayList<>();
    }

    public static ZpopVirtualFile read(DataInput in, List<String> runtimeOutfitNames)
            throws IOException {
        ZpopVirtualFile file = new ZpopVirtualFile();
        file.version = in.readUnsignedShort();
        if (file.version != WRITE_VERSION) {
            throw new IOException(
                    "unsupported zpop_virtual version "
                            + file.version
                            + "; only "
                            + WRITE_VERSION
                            + " has a recovered layout");
        }

        file.outfitNames = ZpopCell.readNameTable(in);
        Map<String, Integer> runtimeIndex = ZpopCell.buildRuntimeIndex(runtimeOutfitNames);

        int groupCount = in.readInt();
        for (int i = 0; i < groupCount; i++) {
            Group group = new Group();
            int count = in.readUnsignedShort();
            group.field0x04 = in.readInt();
            group.field0x08 = in.readInt();
            for (int m = 0; m < Math.max(count, 1); m++) {
                group.members.add(
                        ZpopCell.readZombie(in, file.version, file.outfitNames, runtimeIndex));
            }
            file.groups.add(group);
        }
        return file;
    }

    public void write(DataOutput out, List<String> runtimeOutfitNames) throws IOException {
        out.writeShort(WRITE_VERSION);
        ZpopCell.writeNameTable(out, runtimeOutfitNames);

        out.writeInt(groups.size());
        for (Group group : groups) {
            out.writeShort(Math.min(Math.max(group.members.size(), 1), MAX_GROUP_SIZE));
            out.writeInt(group.field0x04);
            out.writeInt(group.field0x08);
            for (PopManZombie member : group.members) {
                ZpopCell.writeZombie(out, member);
            }
        }
    }
}
