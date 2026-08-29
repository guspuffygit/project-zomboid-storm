package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.ServerCellRecalcGuardMetrics;
import zombie.network.ServerMap;

/**
 * Pure logic behind {@link ServerCellRecalcCrashGuardPatch}: swallow a throwable escaping {@code
 * ServerCell.RecalcAll2()} so the throwing cell finishes {@code Load2()} and is evicted from the
 * retry lists instead of wedging the server main loop forever.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>{@code ServerCell.Load2()} calls {@code RecalcAll2()} <b>before</b> {@code loaded2.remove(i)}
 * and before {@code return true}. Any exception out of {@code RecalcAll2} therefore leaves the cell
 * in the static {@code ServerCell.loaded2} list <i>and</i> in {@code ServerMap.toLoad} permanently.
 * {@code ServerMap.preupdate()} retries it every frame — and {@code preupdate} is the <i>first</i>
 * statement in {@code GameServer.main}'s frame-step {@code try}, so the re-thrown exception skips
 * the entire rest of the frame body on every tick: world update, {@code StatisticManager.update},
 * {@code LoginQueue.update}, {@code SteamUtils.runLoop}, console command processing. The world
 * freezes while chat/network/HTTP threads keep serving, and the {@code mainCycleExceptionLogCount =
 * 25} process-lifetime log budget in {@code GameServer.main} hides the loop after at most 25 log
 * lines. Observed live 2026-08-29: an {@code IllegalArgumentException: Entity is already
 * registered} from {@code IsoChunk.doLoadGridsquare} → {@code IsoObject.addToWorld} froze the
 * server for ~13 minutes until a manual restart, with 21 log lines covering ~15,000 silent retry
 * frames.
 *
 * <h2>The fix</h2>
 *
 * <p>Swallowing the throwable at the {@code RecalcAll2} method boundary lets {@code Load2} run its
 * own cleanup: {@code loaded2.remove(i)}, {@code loadVehicles()}, {@code return true} — and the
 * {@code preupdate} driver then removes the cell from {@code toLoad}. One bad cell degrades to a
 * loudly-logged, partially-recalculated (but present and playable) cell instead of a dead server.
 *
 * <p>{@link VirtualMachineError}s ({@code OutOfMemoryError}, {@code StackOverflowError}, ...) are
 * never masked — hiding those would trade a visible crash for undefined behavior. The decision
 * logic ({@link #shouldSwallow}) is split out from the I/O ({@link #onRecalcExit}) so it can be
 * unit-tested without game classes on the classpath.
 */
public final class ServerCellRecalcCrashGuard {

    private ServerCellRecalcCrashGuard() {}

    /**
     * Pure decision: may {@code thrown} be swallowed at the {@code RecalcAll2} boundary?
     *
     * @param thrown the throwable that escaped {@code RecalcAll2}
     * @return {@code false} for {@link VirtualMachineError}s, {@code true} for everything else
     */
    public static boolean shouldSwallow(Throwable thrown) {
        return !(thrown instanceof VirtualMachineError);
    }

    /**
     * Driver called unconditionally from the {@code RecalcAll2()} exit advice — the unconditional
     * call makes this class (and its failure counter) load on the first cell recalc at server boot,
     * so the metric exists before any failure.
     *
     * <p>{@code cellRef} is typed {@code Object} so the inlined advice does not embed a checkcast
     * against a game class into the patched method's bytecode; the cast happens here, when both
     * classes are guaranteed loaded. See the {@code feedback_elided_cast_load} memory.
     *
     * @param cellRef the {@code ServerMap.ServerCell} whose recalc just finished
     * @param thrown the throwable that escaped {@code RecalcAll2}, or {@code null} on clean exit
     * @return the throwable the advised method should re-throw: {@code null} to swallow
     */
    public static Throwable onRecalcExit(Object cellRef, Throwable thrown) {
        if (thrown == null) {
            return null;
        }
        if (!shouldSwallow(thrown)) {
            return thrown;
        }
        ServerCellRecalcGuardMetrics.recordFailure();
        ServerMap.ServerCell cell = (ServerMap.ServerCell) cellRef;
        LOGGER.error(
                "ServerCell.RecalcAll2 threw for cell {},{} — dropping the cell from the recalc"
                        + " retry lists so the main loop keeps running. The cell stays loaded but"
                        + " partially recalculated (some squares may have stale room/LOS"
                        + " bookkeeping until the next load).",
                cell.wx,
                cell.wy,
                thrown);
        return null;
    }
}
