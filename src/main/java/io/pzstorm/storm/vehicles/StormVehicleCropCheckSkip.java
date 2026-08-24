package io.pzstorm.storm.vehicles;

import io.pzstorm.storm.logging.StormLogger;
import zombie.vehicles.BaseVehicle;

/**
 * Decides whether {@code BaseVehicle.notKillCrops()} may short-circuit to {@code true} ("does not
 * kill crops"), wired in by {@code BaseVehicleCropCheckSkipPatch}.
 *
 * <p>A vehicle with no driver and no towing vehicle cannot be deliberately moving, so it cannot be
 * crushing crops. Answering {@code true} for it short-circuits the crop-crushing block in {@code
 * BaseVehicle.update()} before its per-tick cost — ~17 {@code ServerMap.getGridSquare} calls plus
 * four {@code GlobalObjectLookup.getObjectAt} and four {@code SGlobalObjects.getSystemByName
 * ("farming")} linear string scans per parked vehicle per tick — and coherently makes the two
 * {@code IsoGridSquare} callers treat parked vehicles as harmless too.
 *
 * <p>Known divergence from vanilla, accepted by design: a driverless vehicle still rolling from
 * physics (pushed, coasting after the driver bailed) won't crush crops until someone sits in the
 * driver seat again.
 *
 * <p>Always on; vanilla behavior is restored automatically and permanently if the check ever
 * throws.
 */
public final class StormVehicleCropCheckSkip {

    private static boolean failed;

    private StormVehicleCropCheckSkip() {}

    /**
     * @return {@code true} to skip the vanilla {@code notKillCrops()} body and report the vehicle
     *     as not killing crops, {@code false} to fall through to vanilla.
     */
    public static boolean shouldSkip(Object vehicleObj) {
        if (failed) {
            return false;
        }
        try {
            BaseVehicle vehicle = (BaseVehicle) vehicleObj;
            return vehicle.getDriver() == null && vehicle.getVehicleTowedBy() == null;
        } catch (Throwable t) {
            failed = true;
            StormLogger.LOGGER.error(
                    "StormVehicleCropCheckSkip failed — reverting to vanilla notKillCrops", t);
            return false;
        }
    }
}
