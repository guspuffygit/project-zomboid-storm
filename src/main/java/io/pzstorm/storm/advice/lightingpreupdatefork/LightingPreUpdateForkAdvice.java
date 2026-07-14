package io.pzstorm.storm.advice.lightingpreupdatefork;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;
import zombie.core.PZForkJoinPool;

/**
 * Advice for {@code zombie.iso.LightingJNI.preUpdate()}.
 *
 * <p>Vanilla only forks {@code checkLights()} to {@link PZForkJoinPool} when {@code
 * DebugOptions.instance.threadLighting.getValue()} is true — and it defaults to false. When false,
 * {@code checkLights} runs synchronously on MainThread from inside {@code LightingJNI.update()},
 * costing ~2.4% MainThread every frame (plus another ~2% in downstream {@code bCouldSee} / {@code
 * chunkLightingDone} calls that would otherwise complete on the pool). Live-flipping the debug
 * option demonstrated a ~3.7% MainThread reclaim and a jump from 10% → 27% {@code
 * Thread.sleepNanos0} — the frame budget freed up genuinely idles.
 *
 * <p>This advice force-forks on every {@code preUpdate} regardless of the debug option, then skips
 * the vanilla body. {@code LightingJNI.update()} still joins the future on MainThread, so
 * synchronization is unchanged; the only difference is that {@code checkLights} runs concurrently
 * with the rest of {@code IsoWorld.updateWorld} rather than serially on MainThread.
 *
 * <p>The {@link Runnable} bridging to the private static {@code checkLights} method is built once
 * via {@link MethodHandleProxies} and cached — no anonymous class emission, no per-frame
 * reflection.
 */
public class LightingPreUpdateForkAdvice {

    public static final Runnable CHECK_LIGHTS_RUNNABLE;
    public static final java.lang.reflect.Field CHECK_LIGHTS_FUTURE_FIELD;

    static {
        try {
            Class<?> ljni = Class.forName("zombie.iso.LightingJNI");
            MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(ljni, MethodHandles.lookup());
            MethodHandle mh =
                    lookup.findStatic(ljni, "checkLights", MethodType.methodType(void.class));
            CHECK_LIGHTS_RUNNABLE = MethodHandleProxies.asInterfaceInstance(Runnable.class, mh);
            CHECK_LIGHTS_FUTURE_FIELD = ljni.getDeclaredField("checkLightsFuture");
            CHECK_LIGHTS_FUTURE_FIELD.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException("failed to bind LightingJNI internals", t);
        }
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter() {
        try {
            CompletableFuture<Void> f =
                    CompletableFuture.runAsync(CHECK_LIGHTS_RUNNABLE, PZForkJoinPool.commonPool());
            CHECK_LIGHTS_FUTURE_FIELD.set(null, f);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
