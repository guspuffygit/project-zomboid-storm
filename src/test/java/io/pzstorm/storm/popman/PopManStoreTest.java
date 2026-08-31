package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PopManStoreTest implements UnitTest {

    /** Cell 60,11 covers world squares x in [15360, 15616), y in [2816, 3072). */
    private static final int CELL_X = 60;

    private static final int CELL_Y = 11;

    private static final int GENERIC02 = 0x80000000 | (2 << 16) | 0x0109;

    @TempDir Path saveDirectory;

    private List<String> runtimeOutfits = new ArrayList<>(List.of("agent", "police", "generic02"));

    private PopManStore store;

    @BeforeEach
    void openStore() {
        store = new PopManStore(saveDirectory, () -> runtimeOutfits);
    }

    private static PopManZombie zombie(float x, float y, float z, int dir, int state, int outfit) {
        PopManZombie zombie = new PopManZombie();
        zombie.x = x;
        zombie.y = y;
        zombie.z = z;
        zombie.dir = (byte) dir;
        zombie.stateFlags = state;
        zombie.descriptorID = outfit;
        return zombie;
    }

    private static void assertSameZombie(PopManZombie expected, PopManZombie actual) {
        assertEquals(expected.x, actual.x, "x");
        assertEquals(expected.y, actual.y, "y");
        assertEquals(expected.z, actual.z, "z");
        assertEquals(expected.dir, actual.dir, "dir");
        assertEquals(expected.stateFlags, actual.stateFlags, "stateFlags");
        assertEquals(expected.descriptorID, actual.descriptorID, "descriptorID");
    }

    /** A cell with something in three different chunks, plus every clock set to its own value. */
    private PopManCell populatedCell() {
        PopManCell cell = new PopManCell(CELL_X, CELL_Y);
        cell.lastRepopTime = 151.61061F;
        cell.lastRedistributeTime = 149.35324F;

        PopManChunk first = cell.chunkAtSquare(15360, 2844);
        first.basePop = 9;
        first.lastSeenTime = 12.5F;
        first.lastRepopTime = 3.3987236F;
        first.zombies.add(zombie(15360.5F, 2844.4F, 1.0F, 5, 0x05, GENERIC02));
        first.zombies.add(zombie(15367.25F, 2847.75F, 0.0F, 0, 0x06, 0));

        PopManChunk second = cell.chunkAtSquare(15500, 2900);
        second.basePop = 3;
        second.lastSeenTime = 88.0F;
        second.lastRepopTime = 90.5F;
        second.zombies.add(zombie(15500.5F, 2900.5F, 2.0F, 7, 0x0f, (1 << 16) | 0x0002));

        PopManChunk last = cell.chunkAtSquare(15615, 3071);
        last.basePop = 1;
        last.zombies.add(zombie(15615.5F, 3071.5F, 0.0F, 3, 0x04, 0));

        cell.dirty = true;
        return cell;
    }

    @Test
    void pathsFollowTheNativeNamingScheme() {
        Path zpop = saveDirectory.resolve("zpop");

        assertEquals(zpop, store.directory());
        assertEquals(zpop.resolve("zpop_60_11.bin"), store.cellFile(60, 11));
        assertEquals(zpop.resolve("zpop_-3_-14.bin"), store.cellFile(-3, -14));
        assertEquals(zpop.resolve("zpop_virtual.bin"), store.virtualFile());
    }

    @Test
    void savingCreatesTheDirectoryAndAVersionSixFile() throws IOException {
        store.save(populatedCell());

        Path file = store.cellFile(CELL_X, CELL_Y);
        assertTrue(Files.isRegularFile(file));
        byte[] bytes = Files.readAllBytes(file);
        assertEquals(ZpopCell.WRITE_VERSION, ((bytes[0] & 0xff) << 8) | (bytes[1] & 0xff));
        try (Stream<Path> written = Files.list(store.directory())) {
            assertEquals(
                    List.of(file),
                    written.toList(),
                    "the temporary file is moved over the real one, not left beside it");
        }
    }

    @Test
    void aSavedCellComesBackFieldForField() {
        PopManCell saved = populatedCell();
        store.save(saved);

        PopManCell loaded = new PopManCell(CELL_X, CELL_Y);
        assertTrue(store.load(loaded));

        assertEquals(saved.lastRepopTime, loaded.lastRepopTime);
        assertEquals(saved.lastRedistributeTime, loaded.lastRedistributeTime);
        for (int i = 0; i < PopManGeometry.CHUNKS_PER_CELL_TOTAL; i++) {
            PopManChunk before = saved.chunks[i];
            PopManChunk after = loaded.chunks[i];
            assertEquals(before.chunkX, after.chunkX);
            assertEquals(before.chunkY, after.chunkY);
            assertEquals(before.basePop, after.basePop, "basePop of chunk " + i);
            assertEquals(before.lastSeenTime, after.lastSeenTime, "lastSeenTime of chunk " + i);
            assertEquals(before.lastRepopTime, after.lastRepopTime, "lastRepopTime of chunk " + i);
            assertEquals(before.zombies.size(), after.zombies.size(), "zombies of chunk " + i);
            for (int z = 0; z < before.zombies.size(); z++) {
                assertSameZombie(before.zombies.get(z), after.zombies.get(z));
            }
        }
    }

    @Test
    void loadingLeavesTheRunningTotalsToTheCaller() {
        store.save(populatedCell());

        PopManCell loaded = new PopManCell(CELL_X, CELL_Y);
        store.load(loaded);

        assertEquals(0, loaded.virtualCount, "totals are the cell map's job, after the load");
        assertEquals(0, loaded.basePopSum);
    }

    @Test
    void savingClearsTheDirtyFlag() {
        PopManCell cell = populatedCell();
        store.save(cell);

        assertFalse(cell.dirty);
    }

    @Test
    void zHeightNarrowsToWholeFloors() {
        PopManCell cell = new PopManCell(CELL_X, CELL_Y);
        PopManChunk chunk = cell.chunkAtSquare(15360, 2816);
        chunk.zombies.add(zombie(15360.5F, 2816.5F, 2.75F, 0, 0, 0));
        chunk.zombies.add(zombie(15361.5F, 2816.5F, -0.5F, 0, 0, 0));
        store.save(cell);

        PopManCell loaded = new PopManCell(CELL_X, CELL_Y);
        store.load(loaded);

        List<PopManZombie> back = loaded.chunkAtSquare(15360, 2816).zombies;
        assertEquals(2.0F, back.get(0).z, "a floor is a floor, not a height");
        assertEquals(-1.0F, back.get(1).z, "and it floors rather than truncating");
    }

    @Test
    void aCellWithNoFileIsNotLoadedAndNotTouched() {
        PopManCell cell = new PopManCell(CELL_X, CELL_Y);

        assertFalse(store.load(cell));

        assertEquals(0.0F, cell.lastRepopTime);
        assertEquals(0.0F, cell.lastRedistributeTime);
        for (PopManChunk chunk : cell.chunks) {
            assertTrue(chunk.zombies.isEmpty());
        }
        assertFalse(Files.exists(store.directory()), "a read must not create anything");
    }

    @Test
    void aTruncatedFileIsRefusedRatherThanHalfLoaded() throws IOException {
        store.save(populatedCell());
        Path file = store.cellFile(CELL_X, CELL_Y);
        byte[] whole = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(whole, whole.length / 2));

        PopManCell cell = new PopManCell(CELL_X, CELL_Y);
        assertFalse(store.load(cell));

        assertEquals(0.0F, cell.lastRepopTime);
        for (PopManChunk chunk : cell.chunks) {
            assertTrue(chunk.zombies.isEmpty(), "a partial parse must leave no partial cell");
        }
    }

    @Test
    void anOutfitKeepsItsNameWhenTheTableIsReordered() {
        store.save(populatedCell());
        runtimeOutfits = new ArrayList<>(List.of("agent", "newmodoutfit", "police", "generic02"));

        PopManCell loaded = new PopManCell(CELL_X, CELL_Y);
        store.load(loaded);

        int descriptor = loaded.chunkAtSquare(15360, 2844).zombies.get(0).descriptorID;
        assertEquals(3, (descriptor & 0x7fffffff) >> 16, "generic02 moved from index 2 to 3");
        assertEquals(0x0109, descriptor & 0xffff, "variant and hat-fallen survive");
        assertEquals(0x80000000, descriptor & 0x80000000, "female bit survives");
    }

    @Test
    void anOutfitThatIsGoneTakesTheWholeDescriptorWithIt() {
        store.save(populatedCell());
        runtimeOutfits = new ArrayList<>(List.of("agent", "police"));

        PopManCell loaded = new PopManCell(CELL_X, CELL_Y);
        store.load(loaded);

        assertEquals(0, loaded.chunkAtSquare(15360, 2844).zombies.get(0).descriptorID);
        assertEquals(
                (1 << 16) | 0x0002,
                loaded.chunkAtSquare(15500, 2900).zombies.get(0).descriptorID,
                "police is still there, at the same index");
    }

    @Test
    void travellingGroupsRoundTripWithTheirLeaderFirst() {
        PopManZombie leader = zombie(15400.5F, 2850.5F, 0.0F, 4, 0x05, GENERIC02);
        PopManGroup horde = new PopManGroup(leader);
        horde.members.add(zombie(15401.5F, 2851.5F, 1.0F, 2, 0x04, 0));
        horde.members.add(zombie(15402.5F, 2852.5F, 0.0F, 6, 0x06, (1 << 16) | 0x0002));
        PopManGroup loner = new PopManGroup(zombie(15450.5F, 2860.5F, 0.0F, 1, 0x05, 0));

        store.saveGroups(List.of(horde, loner));
        List<PopManGroup> loaded = store.loadGroups();

        assertEquals(2, loaded.size());
        assertEquals(3, loaded.get(0).members.size());
        for (int i = 0; i < horde.members.size(); i++) {
            assertSameZombie(horde.members.get(i), loaded.get(0).members.get(i));
        }
        assertSame(
                loaded.get(0).members.get(0),
                loaded.get(0).leader,
                "the leader is the group's first member, not a copy of it");
        assertEquals(1, loaded.get(1).members.size());
        assertSameZombie(loner.leader, loaded.get(1).leader);
    }

    @Test
    void groupsComeBackWithNoCellBecauseTheFileHasNone() {
        PopManGroup group = new PopManGroup(zombie(15400.5F, 2850.5F, 0.0F, 4, 0x05, 0));
        group.cell = new PopManCell(CELL_X, CELL_Y);
        store.saveGroups(List.of(group));

        assertNull(store.loadGroups().get(0).cell);
    }

    @Test
    void noVirtualFileMeansNoHordes() {
        assertTrue(store.loadGroups().isEmpty());
        assertFalse(Files.exists(store.directory()), "a read must not create anything");
    }

    @Test
    void theCellMapLoadsThroughTheStore() {
        store.save(populatedCell());
        PopManCellMap cells = new PopManCellMap(new PopManConfig(), new ScriptedWorld(), store);

        PopManCell cell = cells.load(CELL_X, CELL_Y, 0.0, 0L);

        assertTrue(cell.loadedFromDisk);
        assertEquals(4, cell.virtualCount, "the cell map recomputes the totals the load skipped");
        assertEquals(13, cell.basePopSum);
    }
}
