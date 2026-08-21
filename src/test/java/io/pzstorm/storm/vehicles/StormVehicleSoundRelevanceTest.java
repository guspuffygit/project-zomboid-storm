package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Pure-radius half of {@link StormVehicleSoundRelevance} against vanilla's numbers. */
class StormVehicleSoundRelevanceTest implements UnitTest {

    @Test
    void silentVehicleHasZeroRadius() {
        Assertions.assertEquals(
                0.0F,
                StormVehicleSoundRelevance.radiusFor(false, false, false, false, false, false));
    }

    @Test
    void eachPredicateContributesVanillaRadius() {
        Assertions.assertEquals(
                500.0F,
                StormVehicleSoundRelevance.radiusFor(true, false, false, false, false, false));
        Assertions.assertEquals(
                150.0F,
                StormVehicleSoundRelevance.radiusFor(false, true, false, false, false, false));
        Assertions.assertEquals(
                50.0F,
                StormVehicleSoundRelevance.radiusFor(false, false, true, false, false, false));
        Assertions.assertEquals(
                200.0F,
                StormVehicleSoundRelevance.radiusFor(false, false, false, true, false, false));
        Assertions.assertEquals(
                500.0F,
                StormVehicleSoundRelevance.radiusFor(false, false, false, false, true, false));
        Assertions.assertEquals(
                500.0F,
                StormVehicleSoundRelevance.radiusFor(false, false, false, false, false, true));
    }

    @Test
    void combinationsTakeTheMaximum() {
        Assertions.assertEquals(
                200.0F,
                StormVehicleSoundRelevance.radiusFor(false, true, true, true, false, false));
        Assertions.assertEquals(
                500.0F, StormVehicleSoundRelevance.radiusFor(false, true, true, true, false, true));
        Assertions.assertEquals(
                150.0F,
                StormVehicleSoundRelevance.radiusFor(false, true, true, false, false, false));
    }
}
