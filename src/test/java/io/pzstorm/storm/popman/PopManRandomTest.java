package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManRandomTest implements UnitTest {

    /**
     * The reference MT19937 output for the canonical seed. If this drifts, every spawn placement
     * drifts with it, and nothing else in the suite would notice.
     */
    @Test
    void matchesTheReferenceMersenneTwisterStream() {
        PopManRandom random = new PopManRandom(5489);

        assertEquals(-795755684, random.nextBits());
        assertEquals(581869302, random.nextBits());
        assertEquals(-404620562, random.nextBits());
        assertEquals(-708632711, random.nextBits());
        assertEquals(545404204, random.nextBits());
    }

    @Test
    void reseedingRewindsTheStream() {
        PopManRandom random = new PopManRandom(1234);
        int first = random.nextBits();
        random.nextBits();

        random.setSeed(1234);
        assertEquals(first, random.nextBits());
    }

    @Test
    void theStreamSurvivesTheFirstTwist() {
        PopManRandom random = new PopManRandom(5489);
        for (int i = 0; i < 623; i++) {
            random.nextBits();
        }
        int last = random.nextBits();
        int firstOfSecondBlock = random.nextBits();

        assertTrue(last != firstOfSecondBlock, "the 625th draw must come from a fresh block");
    }

    @Test
    void aNonPositiveBoundComesBackUnchanged() {
        PopManRandom random = new PopManRandom(7);

        assertEquals(0, random.nextInt(0));
        assertEquals(-4, random.nextInt(-4));
    }

    @Test
    void aBoundOfOneIsAlwaysZero() {
        PopManRandom random = new PopManRandom(7);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, random.nextInt(1));
        }
    }

    @Test
    void everyDrawLandsInsideTheBound() {
        PopManRandom random = new PopManRandom(99);
        for (int i = 0; i < 20_000; i++) {
            int value = random.nextInt(7);
            assertTrue(value >= 0 && value < 7, "out of range: " + value);
        }
    }

    @Test
    void theWholeBoundIsReachable() {
        PopManRandom random = new PopManRandom(99);
        boolean[] seen = new boolean[8];
        for (int i = 0; i < 5_000; i++) {
            seen[random.nextInt(8)] = true;
        }
        for (int i = 0; i < 8; i++) {
            assertTrue(seen[i], "never drew " + i);
        }
    }

    @Test
    void rangesAreHalfOpenAndOrderIndependent() {
        PopManRandom random = new PopManRandom(3);
        boolean sawLow = false;
        boolean sawHigh = false;
        for (int i = 0; i < 2_000; i++) {
            int value = random.nextRange(10, 13);
            assertTrue(value >= 10 && value < 13, "out of range: " + value);
            sawLow |= value == 10;
            sawHigh |= value == 12;
        }
        assertTrue(sawLow && sawHigh, "both ends of the half-open range must be reachable");

        for (int i = 0; i < 200; i++) {
            int value = random.nextRange(13, 10);
            assertTrue(value >= 10 && value < 13, "reversed bounds mean the same range");
        }
    }

    @Test
    void anEmptyRangeIsItsOwnLowerBound() {
        PopManRandom random = new PopManRandom(3);

        assertEquals(42, random.nextRange(42, 42));
    }

    @Test
    void unitFloatsStayBelowOne() {
        PopManRandom random = new PopManRandom(11);
        for (int i = 0; i < 20_000; i++) {
            float value = random.nextUnitFloat();
            assertTrue(value >= 0.0F && value < 1.0F, "out of range: " + value);
        }
    }

    @Test
    void floatRangesSpanTheRequestedInterval() {
        PopManRandom random = new PopManRandom(11);
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < 20_000; i++) {
            float value = random.nextFloat(-2.0F, 3.0F);
            assertTrue(value >= -2.0F && value < 3.0F, "out of range: " + value);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertTrue(min < -1.9F && max > 2.9F, "the interval should be covered");
    }
}
