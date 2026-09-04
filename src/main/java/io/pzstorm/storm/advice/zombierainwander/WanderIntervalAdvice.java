package io.pzstorm.storm.advice.zombierainwander;

import io.pzstorm.storm.patch.performance.ZombieRainWanderInterval;
import net.bytebuddy.asm.Advice;

/**
 * Exit advice on {@code ZombieIdleState.pickRandomWanderInterval()}. Vanilla's answer is handed to
 * {@link ZombieRainWanderInterval#adjust}, which returns it unchanged unless it is raining and the
 * {@code Storm.ZombieRainWanderPercent} option is off its vanilla default.
 *
 * <p>Advice bodies are inlined into the target method, so this class stays plain imperative Java.
 */
public class WanderIntervalAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return(readOnly = false) float result) {
        result = ZombieRainWanderInterval.adjust(result);
    }
}
