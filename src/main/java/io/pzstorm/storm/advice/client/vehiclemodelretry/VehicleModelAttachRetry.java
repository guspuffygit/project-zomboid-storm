package io.pzstorm.storm.advice.client.vehiclemodelretry;

import io.pzstorm.storm.logging.StormLogger;
import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import zombie.core.skinnedmodel.ModelManager;
import zombie.vehicles.BaseVehicle;

/**
 * Retries {@code ModelManager.addVehicle} for a vehicle whose model never attached.
 *
 * <p>Vanilla sets {@code BaseVehicle.createdModel = true} unconditionally in {@code createPhysics}
 * even when {@code ModelManager.addVehicle} silently no-ops (manager not created yet, script null,
 * or the model/texture not loaded at that instant — common under high-population streaming
 * pressure). The vehicle then has physics and collision but {@code sprite.modelSlot == null}, so it
 * is never rendered, and nothing in vanilla retries until a part model changes. This helper retries
 * the attach, throttled per vehicle.
 *
 * <p>Fail-soft: any error permanently disables the retry (vanilla behavior resumes); the reflection
 * handle failing to resolve on a future PZ update disables it at class-init.
 */
public final class VehicleModelAttachRetry {

    public static final long RETRY_INTERVAL_NANOS = 2_000_000_000L;

    public static final ConcurrentHashMap<Short, Long> LAST_ATTEMPT = new ConcurrentHashMap<>();

    public static volatile boolean disabled;

    // createdModel is private; only ever read. Retrying while it is false would attach a slot
    // that removeFromWorld never releases (it only calls ModelManager.Remove when createdModel
    // is set), leaking the slot.
    public static final Field CREATED_MODEL;

    static {
        Field field = null;
        try {
            field = BaseVehicle.class.getDeclaredField("createdModel");
            field.setAccessible(true);
        } catch (Throwable t) {
            StormLogger.LOGGER.error(
                    "VehicleModelAttachRetry disabled: BaseVehicle.createdModel not found", t);
        }
        CREATED_MODEL = field;
    }

    private VehicleModelAttachRetry() {}

    public static void retryIfModelMissing(BaseVehicle vehicle) {
        if (disabled || CREATED_MODEL == null) {
            return;
        }
        try {
            if (vehicle.getScript() == null) {
                return;
            }
            ModelManager manager = ModelManager.instance;
            if (manager == null || !manager.isCreated()) {
                return;
            }
            if (!CREATED_MODEL.getBoolean(vehicle)) {
                return;
            }
            short id = vehicle.getId();
            long now = System.nanoTime();
            Long last = LAST_ATTEMPT.get(id);
            if (last != null && now - last < RETRY_INTERVAL_NANOS) {
                return;
            }
            LAST_ATTEMPT.put(id, now);
            manager.addVehicle(vehicle);
            if (vehicle.sprite != null && vehicle.sprite.modelSlot != null) {
                LAST_ATTEMPT.remove(id);
                StormLogger.LOGGER.info("Re-attached missing vehicle model, id={}", id);
            }
        } catch (Throwable t) {
            disabled = true;
            StormLogger.LOGGER.error("VehicleModelAttachRetry disabled after error", t);
        }
    }
}
