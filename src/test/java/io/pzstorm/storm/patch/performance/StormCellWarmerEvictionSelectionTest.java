package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;

/**
 * Distance-aware eviction victim selection ({@code StormCellWarmer.selectEvictionVictim}): among
 * LRU-ordered candidates, prefer the first cell with no player influence within the Chebyshev
 * margin; fall back to the plain LRU head when every candidate is near.
 */
class StormCellWarmerEvictionSelectionTest implements UnitTest {

    private static final int MARGIN = 2;

    /**
     * Grid whose influenced set is exactly the 2x2 cell block (4,4)-(5,5): a sub-cell-range
     * connection positioned exactly on cell corner (5,5) — see
     * StormPlayerInfluenceGridTest#nearInfluenceDilatesByChebyshevMargin.
     */
    private static StormPlayerInfluenceGrid gridInfluencingBlockAt45() throws Exception {
        sun.reflect.ReflectionFactory rf = sun.reflect.ReflectionFactory.getReflectionFactory();
        java.lang.reflect.Constructor<?> ctor =
                rf.newConstructorForSerialization(
                        UdpConnection.class, Object.class.getDeclaredConstructor());
        UdpConnection c = (UdpConnection) ctor.newInstance();
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.setRelevantRange((byte) 1);
        c.releventPos[0] = new Vector3(5 * 64, 5 * 64, 0.0f);
        StormPlayerInfluenceGrid grid = new StormPlayerInfluenceGrid();
        grid.rebuild(List.of(c));
        assertEquals(4, grid.size());
        return grid;
    }

    @Test
    void farHeadIsEvictedFirst() throws Exception {
        StormPlayerInfluenceGrid grid = gridInfluencingBlockAt45();
        // Head at (100,100) is far from all influence — evict it, near-aware or not.
        int[] wxs = {100, 5, 6};
        int[] wys = {100, 5, 6};
        assertEquals(0, StormCellWarmer.selectEvictionVictim(wxs, wys, grid, MARGIN));
    }

    @Test
    void nearHeadIsSkippedForFirstFarCandidate() throws Exception {
        StormPlayerInfluenceGrid grid = gridInfluencingBlockAt45();
        // (6,6) and (7,7) are within margin 2 of the influenced block; (20,20) and (30,30) are
        // not — the first far candidate wins, not the farthest.
        int[] wxs = {6, 7, 20, 30};
        int[] wys = {6, 7, 20, 30};
        assertEquals(2, StormCellWarmer.selectEvictionVictim(wxs, wys, grid, MARGIN));
    }

    @Test
    void allNearFallsBackToLruHead() throws Exception {
        StormPlayerInfluenceGrid grid = gridInfluencingBlockAt45();
        int[] wxs = {4, 5, 6, 7};
        int[] wys = {4, 5, 6, 7};
        assertEquals(0, StormCellWarmer.selectEvictionVictim(wxs, wys, grid, MARGIN));
    }

    @Test
    void marginBoundaryIsChebyshev() throws Exception {
        StormPlayerInfluenceGrid grid = gridInfluencingBlockAt45();
        // (7,7) is Chebyshev distance 2 from influenced (5,5) — near at margin 2, so skipped;
        // (8,8) is distance 3 — far, so selected.
        assertEquals(
                1,
                StormCellWarmer.selectEvictionVictim(
                        new int[] {7, 8}, new int[] {7, 8}, grid, MARGIN));
        // At margin 1 the same head (7,7) is already far.
        assertEquals(
                0,
                StormCellWarmer.selectEvictionVictim(new int[] {7, 8}, new int[] {7, 8}, grid, 1));
    }

    @Test
    void emptyCandidatesFallBackToHead() throws Exception {
        StormPlayerInfluenceGrid grid = gridInfluencingBlockAt45();
        assertEquals(0, StormCellWarmer.selectEvictionVictim(new int[0], new int[0], grid, MARGIN));
    }

    @Test
    void emptyGridEvictsHeadImmediately() {
        // No influence anywhere (nobody online): everything is far, head wins.
        StormPlayerInfluenceGrid grid = new StormPlayerInfluenceGrid();
        grid.rebuild(List.of());
        assertEquals(
                0,
                StormCellWarmer.selectEvictionVictim(
                        new int[] {6, 20}, new int[] {6, 20}, grid, MARGIN));
    }
}
