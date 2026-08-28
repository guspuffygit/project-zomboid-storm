package io.pzstorm.storm.vehicles;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;
import zombie.core.math.PZMath;

/**
 * Coverage parity for {@link StormVehicleSend}'s grid queries against vanilla {@code
 * UdpConnection.isRelevantTo}, which has two relevance sources: the {@code connectArea} boxes and
 * {@code releventPos} within {@code relevantRange * 8} squares. Any square vanilla considers
 * relevant must fall inside the fast path's queried square range, or the vehicle on it is never
 * examined and never sent — the client collides with a car it has no model for. The releventPos
 * source is the steady-state one (connectArea was null for every live ATF connection when the
 * 2026-08-28 invisible-car outage hit), so both sources get the same superset assertion.
 */
class StormVehicleSendTest implements UnitTest {

    /** Fractional, negative and live-ATF-sized coordinates. */
    private static final float[] COORDS = {
        -1234.75F, -8.5F, -0.25F, 0.0F, 3.99F, 100.0F, 1001.5F, 8001.4F, 11405.9F, 14069.2F
    };

    @Test
    void connectAreaQueryCoversEveryVanillaRelevantSquare() {
        for (float areaCoord : COORDS) {
            for (int width : new int[] {1, 7, 8, 12, 13, 20}) {
                // Vanilla box on one axis: x in [min, min + width * 8), min floored to chunks.
                int vanillaMin = PZMath.fastfloor(areaCoord - width / 2) * 8;
                int vanillaMaxExclusive = vanillaMin + width * 8;
                assertTrue(
                        StormVehicleSend.boxMinSquare(areaCoord, width) <= vanillaMin,
                        "box min uncovered at " + areaCoord + " width " + width);
                assertTrue(
                        StormVehicleSend.boxMaxSquare(areaCoord, width) >= vanillaMaxExclusive - 1,
                        "box max uncovered at " + areaCoord + " width " + width);
            }
        }
    }

    @Test
    void releventPosQueryCoversEveryVanillaRelevantSquare() {
        for (float pos : COORDS) {
            // receivePlayerConnect clamps range to 12..20 then stores range / 2 + 2 = 8..12.
            for (int relevantRange = 8; relevantRange <= 12; relevantRange++) {
                // Vanilla: |pos - x| <= relevantRange * 8, inclusive on both ends.
                int lowestRelevantSquare = PZMath.fastfloor(pos - relevantRange * 8);
                int highestRelevantSquare = PZMath.fastfloor(pos + relevantRange * 8);
                assertTrue(
                        StormVehicleSend.posMinSquare(pos, relevantRange) <= lowestRelevantSquare,
                        "pos min uncovered at " + pos + " range " + relevantRange);
                assertTrue(
                        StormVehicleSend.posMaxSquare(pos, relevantRange) >= highestRelevantSquare,
                        "pos max uncovered at " + pos + " range " + relevantRange);
            }
        }
    }
}
