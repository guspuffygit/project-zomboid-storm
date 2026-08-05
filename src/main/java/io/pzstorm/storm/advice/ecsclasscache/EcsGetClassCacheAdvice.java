package io.pzstorm.storm.advice.ecsclasscache;

import io.pzstorm.storm.entity.EcsClassCache;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Routes the static {@code ECSComponent.getECSClass(Class)} through {@link EcsClassCache#lookup}: a
 * non-null verdict is the memoized vanilla result and the vanilla superclass walk is skipped (the
 * exit advice writes it as the return value); {@code null} (client JVM guard, kill switch, failure
 * latch, or an input whose vanilla result is {@code null}) leaves the vanilla body to run
 * untouched.
 *
 * <p>Runs millions of times per second on a busy server — the hit path is a static boolean read,
 * the {@code lookup} call, and a {@code ClassValue.get}; deliberately no metrics here (misses are
 * counted once per class inside the cache's {@code computeValue}).
 */
public class EcsGetClassCacheAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Class<?> onEnter(@Advice.Argument(0) Class<?> clazz) {
        if (!GameServer.server) {
            return null;
        }
        return EcsClassCache.lookup(clazz);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Class<?> cached, @Advice.Return(readOnly = false) Class<?> ret) {
        if (cached != null) {
            ret = cached;
        }
    }
}
