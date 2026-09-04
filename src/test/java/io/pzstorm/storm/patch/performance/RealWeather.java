package io.pzstorm.storm.patch.performance;

import java.lang.reflect.Field;
import zombie.core.random.RandStandard;
import zombie.iso.weather.ClimateManager;

/**
 * Drives the engine's real weather for the rain-wander tests: the real {@code ClimateManager}
 * singleton, whose real {@code precipitationIntensity} and {@code precipitationIsSnow} the real
 * {@code RainManager.isRaining()} reads.
 *
 * <p>Nothing here is a stub or a pin. {@code RainManager.isRaining()} delegates to {@code
 * ClimateManager.getInstance().isRaining()}, which is {@code getPrecipitationIntensity() > 0 &&
 * !getPrecipitationIsSnow()}, so setting those two fields is the only honest way to make the engine
 * believe it is raining without a world. Pinning {@code isRaining} instead would test the seam and
 * not the engine, which is exactly the mistake the 1.0.3 animal stride shipped.
 *
 * <p>{@code ClimateManager} builds its singleton in a static initialiser that calls {@code
 * Rand.Next}, so {@code RandStandard} has to be seeded before the class is touched at all;
 * otherwise the first reference dies with {@code ExceptionInInitializerError} and every later touch
 * of the class throws too.
 */
final class RealWeather {

    private static Field intensityValue;
    private static Object intensityHolder;
    private static Field snowValue;
    private static Object snowHolder;

    private RealWeather() {}

    /** Seeds {@code Rand} and resolves the two real climate fields. Idempotent. */
    static synchronized void init() throws Exception {
        if (intensityValue != null) {
            return;
        }
        RandStandard.INSTANCE.init();
        ClimateManager climate = ClimateManager.getInstance();
        intensityHolder = read(climate, "precipitationIntensity");
        intensityValue = finalValueField(intensityHolder);
        snowHolder = read(climate, "precipitationIsSnow");
        snowValue = finalValueField(snowHolder);
    }

    /** Rain falling: the engine's own {@code isRaining()} answers true. */
    static void rain() throws Exception {
        init();
        snowValue.setBoolean(snowHolder, false);
        intensityValue.setFloat(intensityHolder, 0.5f);
    }

    /** Nothing falling. */
    static void dry() throws Exception {
        init();
        snowValue.setBoolean(snowHolder, false);
        intensityValue.setFloat(intensityHolder, 0.0f);
    }

    /**
     * Heavy precipitation that is snow. Vanilla does <em>not</em> count this as rain, so the wander
     * interval must behave exactly as it does in dry weather.
     */
    static void snow() throws Exception {
        init();
        intensityValue.setFloat(intensityHolder, 1.0f);
        snowValue.setBoolean(snowHolder, true);
    }

    private static Object read(ClimateManager climate, String name) throws Exception {
        Field field = ClimateManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(climate);
    }

    private static Field finalValueField(Object holder) throws Exception {
        Field field = holder.getClass().getDeclaredField("finalValue");
        field.setAccessible(true);
        return field;
    }
}
