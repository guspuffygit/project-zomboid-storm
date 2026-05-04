package io.pzstorm.storm.advice.bitheader;

import io.pzstorm.storm.metrics.BitHeaderMetrics;
import net.bytebuddy.asm.Advice;
import zombie.network.GameServer;

/**
 * Server-side: counts each {@code BitHeader$BitHeaderByte.release()} call <em>and</em>
 * short-circuits the original method body, so {@code reset()} and {@code pool_byte.offer(this)}
 * never run. Effect: the BitHeader instance is dropped on the floor and becomes eligible for GC,
 * and no {@code ConcurrentLinkedDeque$Node} is allocated.
 *
 * <p>This is the "drop the pool entirely" control test from the JFR analysis. Skipping {@code
 * reset()} is safe because every reuse path goes through {@code getHeader} → {@code setBuffer} /
 * {@code setWrite} → {@code create()} / {@code read()}, which fully reinitialize all four state
 * fields. (No caller in the codebase passes {@code allocOnly=true}.)
 *
 * <p>Client side: original body runs unchanged, so the pool keeps working there.
 */
public class BitHeaderByteReleaseAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        if (!GameServer.server) {
            return false;
        }
        BitHeaderMetrics.observeReleaseByte();
        return true;
    }
}
