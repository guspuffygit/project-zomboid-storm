package io.pzstorm.storm.popman;

import io.pzstorm.storm.logging.StormLogger;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The population's disk layer — one {@code zpop_<cellX>_<cellY>.bin} per cell plus the single
 * {@code zpop_virtual.bin} for the travelling hordes, all under the {@code zpop} subdirectory of a
 * save. Paths and byte layouts are recovered in {@code docs/re-popman/04-persistence.md} §1.2 and
 * §2; {@link ZpopCell} and {@link ZpopVirtualFile} own the bytes, this owns the files and the
 * translation to and from the runtime model.
 *
 * <p>Every file carries its own copy of the outfit-name table, because a descriptor stores an
 * <em>index</em> into a list that mods reorder between sessions. Loading resolves the stored index
 * through the file's table and back into the current one, so a zombie keeps the outfit it was
 * wearing rather than whatever now sits at that index.
 */
public final class PopManStore implements PopManCellMap.Loader {

    public static final String SUBDIRECTORY = "zpop";
    public static final String CELL_FILE_PREFIX = "zpop_";
    public static final String CELL_FILE_SUFFIX = ".bin";
    public static final String VIRTUAL_FILE_NAME = "zpop_virtual.bin";

    /** Written beside the real file and moved over it, so a crash mid-write loses nothing. */
    private static final String TEMP_SUFFIX = ".tmp";

    private final Path saveDirectory;
    private final Supplier<List<String>> runtimeOutfitNames;

    /**
     * @param saveDirectory the directory holding the {@code zpop} subdirectory — vanilla's {@code
     *     GameModeCacheDir + Core.GameSaveWorld}
     * @param runtimeOutfitNames the current lower-cased outfit list, as {@code n_setOutfitNames}
     *     supplied it. Re-read on every call rather than captured, since the table is set after the
     *     store exists and can be replaced.
     */
    public PopManStore(Path saveDirectory, Supplier<List<String>> runtimeOutfitNames) {
        this.saveDirectory = saveDirectory;
        this.runtimeOutfitNames = runtimeOutfitNames;
    }

    public Path directory() {
        return saveDirectory.resolve(SUBDIRECTORY);
    }

    public Path cellFile(int cellX, int cellY) {
        return directory().resolve(CELL_FILE_PREFIX + cellX + "_" + cellY + CELL_FILE_SUFFIX);
    }

    public Path virtualFile() {
        return directory().resolve(VIRTUAL_FILE_NAME);
    }

    /**
     * Fills every chunk of {@code cell} plus the cell's own two clocks from its save file, leaving
     * the running totals to the caller.
     *
     * <p>The file is parsed whole before anything is written into the cell: a truncated file must
     * leave a cell that can still be given a virgin population, not a half-filled one that would
     * then be populated on top of itself.
     *
     * @return false when the cell has never been saved, or when its file could not be read
     */
    @Override
    public boolean load(PopManCell cell) {
        Path file = cellFile(cell.cellX, cell.cellY);
        if (!Files.isRegularFile(file)) {
            return false;
        }

        ZpopCell stored;
        try (DataInputStream in = openRead(file)) {
            stored = ZpopCell.read(in, outfitNames());
        } catch (IOException e) {
            StormLogger.LOGGER.error("Unable to read popman cell file " + file, e);
            return false;
        }

        cell.lastRepopTime = stored.lastRepopTime;
        cell.lastRedistributeTime = stored.lastRedistributeTime;
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            ZpopChunk source = stored.chunks.get(i);
            PopManChunk target = cell.chunks[i];
            target.basePop = source.basePop;
            target.lastSeenTime = source.lastSeenTime;
            target.lastRepopTime = source.lastRepopTime;
            target.zombies.clear();
            target.zombies.addAll(source.zombies);
        }
        return true;
    }

    /**
     * Writes {@code cell} out as a version-6 file, creating the {@code zpop} directory if needed.
     */
    public void save(PopManCell cell) {
        ZpopCell stored = new ZpopCell();
        stored.lastRepopTime = cell.lastRepopTime;
        stored.lastRedistributeTime = cell.lastRedistributeTime;
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            PopManChunk source = cell.chunks[i];
            ZpopChunk target = stored.chunks.get(i);
            target.basePop = source.basePop;
            target.lastSeenTime = source.lastSeenTime;
            target.lastRepopTime = source.lastRepopTime;
            target.zombies.addAll(source.zombies);
        }

        if (write(cellFile(cell.cellX, cell.cellY), out -> stored.write(out, outfitNames()))) {
            cell.dirty = false;
        }
    }

    /**
     * The hordes in flight at the last save. They come back with no cell attached — the file does
     * not record one, and the owning cell follows from the leader's position anyway.
     */
    public List<PopManGroup> loadGroups() {
        List<PopManGroup> groups = new ArrayList<>();
        Path file = virtualFile();
        if (!Files.isRegularFile(file)) {
            return groups;
        }

        ZpopVirtualFile stored;
        try (DataInputStream in = openRead(file)) {
            stored = ZpopVirtualFile.read(in, outfitNames());
        } catch (IOException e) {
            StormLogger.LOGGER.error("Unable to read popman virtual file " + file, e);
            return groups;
        }

        for (ZpopVirtualFile.Group source : stored.groups) {
            PopManGroup group = new PopManGroup(source.members.get(0));
            group.members.addAll(source.members.subList(1, source.members.size()));
            groups.add(group);
        }
        return groups;
    }

    public void saveGroups(List<PopManGroup> groups) {
        ZpopVirtualFile file = new ZpopVirtualFile();
        for (PopManGroup group : groups) {
            ZpopVirtualFile.Group stored = new ZpopVirtualFile.Group();
            stored.members.addAll(group.members.isEmpty() ? List.of(group.leader) : group.members);
            file.groups.add(stored);
        }
        write(virtualFile(), out -> file.write(out, outfitNames()));
    }

    private List<String> outfitNames() {
        List<String> names = runtimeOutfitNames.get();
        return names == null ? List.of() : names;
    }

    private static DataInputStream openRead(Path file) throws IOException {
        return new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
    }

    /**
     * @return whether the file now holds what {@code body} produced
     */
    private static boolean write(Path file, Body body) {
        Path temp = file.resolveSibling(file.getFileName() + TEMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out =
                    new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                body.writeTo(out);
            }
            Files.move(
                    temp,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            StormLogger.LOGGER.error("Unable to write popman file " + file, e);
            deleteQuietly(temp);
            return false;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            StormLogger.LOGGER.warn("Unable to remove partial popman file " + file, e);
        }
    }

    private interface Body {
        void writeTo(DataOutputStream out) throws IOException;
    }
}
