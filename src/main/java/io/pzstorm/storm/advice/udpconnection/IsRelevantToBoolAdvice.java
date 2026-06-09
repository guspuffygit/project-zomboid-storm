package io.pzstorm.storm.advice.udpconnection;

import io.pzstorm.storm.patch.networking.UdpConnectionRelevance;
import net.bytebuddy.asm.Advice;

/**
 * Shared advice for the three boolean relevance overloads on {@code UdpConnection}: {@code
 * isRelevantTo(float, float)}, {@code RelevantToPlayerIndex(int, float, float)}, and {@code
 * RelevantTo(float, float, float)}.
 *
 * <p>Returning {@code true} from the enter handler skips the original body. With the body skipped,
 * the method returns the default {@code boolean} value — {@code false} — which is the semantically
 * correct "not relevant" outcome for a connection still streaming the world download.
 */
public class IsRelevantToBoolAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(@Advice.This Object self) {
        return !UdpConnectionRelevance.isConnectionReady(self);
    }
}
