package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PopManCollisionGridTest implements UnitTest {

    private long nowMs = 10_000L;
    private boolean noSave;
    private final Map<String, byte[]> saved = new HashMap<>();
    private final Map<String, byte[]> shipped = new HashMap<>();
    private final PopManMetaRegistry registry = new PopManMetaRegistry();

    {
        registry.initGrid(-2, -2, 100, 100);
    }

    private final PopManChunkDataSource files =
            new PopManChunkDataSource() {
                @Override
                public byte[] readSaved(int cellX, int cellY) {
                    return saved.get(cellX + "," + cellY);
                }

                @Override
                public byte[] readShipped(String path) {
                    return shipped.get(path);
                }

                @Override
                public void writeSaved(int cellX, int cellY, byte[] data) {
                    saved.put(cellX + "," + cellY, data);
                }
            };

    private PopManCollisionGrid grid() {
        PopManCollisionGrid grid = new PopManCollisionGrid(() -> nowMs);
        grid.init(files, registry, () -> noSave, -2, -2, 100, 100);
        return grid;
    }

    @Test
    void anUndescribedCellIsUniformZeroWithoutData() {
        PopManCollisionGrid grid = grid();
        assertEquals(0, grid.squareFlags(300, 300));
        PopManCollisionCell cell = grid.cellAt(1, 1);
        assertTrue(cell.loaded);
        assertFalse(cell.hasData);
    }

    @Test
    void outsideTheWorldIsSolid() {
        PopManCollisionGrid grid = grid();
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(-2 * 256 - 1, 0));
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(0, 98 * 256));
        assertEquals(0, grid.squareFlags(-2 * 256, 0), "first square inside");
        assertEquals(0, grid.squareFlags(0, 98 * 256 - 1), "last square inside");
    }

    @Test
    void theSavedFileWinsOverShippedOnes() {
        saved.put("1,1", PopManChunkDataTest.file(1, 3, null));
        shipped.put("a", PopManChunkDataTest.file(1, 1, null));
        registry.initCell(1, 1, "a");
        PopManCollisionGrid grid = grid();
        assertEquals(PopManMap.BIT_WATER, grid.squareFlags(256 + 5, 256 + 5));
        assertTrue(grid.cellAt(1, 1).hasData);
    }

    @Test
    void oneShippedFileIsReadAsIs() {
        shipped.put("a", PopManChunkDataTest.file(1, 4, null));
        registry.initCell(2, 2, "a");
        PopManCollisionGrid grid = grid();
        assertEquals(PopManMap.BIT_ROOM, grid.squareFlags(2 * 256, 2 * 256));
        assertTrue(grid.cellAt(2, 2).hasData);
    }

    @Test
    void aMissingSingleShippedFileLeavesNoData() {
        registry.initCell(2, 2, "missing");
        PopManCollisionGrid grid = grid();
        assertEquals(0, grid.squareFlags(2 * 256, 2 * 256));
        assertFalse(grid.cellAt(2, 2).hasData);
    }

    @Test
    void severalShippedFilesMergeFirstMapWinsAndAlwaysCountAsData() {
        shipped.put("a", PopManChunkDataTest.file(1, 1, null));
        shipped.put("b", PopManChunkDataTest.file(1, 3, null));
        registry.initCell(3, 3, "a");
        registry.initCell(3, 3, "b");
        PopManCollisionGrid grid = grid();
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(3 * 256 + 9, 3 * 256 + 9));
        assertTrue(grid.cellAt(3, 3).hasData);

        registry.initCell(4, 4, "missing1");
        registry.initCell(4, 4, "missing2");
        assertEquals(0x20, grid.squareFlags(4 * 256, 4 * 256), "scratch default");
        assertTrue(grid.cellAt(4, 4).hasData, "multi-file cells always have data");
    }

    @Test
    void aFileThatFailsToParseLeavesTheCellWithoutData() {
        saved.put("2,2", new byte[] {0, 9});
        PopManCollisionGrid grid = grid();
        assertEquals(0, grid.squareFlags(2 * 256, 2 * 256));
        assertFalse(grid.cellAt(2, 2).hasData);
    }

    @Test
    void chunkUpdatesDirtyTheCellOnlyWhenSomethingChanges() {
        PopManCollisionGrid grid = grid();
        byte[] squares = new byte[64];
        squares[3 + 2 * 8] = PopManMap.BIT_SOLID;

        grid.applyChunk(4, 5, squares);
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(4 * 8 + 3, 5 * 8 + 2));
        assertEquals(0, grid.squareFlags(4 * 8 + 4, 5 * 8 + 2), "rest of the chunk as sent");
        PopManCollisionCell cell = grid.cellAt(0, 0);
        assertTrue(cell.dirty);

        cell.dirty = false;
        grid.applyChunk(6, 5, new byte[64]);
        assertFalse(cell.dirty, "uniform 0 onto uniform 0 is not a change");

        grid.applyChunk(4, 5, squares);
        assertTrue(cell.dirty, "an explicit chunk is always a change");
    }

    @Test
    void squareUpdatesDirtyOnlyOnARealChange() {
        PopManCollisionGrid grid = grid();
        PopManCollisionCell cell = grid.cellAt(0, 0);

        grid.applySquare(10, 10, 0);
        assertFalse(cell.dirty, "same category, nothing written");

        grid.applySquare(10, 10, PopManMap.BIT_ROOM);
        assertTrue(cell.dirty);
        assertEquals(PopManMap.BIT_ROOM, grid.squareFlags(10, 10));
        assertEquals(0, grid.squareFlags(11, 10), "neighbour kept");
        assertTrue(cell.isExplicit(PopManCollisionCell.chunkIndex(10, 10)));

        grid.applySquare(10, 10, 0);
        assertFalse(cell.isExplicit(PopManCollisionCell.chunkIndex(10, 10)), "collapsed again");
    }

    @Test
    void negativeCoordinatesLandInTheRightCellAndSquare() {
        PopManCollisionGrid grid = grid();
        grid.applySquare(-1, -1, PopManMap.BIT_SOLID);
        assertTrue(grid.isResident(-1, -1));
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(-1, -1));
        assertEquals(0, grid.squareFlags(-2, -1));
    }

    @Test
    void updatesForCellsOutsideTheWorldAreDropped() {
        PopManCollisionGrid grid = grid();
        grid.applyChunk(-3 * 32, 0, new byte[64]);
        assertEquals(0, grid.residentCells());
    }

    @Test
    void touchingBeyondTwentyResidentUnloadsCellsIdleForASecond() {
        PopManCollisionGrid grid = grid();
        for (int i = 0; i < PopManCollisionGrid.RESIDENT_TARGET; i++) {
            grid.squareFlags(i * 256, 0);
        }
        assertEquals(PopManCollisionGrid.RESIDENT_TARGET, grid.residentCells());

        grid.squareFlags(0, 256);
        assertEquals(PopManCollisionGrid.RESIDENT_TARGET + 1, grid.residentCells(), "none idle");

        nowMs += PopManCollisionGrid.TOUCH_IDLE_MS + 1;
        grid.squareFlags(0, 512);
        assertEquals(PopManCollisionGrid.RESIDENT_TARGET + 1, grid.residentCells());
        assertFalse(grid.isResident(0, 0), "the oldest idle cell went");
        assertTrue(grid.isResident(0, 2));
    }

    @Test
    void theUpdateEvictionUnloadsOneCellIdleForFiveSeconds() {
        PopManCollisionGrid grid = grid();
        grid.squareFlags(0, 0);
        grid.squareFlags(256, 0);
        nowMs += PopManCollisionGrid.UPDATE_IDLE_MS + 1;
        grid.evictOneIdle();
        assertEquals(1, grid.residentCells());
        assertFalse(grid.isResident(0, 0));
        assertTrue(grid.isResident(1, 0));
    }

    @Test
    void unloadingADirtyCellWritesItWhenItHasData() {
        saved.put("0,0", PopManChunkDataTest.file(1, 1, null));
        PopManCollisionGrid grid = grid();
        grid.applySquare(0, 0, PopManMap.BIT_WATER);
        grid.squareFlags(256, 0);
        nowMs += PopManCollisionGrid.UPDATE_IDLE_MS + 1;
        grid.evictOneIdle();

        byte[] written = saved.get("0,0");
        assertNotNull(written);
        assertEquals(2, written[2], "chunk 0 is explicit now");
        assertEquals(PopManMap.BIT_WATER, written[3]);
    }

    @Test
    void cellsWithoutDataAreNeverWrittenAndNoSaveBlocksWriting() {
        PopManCollisionGrid grid = grid();
        grid.applySquare(0, 0, PopManMap.BIT_WATER);
        grid.save();
        assertNull(saved.get("0,0"), "no data, no file");
        assertFalse(grid.cellAt(0, 0).dirty, "clean regardless");

        saved.put("1,0", PopManChunkDataTest.file(1, 0, null));
        noSave = true;
        grid.applySquare(256, 0, PopManMap.BIT_WATER);
        grid.save();
        assertEquals(2 + 1024, saved.get("1,0").length, "untouched under noSave");
    }

    @Test
    void stopDiscardsUnsavedChangesAndDropsEveryCell() {
        saved.put("0,0", PopManChunkDataTest.file(1, 0, null));
        PopManCollisionGrid grid = grid();
        grid.applySquare(0, 0, PopManMap.BIT_WATER);
        grid.stop();
        assertEquals(2 + 1024, saved.get("0,0").length);
        assertEquals(0, grid.residentCells());
        assertEquals(PopManMap.BIT_SOLID, grid.squareFlags(0, 0), "no world any more");
    }

    @Test
    void densityComesFromTheRegistry() {
        registry.initChunk(7, 0, 3, 0, 200);
        PopManCollisionGrid grid = grid();
        assertEquals(200, grid.densityByte(7 * 32 + 3, 0));
        assertEquals(0, grid.densityByte(7 * 32 + 4, 0), "same cell, no intensity");
        assertEquals(PopManPopulation.NO_DENSITY_DATA, grid.densityByte(0, 0), "no cell");
    }

    @Test
    void chunkStateReportsExplicitChunksForTheOverlay() {
        PopManCollisionGrid grid = grid();
        byte[] squares = new byte[64];
        squares[1] = PopManMap.BIT_SOLID;
        grid.applyChunk(5, 5, squares);
        assertEquals(PopManCollisionCell.STATE_EXPLICIT, grid.chunkState(5, 5));
        assertEquals(0, grid.chunkState(6, 5));
        assertEquals(-1, grid.chunkState(-3 * 32, 0));
    }
}
