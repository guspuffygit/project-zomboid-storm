package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.*;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;

/**
 * Proves {@link StormPlayerInfluenceGrid} answers exactly like the vanilla per-cell sweep, using
 * real {@link UdpConnection} instances (the same {@code isRelevantTo} the server runs) as both the
 * grid's input and the brute-force reference. The grid claims exactness by construction — every
 * candidate is verified with the vanilla predicate — so the property under test is that the
 * rasterized candidate ranges never miss an influenced cell.
 */
class StormPlayerInfluenceGridTest implements UnitTest {

    /** The vanilla predicate the warmer replaces: any cell corner relevant to any connection. */
    private static boolean bruteForceInfluenced(List<UdpConnection> conns, int wx, int wy) {
        int x1 = wx * 64;
        int y1 = wy * 64;
        int x2 = (wx + 1) * 64;
        int y2 = (wy + 1) * 64;
        for (UdpConnection c : conns) {
            if (c.isRelevantTo(x1, y1)
                    || c.isRelevantTo(x2, y1)
                    || c.isRelevantTo(x2, y2)
                    || c.isRelevantTo(x1, y2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The real constructor's field initializers build a {@code PacketsCache}, whose static-init
     * chain ({@code PacketTypes} → {@code AntiCheat}) needs live game state — so allocate without
     * running any constructor and hand-set the three fields {@code isRelevantTo} reads.
     */
    private static UdpConnection blankConnection() throws Exception {
        sun.reflect.ReflectionFactory rf = sun.reflect.ReflectionFactory.getReflectionFactory();
        java.lang.reflect.Constructor<?> ctor =
                rf.newConstructorForSerialization(
                        UdpConnection.class, Object.class.getDeclaredConstructor());
        UdpConnection c = (UdpConnection) ctor.newInstance();
        c.releventPos = new Vector3[4];
        c.connectArea = new Vector3[4];
        c.setRelevantRange((byte) 0);
        return c;
    }

    private static void assertGridMatchesBruteForce(
            List<UdpConnection> conns, int cellRange, String context) {
        StormPlayerInfluenceGrid grid = new StormPlayerInfluenceGrid();
        grid.rebuild(conns);
        for (int wx = -cellRange; wx <= cellRange; wx++) {
            for (int wy = -cellRange; wy <= cellRange; wy++) {
                assertEquals(
                        bruteForceInfluenced(conns, wx, wy),
                        grid.containsCell(wx, wy),
                        context + ": cell " + wx + "," + wy);
            }
        }
    }

    @Test
    void emptyConnectionsInfluenceNothing() throws Exception {
        StormPlayerInfluenceGrid grid = new StormPlayerInfluenceGrid();
        grid.rebuild(List.of());
        assertEquals(0, grid.size());
        assertFalse(grid.containsCell(0, 0));

        // A connection with all slots null (fresh, pre-position) influences nothing either.
        assertGridMatchesBruteForce(List.of(blankConnection()), 5, "all-null slots");
    }

    @Test
    void boundaryExactPositions() throws Exception {
        // Positions and ranges sitting exactly on cell-corner multiples of 64 — the closed
        // releventPos bound means corners at |d| == range are relevant; the grid must agree.
        UdpConnection c = blankConnection();
        c.setRelevantRange((byte) 8); // range = 64 tiles = exactly one cell
        c.releventPos[0] = new Vector3(128.0f, 128.0f, 0.0f); // exactly cell corner (2,2)
        assertGridMatchesBruteForce(List.of(c), 8, "corner-exact releventPos");

        UdpConnection c2 = blankConnection();
        c2.setRelevantRange((byte) 8);
        c2.releventPos[0] = new Vector3(127.999f, -64.001f, 0.0f);
        assertGridMatchesBruteForce(List.of(c2), 8, "epsilon-off releventPos");
    }

    @Test
    void connectAreaHalfOpenEdges() throws Exception {
        // connectArea's upper edge is exclusive (x < maxX) while releventPos is closed; odd
        // widths exercise vanilla's int division in `chunkMapWidth / 2`.
        for (int width : new int[] {1, 7, 8, 13, 25}) {
            UdpConnection c = blankConnection();
            c.connectArea[0] = new Vector3(10.5f, -3.25f, width);
            assertGridMatchesBruteForce(List.of(c), 30, "connectArea width " + width);
        }
    }

    @Test
    void zeroRelevantRangeAndNegativeCoordinates() throws Exception {
        UdpConnection c = blankConnection();
        c.setRelevantRange((byte) 0);
        c.releventPos[0] = new Vector3(-1000.0f, -1000.0f, 0.0f);
        assertGridMatchesBruteForce(List.of(c), 20, "zero range negative coords");
    }

    @Test
    void nanCoordinatesInfluenceNothingAndDoNotThrow() throws Exception {
        UdpConnection c = blankConnection();
        c.setRelevantRange((byte) 20);
        c.releventPos[0] = new Vector3(Float.NaN, 100.0f, 0.0f);
        c.releventPos[1] = new Vector3(100.0f, Float.NaN, 0.0f);
        // Vanilla's |NaN - x| <= r is always false, so NaN positions influence nothing; the grid
        // skips the non-finite primitive, which must land on the same answer.
        assertGridMatchesBruteForce(List.of(c), 10, "NaN releventPos");
    }

    @Test
    void nearInfluenceDilatesByChebyshevMargin() throws Exception {
        // Position exactly on cell corner (5,5) with range 1 (= 8 tiles, less than a cell):
        // the only relevant corner is (320,320), influencing exactly the 2x2 cell block
        // (4,4)-(5,5) that shares it. (A mid-cell position with a sub-cell range influences
        // nothing — the vanilla predicate only samples corners.)
        UdpConnection c = blankConnection();
        c.setRelevantRange((byte) 1);
        c.releventPos[0] = new Vector3(5 * 64, 5 * 64, 0.0f);
        StormPlayerInfluenceGrid grid = new StormPlayerInfluenceGrid();
        grid.rebuild(List.of(c));
        assertEquals(4, grid.size());
        assertTrue(grid.containsCell(4, 4));
        assertTrue(grid.containsCell(5, 5));
        assertFalse(grid.containsCell(6, 6));

        assertTrue(grid.nearInfluence(5, 5, 0));
        assertFalse(grid.nearInfluence(6, 6, 0));
        assertTrue(grid.nearInfluence(6, 6, 1));
        assertFalse(grid.nearInfluence(7, 7, 1));
        assertTrue(grid.nearInfluence(7, 7, 2));
        assertFalse(grid.nearInfluence(8, 8, 2));

        // The general property: nearInfluence(wx, wy, m) == any influenced cell within
        // Chebyshev distance m.
        for (int margin : new int[] {0, 1, 3}) {
            for (int wx = -2; wx <= 12; wx++) {
                for (int wy = -2; wy <= 12; wy++) {
                    boolean expected = false;
                    outer:
                    for (int dx = -margin; dx <= margin; dx++) {
                        for (int dy = -margin; dy <= margin; dy++) {
                            if (grid.containsCell(wx + dx, wy + dy)) {
                                expected = true;
                                break outer;
                            }
                        }
                    }
                    assertEquals(
                            expected,
                            grid.nearInfluence(wx, wy, margin),
                            "margin " + margin + " cell " + wx + "," + wy);
                }
            }
        }
    }

    @Test
    void fuzzAgainstBruteForce() throws Exception {
        Random rng = new Random(20260818);
        for (int trial = 0; trial < 30; trial++) {
            int connCount = 1 + rng.nextInt(6);
            List<UdpConnection> conns = new ArrayList<>();
            for (int i = 0; i < connCount; i++) {
                UdpConnection c = blankConnection();
                c.setRelevantRange((byte) rng.nextInt(21)); // typical live values are ~5-17
                for (int slot = 0; slot < 4; slot++) {
                    if (rng.nextInt(3) == 0) {
                        c.releventPos[slot] =
                                new Vector3(
                                        (rng.nextFloat() - 0.5f) * 3000.0f,
                                        (rng.nextFloat() - 0.5f) * 3000.0f,
                                        0.0f);
                    }
                    if (rng.nextInt(4) == 0) {
                        c.connectArea[slot] =
                                new Vector3(
                                        (rng.nextFloat() - 0.5f) * 350.0f,
                                        (rng.nextFloat() - 0.5f) * 350.0f,
                                        rng.nextInt(30));
                    }
                }
                conns.add(c);
            }
            // ±3000 tiles of position (±24 cells) plus max range/area stays well inside ±40 cells.
            assertGridMatchesBruteForce(conns, 40, "fuzz trial " + trial);
        }
    }
}
