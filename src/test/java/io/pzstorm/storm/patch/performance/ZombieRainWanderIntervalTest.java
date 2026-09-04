package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import io.pzstorm.storm.metrics.ZombieRainWanderMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.GameServer;

/**
 * The decision and its arithmetic, without the bytecode. {@link ZombieRainWanderPatchTest} covers
 * the same rules end to end through the real transformed class; this class pins the clamp, the
 * scaling identity and the counter bookkeeping, which are cheaper to state directly.
 */
class ZombieRainWanderIntervalTest implements UnitTest {

    private boolean savedServerFlag;

    /**
     * ⛔ {@code GameServer.server} is process-wide; see {@code
     * RequestDataManagerJoinStallPatchTest}.
     */
    @BeforeEach
    void captureState() throws Exception {
        savedServerFlag = GameServer.server;
        ZombieRainWanderInterval.resetForTest();
        ZombieRainWanderMetrics.resetForTest();
        RealWeather.init();
    }

    @AfterEach
    void restoreState() throws Exception {
        GameServer.server = savedServerFlag;
        ZombieRainWanderInterval.resetForTest();
        ZombieRainWanderMetrics.resetForTest();
        RealWeather.dry();
    }

    // ---------------------------------------------------------------- constants

    /**
     * The whole patch rests on vanilla's dry branch being {@code f *= 1.5F}. If that constant ever
     * changes, {@code RAIN_AS_DRY_PERCENT} stops meaning what its name and the option tooltip say.
     */
    @Test
    void rainAsDryPercentIsVanillasDryMultiplier() {
        assertEquals(150, ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT);
        assertEquals(
                1.5f,
                ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT / 100.0f,
                0f,
                "the option is only meaningful while vanilla's dry branch is 1.5x");
        assertEquals(100, ZombieRainWanderInterval.VANILLA_PERCENT);
        assertTrue(
                ZombieRainWanderInterval.MIN_PERCENT == ZombieRainWanderInterval.VANILLA_PERCENT,
                "the floor is vanilla: this option must never be able to make rain cost more");
    }

    // ---------------------------------------------------------------- clamp

    @Test
    void clampHoldsTheDeclaredRange() {
        assertEquals(
                ZombieRainWanderInterval.MIN_PERCENT, ZombieRainWanderInterval.clampPercent(1));
        assertEquals(
                ZombieRainWanderInterval.MIN_PERCENT, ZombieRainWanderInterval.clampPercent(-500));
        assertEquals(
                ZombieRainWanderInterval.MAX_PERCENT, ZombieRainWanderInterval.clampPercent(9999));
        assertEquals(137, ZombieRainWanderInterval.clampPercent(137));
    }

    @Test
    void setPercentAppliesTheClampedValue() {
        assertEquals(
                ZombieRainWanderInterval.MAX_PERCENT, ZombieRainWanderInterval.setPercent(500));
        assertEquals(
                ZombieRainWanderInterval.MAX_PERCENT,
                ZombieRainWanderInterval.getCurrentPercent(),
                "the gauge and the decision must see the same number the setter returned");
        assertEquals(150, ZombieRainWanderInterval.setPercent(150));
        assertEquals(150, ZombieRainWanderInterval.getCurrentPercent());
    }

    // ---------------------------------------------------------------- arithmetic

    @Test
    void scaleAtOneFiftyIsVanillasDryBranch() {
        for (float raw = 400f; raw <= 999f; raw += 1f) {
            assertEquals(
                    raw * 1.5f,
                    ZombieRainWanderInterval.scale(
                            raw, ZombieRainWanderInterval.RAIN_AS_DRY_PERCENT),
                    0f,
                    "a raining pick at 150% must be bit-identical to vanilla's dry pick for "
                            + raw);
        }
    }

    @Test
    void scaleAtVanillaPercentIsIdentity() {
        assertEquals(
                742f,
                ZombieRainWanderInterval.scale(742f, ZombieRainWanderInterval.VANILLA_PERCENT),
                0f);
    }

    // ---------------------------------------------------------------- decision

    @Test
    void theDefaultReturnsVanillaAndCountsNothing() throws Exception {
        GameServer.server = true;
        RealWeather.rain();
        assertEquals(700f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
        assertEquals(0, ZombieRainWanderMetrics.dry);
    }

    @Test
    void rainIsScaledAndCounted() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(150);
        RealWeather.rain();
        assertEquals(1050f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(1, ZombieRainWanderMetrics.rainScaled);
        assertEquals(0, ZombieRainWanderMetrics.dry);
    }

    @Test
    void dryIsLeftAloneAndCountedSeparately() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(150);
        RealWeather.dry();
        assertEquals(1050f, ZombieRainWanderInterval.adjust(1050f), 0f);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
        assertEquals(1, ZombieRainWanderMetrics.dry);
    }

    /** Snow reaches the decision as "not raining", because that is what the engine calls it. */
    @Test
    void snowCountsAsDry() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(150);
        RealWeather.snow();
        assertEquals(700f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
        assertEquals(1, ZombieRainWanderMetrics.dry);
    }

    @Test
    void offTheServerNothingIsTouched() throws Exception {
        GameServer.server = false;
        ZombieRainWanderInterval.setPercent(150);
        RealWeather.rain();
        assertEquals(700f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
        assertEquals(0, ZombieRainWanderMetrics.dry);
    }

    @Test
    void aLatchedFailureIsPermanentForTheBoot() throws Exception {
        GameServer.server = true;
        ZombieRainWanderInterval.setPercent(150);
        RealWeather.rain();
        assertFalse(ZombieRainWanderInterval.isFailureLatched());
        ZombieRainWanderInterval.latchForTest();
        assertEquals(700f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(700f, ZombieRainWanderInterval.adjust(700f), 0f);
        assertEquals(0, ZombieRainWanderMetrics.rainScaled);
    }
}
