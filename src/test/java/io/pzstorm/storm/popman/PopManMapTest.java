package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManMapTest implements UnitTest {

    private final FlagWorld world = new FlagWorld();
    private final PopManMap map = new PopManMap(world);

    @Test
    void onlyTheSquareBeingEnteredIsTestedAgainstTheBlockMask() {
        world.set(5, 5, PopManMap.BIT_SOLID);

        assertTrue(map.isMoveBlocked(4, 5, 5, 5), "walking into solid is blocked");
        assertFalse(map.isMoveBlocked(5, 5, 4, 5), "but walking back out of it is not");
    }

    @Test
    void waterAndRoomsBlockJustAsSolidDoes() {
        world.set(5, 5, PopManMap.BIT_WATER);
        world.set(6, 5, PopManMap.BIT_ROOM);

        assertTrue(map.isSquareBlocked(5, 5));
        assertTrue(map.isSquareBlocked(6, 5));
        assertFalse(map.isSquareBlocked(7, 5));
    }

    @Test
    void wallBitsBelongToTheSquareTheyAreDrawnOn() {
        world.set(5, 5, PopManMap.BIT_WALL_N | PopManMap.BIT_WALL_W);

        assertTrue(map.isMoveBlocked(5, 5, 5, 4), "leaving north over our own north wall");
        assertTrue(map.isMoveBlocked(4, 5, 5, 5), "entering east over the same square's west wall");

        assertFalse(
                map.isMoveBlocked(5, 5, 6, 5), "the east neighbour has no west wall of its own");
        assertFalse(map.isMoveBlocked(5, 5, 5, 6), "nor the south neighbour a north wall");
    }

    @Test
    void aWallStopsTravelInBothDirections() {
        world.set(5, 5, PopManMap.BIT_WALL_N);

        assertTrue(map.isMoveBlocked(5, 5, 5, 4));
        assertTrue(map.isMoveBlocked(5, 4, 5, 5));
    }

    @Test
    void aZeroLengthStepAsksWhetherTheSquareItselfIsBlocked() {
        world.set(5, 5, PopManMap.BIT_SOLID);

        assertTrue(map.isMoveBlocked(5, 5, 5, 5));
        assertFalse(map.isMoveBlocked(6, 6, 6, 6));
    }

    @Test
    void aDiagonalCannotCutACorner() {
        world.set(5, 4, PopManMap.BIT_SOLID);
        world.set(4, 5, PopManMap.BIT_SOLID);

        assertFalse(map.isSquareBlocked(5, 5), "the destination itself is clear");
        assertTrue(map.isMoveBlocked(4, 4, 5, 5), "yet the diagonal is refused");
    }

    @Test
    void oneBlockedCornerIsEnoughToRefuseADiagonal() {
        world.set(5, 4, PopManMap.BIT_SOLID);

        assertTrue(map.isMoveBlocked(4, 4, 5, 5));
    }

    @Test
    void anOpenDiagonalIsAllowed() {
        assertFalse(map.isMoveBlocked(4, 4, 5, 5));
    }

    @Test
    void aChunkCountsAsBlockedOnlyWhenEverySquareIs() {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                world.set(16 + x, 24 + y, PopManMap.BIT_SOLID);
            }
        }
        assertTrue(map.isChunkBlocked(2, 3));

        world.set(20, 27, 0);
        assertTrue(map.isChunkBlocked(2, 3), "merging zero flags cannot clear a bit");

        assertFalse(map.isChunkBlocked(2, 4), "an untouched chunk is open");
    }

    @Test
    void aOneTileRoomIsSealed() {
        world.box(5, 5, 5, 5);

        assertTrue(map.isSealed(5, 5));
        assertFalse(map.isValidSpawnSquare(5, 5, 0));
    }

    @Test
    void aSealedTileIsCutOffFromBothSidesOfEveryBoundary() {
        world.box(5, 5, 5, 5);

        assertTrue(map.isMoveBlocked(5, 5, 4, 5), "west, over our own west wall");
        assertTrue(map.isMoveBlocked(6, 5, 5, 5), "and inwards from the east neighbour");
    }

    @Test
    void aTwoTileRoomIsRejectedBecauseItsExitsLeadToEachOther() {
        world.box(5, 5, 6, 5);

        assertFalse(map.isSealed(5, 5), "each half can reach the other");
        assertFalse(map.isValidSpawnSquare(5, 5, 0));
        assertFalse(map.isValidSpawnSquare(6, 5, 0), "and the pocket is rejected from either end");
    }

    @Test
    void aThreeTileDeadEndIsAcceptableGround() {
        world.box(5, 5, 7, 5);

        assertTrue(map.isValidSpawnSquare(5, 5, 0));
        assertTrue(map.isValidSpawnSquare(6, 5, 0));
        assertTrue(map.isValidSpawnSquare(7, 5, 0));
    }

    @Test
    void aVerticalTwoTilePocketIsRejectedToo() {
        world.box(5, 5, 5, 6);

        assertFalse(map.isValidSpawnSquare(5, 5, 0));
        assertFalse(map.isValidSpawnSquare(5, 6, 0));
    }

    @Test
    void openGroundIsAlwaysValid() {
        assertTrue(map.isValidSpawnSquare(0, 0, 0));
        assertTrue(map.isValidSpawnSquare(-300, -300, 0));
    }

    @Test
    void aBlockedSquareIsNeverValidEvenInTheOpen() {
        world.set(5, 5, PopManMap.BIT_ROOM);

        assertFalse(map.isValidSpawnSquare(5, 5, 0), "indoors counts as blocked");
    }

    @Test
    void theDistributionOptionIsWhatSelectsUniformDensity() {
        assertFalse(map.isUniformDensityMode());

        map.gameState().setInt("SandboxOptions.Distribution", PopManGameState.DISTRIBUTION_UNIFORM);
        assertTrue(map.isUniformDensityMode());
    }

    @Test
    void lastStandIsNotTheSameSwitchAsDisabledZombies() {
        map.gameState().setBoolean("Core.bLastStand", true);
        assertFalse(map.areZombiesDisabled(), "Last Stand is a game mode, not the sandbox option");

        map.gameState().setBoolean("World.ZombiesDisabled", true);
        assertTrue(map.areZombiesDisabled());
    }

    @Test
    void anUnknownGameStateKeyIsRejectedAtTheCallSite() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> map.gameState().setBoolean("Core.nope", true));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> map.gameState().setInt("Sandbox.nope", 1));
    }

    @Test
    void worldBoundsAreRememberedInCells() {
        map.setWorldBounds(-10, -20, 30, 40);

        assertEquals(-10, map.minCellX());
        assertEquals(-20, map.minCellY());
        assertEquals(30, map.widthCells());
        assertEquals(40, map.heightCells());
    }

    @Test
    void worldBoundsAreHeldInCellsAndTestedInSquares() {
        map.setWorldBounds(0, 0, 2, 2);

        assertFalse(map.isOutsideWorld(0, 0));
        assertFalse(map.isOutsideWorld(511, 511), "the last square of the last cell is inside");
        assertTrue(map.isOutsideWorld(512, 0), "the upper edge is exclusive");
        assertTrue(map.isOutsideWorld(-1, 0), "the lower edge is inclusive");
    }

    @Test
    void aWorldStartingWestOfTheOriginHasNegativeInBoundsSquares() {
        map.setWorldBounds(-1, -1, 2, 2);

        assertFalse(map.isOutsideWorld(-256, -256));
        assertTrue(map.isOutsideWorld(-257, -256));
        assertFalse(map.isOutsideWorld(255, 255));
        assertTrue(map.isOutsideWorld(256, 0));
    }

    @Test
    void anUnobstructedLineIsClear() {
        assertTrue(map.isLineClear(0, 0, 5, 0, 10));
        assertTrue(map.isLineClear(0, 0, 5, 5, 10));
        assertTrue(map.isLineClear(3, 3, 3, 3, 10), "a line to where you already are");
    }

    @Test
    void anythingOnTheLineBlocksIt() {
        world.set(3, 0, PopManMap.BIT_SOLID);
        assertFalse(map.isLineClear(0, 0, 5, 0, 10));

        world.set(3, 3, PopManMap.BIT_SOLID);
        assertFalse(map.isLineClear(0, 0, 5, 5, 10));
    }

    @Test
    void aWallOffTheLineDoesNotBlockIt() {
        world.set(3, 4, PopManMap.BIT_SOLID);

        assertTrue(map.isLineClear(0, 0, 8, 0, 10));
    }

    /** The budget is a cost ceiling, so the far half of a long line is simply never examined. */
    @Test
    void runningOutOfStepsCountsAsClearRatherThanBlocked() {
        world.set(15, 0, PopManMap.BIT_SOLID);

        assertTrue(map.isLineClear(0, 0, 20, 0, 10), "the wall is past the tenth step");
        assertFalse(map.isLineClear(0, 0, 20, 0, 20), "and is found once the budget reaches it");
    }

    @Test
    void aZeroBudgetExaminesNothingAtAll() {
        world.set(1, 0, PopManMap.BIT_SOLID);

        assertTrue(map.isLineClear(0, 0, 5, 0, 0));
    }

    @Test
    void aDedicatedServerAsksItsOwnCellsWhereTheRimIs() {
        map.loadedAreas().set(new int[] {0, 0, 2, 2}, 1);
        map.serverCells().set(new int[] {10, 10, 2, 2}, 1);

        assertTrue(map.isOnLoadedPerimeter(0, 0));
        assertFalse(map.isOnLoadedPerimeter(80, 80));

        map.setServer(true);

        assertFalse(map.isOnLoadedPerimeter(0, 0), "the client's areas are meaningless here");
        assertTrue(map.isOnLoadedPerimeter(80, 80));
    }
}
