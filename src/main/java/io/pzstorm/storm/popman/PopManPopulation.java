package io.pzstorm.storm.popman;

import java.util.function.IntSupplier;

/**
 * How many zombies the simulation wants in a place, and how a fractional want becomes a whole
 * zombie. Pure arithmetic, transliterated from the native so the numbers a server produces do not
 * drift from vanilla.
 */
public final class PopManPopulation {

    /** No entry in the map's density image for this chunk. */
    public static final int NO_DENSITY_DATA = -1;

    public static final float HOURS_PER_DAY = 24.0F;

    private PopManPopulation() {}

    /**
     * The cell's target: a linear ramp from {@code base * Start} on day zero to {@code base * Peak}
     * on {@code PopulationPeakDay}, flat after that.
     *
     * <p>Deliberately not shared with {@link #desiredChunkPopulation}: this one interpolates and
     * then truncates, that one rounds up and then interpolates, and the two disagree.
     */
    public static int desiredCellPopulation(PopManConfig config, int base, double worldAgeHours) {
        float b = base;
        float days = (float) worldAgeHours / HOURS_PER_DAY;
        float peakDay = config.populationPeakDay;
        if (days < peakDay) {
            float start = b * config.populationStartMultiplier;
            return (int) ((b * config.populationPeakMultiplier - start) * (days / peakDay) + start);
        }
        return (int) (b * config.populationPeakMultiplier);
    }

    /** The chunk's target. Note the ceilings land before the interpolation, not after. */
    public static int desiredChunkPopulation(
            PopManConfig config, int basePop, double worldAgeHours) {
        float base = basePop;
        float days = (float) worldAgeHours / HOURS_PER_DAY;
        int peakDay = config.populationPeakDay;
        if (days >= peakDay) {
            return (int) (float) Math.ceil(base * config.populationPeakMultiplier);
        }
        float start = (float) Math.ceil(base * config.populationStartMultiplier);
        float peak = (float) Math.ceil(base * config.populationPeakMultiplier);
        return (int) (start + (peak - start) * (days / peakDay));
    }

    /**
     * Rescales the map's 0..255 density byte into zombies per 8x8 chunk. In uniform mode the
     * sandbox value is substituted into the byte's slot, so it is scaled as if it were a density.
     */
    public static float chunkDensity(PopManConfig config, int densityByte, boolean uniformMode) {
        if (densityByte == NO_DENSITY_DATA) {
            return 0.0F;
        }
        float density = densityByte & 0xFF;
        if (uniformMode) {
            density = config.uniformZombiesPerChunk;
        }
        if (density == 0.0F) {
            return 0.0F;
        }
        return (density * config.populationMultiplier / 255.0F)
                        * (config.maxZombiesPerChunk - config.minZombiesPerChunk)
                + config.minZombiesPerChunk;
    }

    /**
     * Turns the fractional density into a whole base population. A density below one is a Bernoulli
     * draw; a density in {@code [1, 2)} is a coin flip between one and <em>zero</em>, not between
     * one and two.
     *
     * @param randomPercent supplies {@code rand(100)}, uniform over {@code 0..99}
     */
    public static short stochasticBasePop(float density, IntSupplier randomPercent) {
        if (density >= 1.0F) {
            if ((int) density != 1) {
                return (short) (int) density;
            }
            if (randomPercent.getAsInt() > 49) {
                return 0;
            }
        } else if (density * 100.0F <= randomPercent.getAsInt()) {
            return 0;
        }
        return 1;
    }
}
