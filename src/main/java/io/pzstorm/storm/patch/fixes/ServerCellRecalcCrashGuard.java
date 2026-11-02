package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.ServerCellRecalcGuardMetrics;
import zombie.network.ServerMap;

/**
 * Pure logic behind {@link ServerCellRecalcCrashGuardPatch}: swallow a throwable escaping {@code
 * ServerCell.RecalcAll2()} so the throwing cell still finishes {@code Load2()} and stays loaded.
 *
 * <h2>The failure this heals</h2>
 *
 * <p>{@code ServerMap.preupdate()} catches an {@code Exception} out of {@code Load2()}, unloads the
 * cell, clears its {@code cellMap} slot and removes it from {@code loadedCells}. A player still
 * standing there makes the cell relevant again, so it reloads, throws again and drops again, and
 * the area never finishes loading. Observed live 2026-08-29: an {@code IllegalArgumentException:
 * Entity is already registered} from {@code IsoChunk.doLoadGridsquare} → {@code
 * IsoObject.addToWorld}.
 *
 * <p>Vanilla's catch leaves two gaps. It catches {@code Exception} only, so an {@code Error} still
 * escapes {@code preupdate} and skips the whole frame body every tick. And each drop drifts {@code
 * RoomDef.indoorZombies}: {@code RecalcAll2} clears {@code unexploredRooms} before it refills the
 * list and only increments the counters at its very end, so {@code Unload} decrements rooms that
 * were never incremented.
 *
 * <h2>The fix</h2>
 *
 * <p>Swallowing the throwable at the {@code RecalcAll2} method boundary lets {@code Load2} finish
 * normally, including {@code loadVehicles()}. One bad cell degrades to a loudly-logged,
 * partially-recalculated (but present and playable) cell instead of a drop/reload loop.
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
                "ServerCell.RecalcAll2 threw for cell {},{} — keeping the cell loaded instead of"
                        + " letting ServerMap.preupdate drop and reload it every pass. The cell is"
                        + " partially recalculated (some squares may have stale room/LOS"
                        + " bookkeeping until the next load).",
                cell.wx,
                cell.wy,
                thrown);
        return null;
    }
}
