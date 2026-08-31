package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PopManFloodFillTest implements UnitTest {

    private static Set<Long> collect(PopManFloodFill fill) {
        Set<Long> squares = new HashSet<>();
        for (int i = 0; i < fill.resultCount(); i++) {
            squares.add(((long) fill.resultX(i) << 32) | (fill.resultY(i) & 0xFFFFFFFFL));
        }
        return squares;
    }

    @Test
    void anEmptyWindowIsFilledCompletelyAndExactlyOnce() {
        PopManFloodFill fill = new PopManFloodFill(8);
        ScriptedWorld world = new ScriptedWorld();

        fill.run(3, 4, 0, 0, world);

        assertEquals(64, fill.resultCount());
        assertEquals(64, collect(fill).size(), "no square may be reported twice");
    }

    @Test
    void theWindowIsAHardClip() {
        PopManFloodFill fill = new PopManFloodFill(8);
        ScriptedWorld world = new ScriptedWorld();

        fill.run(100, 100, 96, 96, world);

        for (int i = 0; i < fill.resultCount(); i++) {
            assertTrue(fill.resultX(i) >= 96 && fill.resultX(i) < 104);
            assertTrue(fill.resultY(i) >= 96 && fill.resultY(i) < 104);
        }
        assertEquals(64, fill.resultCount());
    }

    @Test
    void aWallKeepsTheFarSideOut() {
        PopManFloodFill fill = new PopManFloodFill(8);
        ScriptedWorld world = new ScriptedWorld().blockColumn(4, 0, 7);

        fill.run(0, 0, 0, 0, world);

        assertEquals(32, fill.resultCount(), "four open columns of eight");
        for (int i = 0; i < fill.resultCount(); i++) {
            assertTrue(fill.resultX(i) < 4);
        }
    }

    @Test
    void aGapInTheWallIsEnoughToReachTheFarSide() {
        PopManFloodFill fill = new PopManFloodFill(8);
        ScriptedWorld world = new ScriptedWorld().blockColumn(4, 0, 5);

        fill.run(0, 0, 0, 0, world);

        assertEquals(64 - 6, fill.resultCount(), "everything but the six wall squares");
        assertTrue(collect(fill).contains(((long) 7 << 32) | 0L));
    }

    /**
     * The premask is the whole safety property of repopulation: a batch can never land on a square
     * a player is streaming in, however open the map around it is.
     */
    @Test
    void theRepopulationWindowFencesOffLoadedAreas() {
        PopManRepopulation.Window window = new PopManRepopulation.Window();
        ScriptedWorld world = new ScriptedWorld().loadRect(10, 10, 6, 6);

        window.run(0, 0, 0, 0, world);

        Set<Long> squares = collect(window);
        assertEquals(40 * 40 - 36, squares.size());
        assertFalse(squares.contains(((long) 12 << 32) | 12L));
        assertTrue(squares.contains(((long) 9 << 32) | 12L));
    }

    /**
     * The start square is emitted before anything is tested, so one zombie of a batch can still
     * land on the path's endpoint even though a player is standing on it. Vanilla behaviour, kept
     * for parity.
     */
    @Test
    void theStartSquareEscapesTheFence() {
        PopManRepopulation.Window window = new PopManRepopulation.Window();
        ScriptedWorld world = new ScriptedWorld().loadRect(0, 0, 40, 40);

        window.run(20, 20, 0, 0, world);

        assertEquals(1, window.resultCount());
        assertEquals(20, window.resultX(0));
        assertEquals(20, window.resultY(0));
    }
}
