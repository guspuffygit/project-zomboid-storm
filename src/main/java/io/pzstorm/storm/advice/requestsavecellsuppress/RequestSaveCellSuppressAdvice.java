package io.pzstorm.storm.advice.requestsavecellsuppress;

import net.bytebuddy.asm.Advice;

/**
 * Unconditionally skips the body of {@code ZombiePopulationManager.requestSaveCell(int, int)}.
 *
 * <p>The owning transformer is registration-gated server-only, so this advice is only ever woven
 * into the dedicated server JVM. The vanilla method body has no useful work outside of the buggy
 * append-to-disk path; skipping it leaves {@code processPendingSaveCells} as a passive no-op.
 */
public class RequestSaveCellSuppressAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter() {
        return true;
    }
}
