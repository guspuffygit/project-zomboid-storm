package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-animal LOS tick stride controller's property resolution, live setter, and
 * round-robin distribution predicate.
 */
class AnimalLOSTickIntervalTest implements UnitTest {

    private String savedProperty;
    private int savedInterval;

    @BeforeEach
    void captureState() {
        savedProperty = System.getProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY);
        System.clearProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY);
        savedInterval = AnimalLOSTickInterval.getCurrentTickInterval();
        AnimalLOSTickInterval.setCurrentTickIntervalForTest(
                AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL);
    }

    @AfterEach
    void restoreState() {
        if (savedProperty == null) {
            System.clearProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY);
        } else {
            System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, savedProperty);
        }
        AnimalLOSTickInterval.setCurrentTickIntervalForTest(savedInterval);
    }

    // -------- resolveTickInterval() --------

    @Test
    void resolveReturnsDefaultWhenPropertyUnset() {
        assertEquals(
                AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveReturnsConfiguredValueWhenInRange() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "8");
        assertEquals(8, AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveTrimsWhitespace() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "  16  ");
        assertEquals(16, AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveClampsBelowMinimum() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "-5");
        assertEquals(
                AnimalLOSTickInterval.MIN_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveClampsAboveMaximum() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "9999");
        assertEquals(
                AnimalLOSTickInterval.MAX_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveFallsBackOnNonNumeric() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "fast");
        assertEquals(
                AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveFallsBackOnEmptyString() {
        System.setProperty(AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY, "");
        assertEquals(
                AnimalLOSTickInterval.DEFAULT_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    @Test
    void resolveAcceptsBoundaryValues() {
        System.setProperty(
                AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY,
                Integer.toString(AnimalLOSTickInterval.MIN_TICK_INTERVAL));
        assertEquals(
                AnimalLOSTickInterval.MIN_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());

        System.setProperty(
                AnimalLOSTickInterval.TICK_INTERVAL_PROPERTY,
                Integer.toString(AnimalLOSTickInterval.MAX_TICK_INTERVAL));
        assertEquals(
                AnimalLOSTickInterval.MAX_TICK_INTERVAL,
                AnimalLOSTickInterval.resolveTickInterval());
    }

    // -------- setTickInterval() --------

    @Test
    void setTickIntervalAppliesAndPersists() {
        int applied = AnimalLOSTickInterval.setTickInterval(10);
        assertEquals(10, applied);
        assertEquals(10, AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void setTickIntervalClampsBelowMinimum() {
        int applied = AnimalLOSTickInterval.setTickInterval(-3);
        assertEquals(AnimalLOSTickInterval.MIN_TICK_INTERVAL, applied);
        assertEquals(
                AnimalLOSTickInterval.MIN_TICK_INTERVAL,
                AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void setTickIntervalClampsAboveMaximum() {
        int applied = AnimalLOSTickInterval.setTickInterval(99_999);
        assertEquals(AnimalLOSTickInterval.MAX_TICK_INTERVAL, applied);
        assertEquals(
                AnimalLOSTickInterval.MAX_TICK_INTERVAL,
                AnimalLOSTickInterval.getCurrentTickInterval());
    }

    @Test
    void setTickIntervalAcceptsBoundaryValues() {
        assertEquals(
                AnimalLOSTickInterval.MIN_TICK_INTERVAL,
                AnimalLOSTickInterval.setTickInterval(AnimalLOSTickInterval.MIN_TICK_INTERVAL));
        assertEquals(
                AnimalLOSTickInterval.MAX_TICK_INTERVAL,
                AnimalLOSTickInterval.setTickInterval(AnimalLOSTickInterval.MAX_TICK_INTERVAL));
    }

    @Test
    void setTickIntervalZeroDisablesLOS() {
        AnimalLOSTickInterval.setTickInterval(0);
        for (int animalId : new int[] {-7, 0, 1, 99}) {
            for (long frame = 0; frame < 32; frame++) {
                assertFalse(
                        AnimalLOSTickInterval.shouldRunForAnimal(
                                AnimalLOSTickInterval.getCurrentTickInterval(), frame, animalId),
                        "stride=0 (disabled) must skip every animal on every frame");
            }
        }
    }

    // -------- shouldRunForAnimal() --------

    @Test
    void shouldRunForAnimalAlwaysTrueAtStrideOne() {
        for (int animalId = -3; animalId <= 5; animalId++) {
            for (long frame = 0; frame < 16; frame++) {
                assertTrue(
                        AnimalLOSTickInterval.shouldRunForAnimal(1, frame, animalId),
                        "stride=1 must always return true for animalId="
                                + animalId
                                + ", frame="
                                + frame);
            }
        }
    }

    @Test
    void shouldRunForAnimalAlwaysFalseAtStrideZero() {
        // Stride 0 disables LOS for every animal at every frame.
        for (int animalId = -3; animalId <= 5; animalId++) {
            for (long frame = 0; frame < 16; frame++) {
                assertFalse(
                        AnimalLOSTickInterval.shouldRunForAnimal(0, frame, animalId),
                        "stride=0 must always return false for animalId="
                                + animalId
                                + ", frame="
                                + frame);
            }
        }
    }

    @Test
    void shouldRunForAnimalAlwaysFalseAtNegativeStride() {
        // Defensive — negative strides behave the same as 0 (LOS disabled).
        assertFalse(AnimalLOSTickInterval.shouldRunForAnimal(-1, 7L, 42));
        assertFalse(AnimalLOSTickInterval.shouldRunForAnimal(-100, 0L, 0));
    }

    @Test
    void shouldRunForAnimalDistributesEvenlyAcrossStride() {
        // With stride=4, each animal should fire on exactly one of every four ticks.
        int stride = 4;
        for (int animalId : new int[] {0, 1, 2, 3, 7, 11, 100}) {
            int hits = 0;
            for (long frame = 0; frame < stride; frame++) {
                if (AnimalLOSTickInterval.shouldRunForAnimal(stride, frame, animalId)) {
                    hits++;
                }
            }
            assertEquals(
                    1, hits, "animalId=" + animalId + " must fire exactly once per stride window");
        }
    }

    @Test
    void shouldRunForAnimalSpreadsLoadAcrossFrames() {
        // Across one stride window, the union of firing animals should cover every frame at
        // least once (no frame is starved when the population is dense enough).
        int stride = 8;
        boolean[] frameHit = new boolean[stride];
        for (int animalId = 0; animalId < stride; animalId++) {
            for (long frame = 0; frame < stride; frame++) {
                if (AnimalLOSTickInterval.shouldRunForAnimal(stride, frame, animalId)) {
                    frameHit[(int) frame] = true;
                }
            }
        }
        for (int f = 0; f < stride; f++) {
            assertTrue(frameHit[f], "frame " + f + " must have at least one animal firing on it");
        }
    }

    @Test
    void shouldRunForAnimalHandlesNegativeAnimalIds() {
        // Math.floorMod must keep negative IDs in-bounds and consistent with positive IDs.
        int stride = 6;
        int negId = -5;
        int hits = 0;
        for (long frame = 0; frame < stride; frame++) {
            if (AnimalLOSTickInterval.shouldRunForAnimal(stride, frame, negId)) {
                hits++;
            }
        }
        assertEquals(1, hits, "negative animalId must still fire exactly once per stride window");
    }

    @Test
    void shouldRunForAnimalIsDeterministicAcrossWindows() {
        int stride = 5;
        int animalId = 17;
        for (long base = 0; base < stride * 4L; base += stride) {
            assertEquals(
                    AnimalLOSTickInterval.shouldRunForAnimal(stride, base, animalId),
                    AnimalLOSTickInterval.shouldRunForAnimal(stride, base + stride, animalId),
                    "predicate must be periodic with period=stride");
        }
    }

    @Test
    void shouldRunForAnimalSkipsOffWindowFrames() {
        // For stride=3, animalId=0 fires at frame 0, 3, 6, ... and not at 1, 2, 4, 5.
        int stride = 3;
        int animalId = 0;
        assertTrue(AnimalLOSTickInterval.shouldRunForAnimal(stride, 0L, animalId));
        assertFalse(AnimalLOSTickInterval.shouldRunForAnimal(stride, 1L, animalId));
        assertFalse(AnimalLOSTickInterval.shouldRunForAnimal(stride, 2L, animalId));
        assertTrue(AnimalLOSTickInterval.shouldRunForAnimal(stride, 3L, animalId));
        assertFalse(AnimalLOSTickInterval.shouldRunForAnimal(stride, 4L, animalId));
    }
}
