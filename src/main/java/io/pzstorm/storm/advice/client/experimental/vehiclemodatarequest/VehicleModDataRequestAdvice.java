package io.pzstorm.storm.advice.client.experimental.vehiclemodatarequest;

import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import zombie.iso.IsoObject;
import zombie.network.GameClient;
import zombie.vehicles.VehicleManager;

public class VehicleModDataRequestAdvice {

    public static final byte OBJECT_TYPE_VEHICLE = 5;

    // BaseVehicle.UpdateFlags.Full. 16384 (Passengers) is NOT a full-update request: the
    // server's flag==16384 branch only prunes out-of-relevance vehicles via VehicleRemove,
    // which would erase the client's VehicleCache recovery hint for the very vehicle we're
    // trying to recover. Every vanilla resend call site uses 1.
    public static final short REQUEST_FLAG_FULL_VEHICLE = 1;

    public static final long THROTTLE_NANOS = 5_000_000_000L;

    public static final ConcurrentHashMap<Short, Long> LAST_REQUESTED = new ConcurrentHashMap<>();

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Return IsoObject result,
            @Advice.FieldValue("objectType") byte objectType,
            @Advice.FieldValue("objectId") short objectId) {
        if (result != null) {
            return;
        }
        if (objectType != OBJECT_TYPE_VEHICLE) {
            return;
        }
        if (!GameClient.client) {
            return;
        }
        VehicleManager vm = VehicleManager.instance;
        if (vm == null) {
            return;
        }
        long now = System.nanoTime();
        Long last = LAST_REQUESTED.get(objectId);
        if (last != null && now - last < THROTTLE_NANOS) {
            return;
        }
        LAST_REQUESTED.put(objectId, now);
        vm.sendVehicleRequest(objectId, REQUEST_FLAG_FULL_VEHICLE);
    }
}
