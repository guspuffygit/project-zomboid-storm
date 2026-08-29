package io.pzstorm.storm.advice.servercellrecalcguard;

import io.pzstorm.storm.patch.fixes.ServerCellRecalcCrashGuard;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code zombie.network.ServerMap$ServerCell.RecalcAll2()}. Hands any
 * escaping throwable to {@link ServerCellRecalcCrashGuard#onRecalcExit(Object, Throwable)}, which
 * logs it and returns {@code null} to swallow it (or returns it unchanged for {@link
 * VirtualMachineError}s). Swallowing lets {@code Load2()} finish and evict the cell from the static
 * {@code loaded2} list and {@code ServerMap.toLoad}, instead of the vanilla behavior where the
 * throw re-fires from {@code ServerMap.preupdate()} every frame and freezes the world loop
 * permanently. See {@link io.pzstorm.storm.patch.fixes.ServerCellRecalcCrashGuardPatch} for the
 * rationale.
 *
 * <p>The helper is called on every exit, not just throwing ones, so its class (and the failure
 * counter it registers) loads on the first cell recalc at boot. {@code suppress = Throwable.class}
 * makes the guard itself fail soft: if the helper ever throws, the assignment is skipped and the
 * original throwable propagates — vanilla behavior, never worse.
 */
public class ServerCellRecalcCrashGuardAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
            @Advice.This Object cell, @Advice.Thrown(readOnly = false) Throwable thrown) {
        thrown = ServerCellRecalcCrashGuard.onRecalcExit(cell, thrown);
    }
}
