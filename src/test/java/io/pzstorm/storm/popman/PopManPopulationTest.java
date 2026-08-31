package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.pzstorm.storm.UnitTest;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.Test;

class PopManPopulationTest implements UnitTest {

    private static final double DAY = 24.0;

    private static PopManConfig defaults() {
        return new PopManConfig();
    }

    @Test
    void theCellTargetRampsFromStartToPeakAndThenFlattens() {
        PopManConfig config = defaults();

        assertEquals(100, PopManPopulation.desiredCellPopulation(config, 100, 0.0));
        assertEquals(125, PopManPopulation.desiredCellPopulation(config, 100, 14 * DAY));
        assertEquals(150, PopManPopulation.desiredCellPopulation(config, 100, 28 * DAY));
        assertEquals(150, PopManPopulation.desiredCellPopulation(config, 100, 400 * DAY));
    }

    @Test
    void theChunkTargetCeilsBeforeInterpolating() {
        PopManConfig config = defaults();

        assertEquals(1, PopManPopulation.desiredChunkPopulation(config, 1, 0.0));
        assertEquals(2, PopManPopulation.desiredChunkPopulation(config, 1, 28 * DAY));
    }

    /** The two formulas round differently on purpose, so they must not share a helper. */
    @Test
    void theCellAndChunkTargetsDisagree() {
        PopManConfig config = defaults();
        double atPeak = 28 * DAY;

        assertEquals(4, PopManPopulation.desiredCellPopulation(config, 3, atPeak));
        assertEquals(5, PopManPopulation.desiredChunkPopulation(config, 3, atPeak));
        assertNotEquals(
                PopManPopulation.desiredCellPopulation(config, 3, atPeak),
                PopManPopulation.desiredChunkPopulation(config, 3, atPeak));
    }

    @Test
    void densityRescalesTheMapByteIntoZombiesPerChunk() {
        PopManConfig config = defaults();

        assertEquals(100.0F, PopManPopulation.chunkDensity(config, 100, false));
        assertEquals(0.0F, PopManPopulation.chunkDensity(config, 0, false));
        assertEquals(
                0.0F,
                PopManPopulation.chunkDensity(config, PopManPopulation.NO_DENSITY_DATA, false),
                "a chunk outside the density image wants nobody");
    }

    /** The sandbox value goes into the 0..255 byte's slot, so it is scaled as if it were one. */
    @Test
    void uniformModeSubstitutesTheSandboxValueForTheDensityByte() {
        PopManConfig config = defaults();

        assertEquals(0.2F, PopManPopulation.chunkDensity(config, 200, true), 1.0e-6F);
    }

    @Test
    void densityHonoursTheMinAndMaxWindow() {
        PopManConfig config = defaults();
        config.setFloat("MinZombiesPerChunk", 2.0F);
        config.setFloat("MaxZombiesPerChunk", 12.0F);

        assertEquals(
                2.0F + (255.0F / 255.0F) * 10.0F,
                PopManPopulation.chunkDensity(config, 255, false));
        assertEquals(
                2.0F + (51.0F / 255.0F) * 10.0F,
                PopManPopulation.chunkDensity(config, 51, false),
                1.0e-5F);
    }

    @Test
    void aDensityOfTwoOrMoreTruncatesWithoutRolling() {
        assertEquals(2, PopManPopulation.stochasticBasePop(2.9F, failIfRolled()));
        assertEquals(7, PopManPopulation.stochasticBasePop(7.0F, failIfRolled()));
    }

    /**
     * A density in {@code [1, 2)} is a coin flip between one and zero — not between one and two.
     */
    @Test
    void aDensityJustAboveOneIsACoinFlipDownwards() {
        assertEquals(1, PopManPopulation.stochasticBasePop(1.9F, () -> 49));
        assertEquals(0, PopManPopulation.stochasticBasePop(1.9F, () -> 50));
        assertEquals(1, PopManPopulation.stochasticBasePop(1.0F, () -> 0));
    }

    @Test
    void aFractionalDensityIsABernoulliDraw() {
        assertEquals(1, PopManPopulation.stochasticBasePop(0.25F, () -> 24));
        assertEquals(0, PopManPopulation.stochasticBasePop(0.25F, () -> 25));
        assertEquals(
                0, PopManPopulation.stochasticBasePop(0.0F, () -> 0), "zero density never spawns");
    }

    private static IntSupplier failIfRolled() {
        return () -> {
            throw new AssertionError("a whole density must not consume a random draw");
        };
    }
}
