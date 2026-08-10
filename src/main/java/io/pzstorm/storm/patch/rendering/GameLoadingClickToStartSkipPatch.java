package io.pzstorm.storm.patch.rendering;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Dev/testing-only: auto-presses the pre-spawn "Click to start" screen so a load runs straight into
 * the world. Gated on {@code -Dstorm.skipclickstart=true}, which the launcher deliberately never
 * passes — normal players keep the vanilla click (deciding when to spawn matters on a PvP server).
 * Enable it per test run via a profile's {@code extraVmArgs} in {@code launcher.json}. Registered
 * only when the property is set — see {@code StormClassTransformers}.
 *
 * <p>{@code GameLoadingState.update()} holds the finished load in {@code StateAction.Remain} until
 * {@code Mouse.isButtonDown(0)} sets the private {@code forceDone} flag. The advice pre-sets that
 * flag on every update; vanilla's own gates ({@code done}, world streamer idle, animations loaded,
 * {@code showedClickToSkip} — which for a new character still waits out the intro text) all stay in
 * force, so the state advances at exactly the first frame a real click would have worked.
 *
 * <p>A bytecode patch is required: no Lua event fires inside the loading state's pre-spawn wait,
 * and {@code forceDone} is a private instance field on the active state with no static handle — a
 * reflective poke from another thread has no reliable instance to target and no visibility
 * guarantee on the plain boolean.
 */
public class GameLoadingClickToStartSkipPatch extends StormClassTransformer {

    public GameLoadingClickToStartSkipPatch() {
        super("zombie.gameStates.GameLoadingState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(UpdateAdvice.class)
                        .on(
                                ElementMatchers.named("update")
                                        .and(ElementMatchers.takesArguments(0))));
    }

    public static class UpdateAdvice {

        /** Once-per-session log latch; update() runs every frame while loading. */
        public static final AtomicBoolean ARMED_LOGGED = new AtomicBoolean();

        @Advice.OnMethodEnter
        public static void onEnter(
                @Advice.FieldValue(value = "forceDone", readOnly = false) boolean forceDone) {
            if (ARMED_LOGGED.compareAndSet(false, true)) {
                LOGGER.info(
                        "GameLoadingClickToStartSkipPatch: auto-skipping the click-to-start"
                                + " screen (storm.skipclickstart)");
            }
            forceDone = true;
        }
    }
}
