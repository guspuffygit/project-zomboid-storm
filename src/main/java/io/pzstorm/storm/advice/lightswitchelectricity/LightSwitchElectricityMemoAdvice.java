package io.pzstorm.storm.advice.lightswitchelectricity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.bytebuddy.asm.Advice;

/**
 * Advice for the private {@code zombie.iso.objects.IsoLightSwitch.hasElectricityAround()}.
 *
 * <p>The vanilla body scans up to 18 neighboring squares, and per square walks the object list
 * ({@code hasFasciaAdjacentToBuildingSquare}) and the chunk generator list ({@code
 * IsoGridSquare.haveElectricity}). It is invoked per switched lamppost per frame from {@code
 * LightingJNI.checkLights} via {@code canSwitchLight()}, plus per visible switch from {@code
 * shouldShowOnOverlay()} — live-client sampling in downtown Louisville attributed 1.6% of
 * MainThread to it directly.
 *
 * <p>The answer ("is there powered infrastructure adjacent?") only changes on power events — grid
 * shutdown, generator placement/toggle, building teardown — so the advice caches it per-switch with
 * a wall-clock TTL rather than tracking every invalidation source. The TTL bounds the staleness: a
 * lamppost reacts to a power change up to {@link #TTL_MILLIS} late, which is imperceptible next to
 * the game's own minute-granularity power updates ({@code IsoLightSwitch.update} gates on {@code
 * getMinutesStamp()}).
 *
 * <p>The cache is a synchronized {@link WeakHashMap} keyed on the switch instance: entries die with
 * their switch when chunks unload, and the synchronization covers the two callers (MainThread and
 * the async lighting pre-update thread). Values are {@code long[]{deadline, result}} rather than a
 * value object to keep the advice body free of any class the target loader might not see.
 */
public class LightSwitchElectricityMemoAdvice {

    public static final long TTL_MILLIS =
            Long.getLong("storm.experimental.clientperf.lightSwitchTtlMs", 500L);

    public static final Map<Object, long[]> CACHE =
            Collections.synchronizedMap(new WeakHashMap<Object, long[]>());

    /**
     * Returns 0 on a cache miss (run the vanilla body), 1 for a cached {@code false}, 2 for a
     * cached {@code true}. Non-zero skips the body.
     */
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This Object self) {
        long[] entry = CACHE.get(self);
        if (entry == null || System.currentTimeMillis() > entry[0]) {
            return 0;
        }
        return entry[1] != 0L ? 2 : 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.This Object self,
            @Advice.Enter int cached,
            @Advice.Return(readOnly = false) boolean ret) {
        if (cached != 0) {
            ret = cached == 2;
            return;
        }
        CACHE.put(self, new long[] {System.currentTimeMillis() + TTL_MILLIS, ret ? 1L : 0L});
    }
}
