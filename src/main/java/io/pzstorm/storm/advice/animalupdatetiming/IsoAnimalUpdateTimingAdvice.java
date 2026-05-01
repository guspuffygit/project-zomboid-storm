package io.pzstorm.storm.advice.animalupdatetiming;

import io.pzstorm.storm.metrics.AnimalUpdateMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Advice for {@code IsoAnimal.update()}.
 *
 * <p>Captures wall-clock nanoseconds spent inside the method and forwards each measurement to
 * {@link AnimalUpdateMetrics}. Server-only &mdash; the metrics path is irrelevant on the client.
 *
 * <p>Advice bodies are inlined into the target method, so this class must use plain imperative
 * Java (no lambdas / streams) and reference only types resolvable from the target's class loader.
 */
public class IsoAnimalUpdateTimingAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        if (!GameServer.server) {
            return 0L;
        }
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter long startNanos) {
        if (!GameServer.server) {
            return;
        }
        if (startNanos == 0L) {
            return;
        }
        AnimalUpdateMetrics.recordUpdateNanos(System.nanoTime() - startNanos);
    }
}
