package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.util.StormFastContainsList;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataCell;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.regions.IsoChunkRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;

/**
 * Runs the real {@code DataRoot.getIsoWorldRegionsInCell} over a real region graph and checks that
 * filling a {@link StormFastContainsList} (what {@code WorldRegionToMetaGridFastContainsPatch} puts
 * in the field the game passes) gives exactly what a plain {@code ArrayList} gives: same regions,
 * same order, no duplicates, and a mirror that stays exact when the same list is reused.
 */
class WorldRegionToMetaGridFastContainsTest implements UnitTest {

    private static final int CELL_X = 3;
    private static final int CELL_Y = 7;

    @Test
    void fastListFillsExactlyLikeArrayListOnARealRegionGraph() throws Exception {
        for (int seed = 0; seed < 20; seed++) {
            Random random = new Random(seed);
            DataRoot root = new DataRoot();
            List<IsoChunkRegion> chunkRegions =
                    buildCell(root, CELL_X, CELL_Y, 8, 4, 3, 40 + random.nextInt(200), random);

            ArrayList<IsoWorldRegion> vanilla = new ArrayList<>();
            root.getIsoWorldRegionsInCell(CELL_X, CELL_Y, vanilla);
            StormFastContainsList<IsoWorldRegion> fast = new StormFastContainsList<>();
            root.getIsoWorldRegionsInCell(CELL_X, CELL_Y, fast);

            List<IsoWorldRegion> oracle = identityDedupeInWalkOrder(root, CELL_X, CELL_Y);
            assertTrue(
                    chunkRegions.size() > oracle.size(),
                    "fixture must hand the method duplicates to remove");
            assertTrue(oracle.contains(null), "fixture must include chunk regions with no region");
            assertSameElementsInOrder(oracle, vanilla, "vanilla vs oracle, seed " + seed);
            assertSameElementsInOrder(vanilla, fast, "fast vs vanilla, seed " + seed);
            for (IsoChunkRegion chunkRegion : chunkRegions) {
                assertTrue(fast.contains(chunkRegion.getIsoWorldRegion()));
            }

            // The game reuses the one field for every call: an absent cell must leave it empty
            // and the mirror must forget everything the previous call added.
            IsoWorldRegion someRegion = fast.get(0) != null ? fast.get(0) : fast.get(1);
            root.getIsoWorldRegionsInCell(CELL_X + 1, CELL_Y, fast);
            assertEquals(0, fast.size());
            assertFalse(fast.contains(someRegion));
            assertFalse(fast.contains(null));

            root.getIsoWorldRegionsInCell(CELL_X, CELL_Y, fast);
            assertSameElementsInOrder(vanilla, fast, "fast list reused, seed " + seed);
        }
    }

    /** Builds one cell of {@code chunksPerSide}^2 chunks and returns every chunk region added. */
    static List<IsoChunkRegion> buildCell(
            DataRoot root,
            int cellX,
            int cellY,
            int chunksPerSide,
            int levels,
            int regionsPerLevel,
            int worldRegionCount,
            Random random)
            throws Exception {
        Constructor<DataCell> cellCtor = DataCell.class.getDeclaredConstructor(DataRoot.class);
        cellCtor.setAccessible(true);
        Constructor<DataChunk> chunkCtor =
                DataChunk.class.getDeclaredConstructor(
                        int.class, int.class, DataCell.class, int.class);
        chunkCtor.setAccessible(true);
        Field cellMapField = DataRoot.class.getDeclaredField("cellMap");
        cellMapField.setAccessible(true);
        Field dataChunksField = DataCell.class.getDeclaredField("dataChunks");
        dataChunksField.setAccessible(true);
        Method getChunkRegions = DataChunk.class.getDeclaredMethod("getChunkRegions", int.class);
        getChunkRegions.setAccessible(true);

        IsoWorldRegion[] pool = new IsoWorldRegion[worldRegionCount];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = root.regionManager.allocIsoWorldRegion();
        }

        DataCell cell = cellCtor.newInstance(root);
        @SuppressWarnings("unchecked")
        Map<Integer, DataCell> cellMap = (Map<Integer, DataCell>) cellMapField.get(root);
        cellMap.put(IsoRegions.hash(cellX, cellY), cell);
        @SuppressWarnings("unchecked")
        Map<Integer, DataChunk> dataChunks = (Map<Integer, DataChunk>) dataChunksField.get(cell);

        List<IsoChunkRegion> added = new ArrayList<>();
        for (int cy = 0; cy < chunksPerSide; cy++) {
            for (int cx = 0; cx < chunksPerSide; cx++) {
                int id = cy * 32 + cx;
                DataChunk chunk = chunkCtor.newInstance(cellX * 32 + cx, cellY * 32 + cy, cell, id);
                dataChunks.put(id, chunk);
                for (int z = 0; z < levels; z++) {
                    @SuppressWarnings("unchecked")
                    ArrayList<IsoChunkRegion> onLevel =
                            (ArrayList<IsoChunkRegion>) getChunkRegions.invoke(chunk, z);
                    int count = z == 0 ? regionsPerLevel : random.nextInt(regionsPerLevel + 1);
                    for (int r = 0; r < count; r++) {
                        IsoChunkRegion chunkRegion =
                                root.regionManager.allocIsoChunkRegion(chunk, z);
                        chunkRegion.setIsoWorldRegion(
                                random.nextInt(20) == 0 ? null : pool[random.nextInt(pool.length)]);
                        onLevel.add(chunkRegion);
                        added.add(chunkRegion);
                    }
                }
            }
        }
        return added;
    }

    /** Same walk as the vanilla method, deduped by identity independently of any list. */
    private static List<IsoWorldRegion> identityDedupeInWalkOrder(
            DataRoot root, int cellX, int cellY) throws Exception {
        Field cellMapField = DataRoot.class.getDeclaredField("cellMap");
        cellMapField.setAccessible(true);
        Method getAllChunks = DataCell.class.getDeclaredMethod("getAllChunks", List.class);
        getAllChunks.setAccessible(true);
        Method getChunkRegions = DataChunk.class.getDeclaredMethod("getChunkRegions", int.class);
        getChunkRegions.setAccessible(true);

        DataCell cell =
                (DataCell) ((Map<?, ?>) cellMapField.get(root)).get(IsoRegions.hash(cellX, cellY));
        List<DataChunk> chunks = new ArrayList<>();
        getAllChunks.invoke(cell, chunks);
        Map<IsoWorldRegion, Boolean> seen = new IdentityHashMap<>();
        List<IsoWorldRegion> out = new ArrayList<>();
        for (DataChunk chunk : chunks) {
            for (int z = 0; z < 32; z++) {
                for (Object o : (List<?>) getChunkRegions.invoke(chunk, z)) {
                    IsoWorldRegion region = ((IsoChunkRegion) o).getIsoWorldRegion();
                    if (seen.put(region, Boolean.TRUE) == null) {
                        out.add(region);
                    }
                }
            }
        }
        return out;
    }

    private static void assertSameElementsInOrder(
            List<IsoWorldRegion> expected, List<IsoWorldRegion> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": size");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), what + ": element " + i);
        }
    }
}
