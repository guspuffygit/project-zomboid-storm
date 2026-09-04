package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.popman.PopManCell;
import io.pzstorm.storm.popman.PopManCore;
import io.pzstorm.storm.popman.PopManZombie;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure logic behind {@link PopManSaveAdoptFixPatch}: undoes, after a popman save, the adoption of
 * the staged live zombies into the resident population.
 *
 * <p>Entry copies the staged list and every resident cell's two counts. Exit removes each staged
 * object — by identity, since the transpiled code adopts the very instances it was handed — from
 * the chunk list its tile maps to, then puts the counts back. A staged zombie the body never
 * adopted (no save directory, {@code noSave}, a stray outside every resident cell) is simply not
 * found, and its cell's counts are already at their entry values, so the exit degrades to a no-op
 * rather than a wrong correction.
 *
 * <p>Both saves run on the popman worker and neither calls the other, so the snapshot is handed
 * from entry to exit through the advice rather than a static. Any throw latches {@link #broken} and
 * the fix becomes a no-op — the transpiled adoption returns, the server keeps running.
 */
public final class PopManSaveAdoptFix {

    /** Permanent fail-soft latch: any throw reverts to the transpiled adoption. */
    private static volatile boolean broken;

    private PopManSaveAdoptFix() {}

    /**
     * Entry driver. Parameter typed {@code Object} so the inlined advice does not embed a checkcast
     * against the class being redefined.
     *
     * @return the snapshot to hand to {@link #afterSave}, or {@code null} when latched off
     */
    public static Object beforeSave(Object coreRef) {
        if (broken) {
            return null;
        }
        try {
            PopManCore core = (PopManCore) coreRef;
            Snapshot snapshot = new Snapshot();
            snapshot.staged.addAll(core.stagedRealZombies());
            for (PopManCell cell : core.cells().resident()) {
                snapshot.counts.put(cell, new short[] {cell.virtualCount, cell.realCount});
            }
            return snapshot;
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /** Exit driver: takes the adopted twins back out and restores the counts. */
    public static void afterSave(Object coreRef, Object snapshotRef) {
        if (broken || snapshotRef == null) {
            return;
        }
        try {
            PopManCore core = (PopManCore) coreRef;
            Snapshot snapshot = (Snapshot) snapshotRef;
            for (PopManZombie zombie : snapshot.staged) {
                int squareX = (int) Math.floor(zombie.x);
                int squareY = (int) Math.floor(zombie.y);
                PopManCell cell = core.cells().residentForSquare(squareX, squareY);
                if (cell != null) {
                    removeByIdentity(cell.chunkAtSquare(squareX, squareY).zombies, zombie);
                }
            }
            for (Map.Entry<PopManCell, short[]> entry : snapshot.counts.entrySet()) {
                entry.getKey().virtualCount = entry.getValue()[0];
                entry.getKey().realCount = entry.getValue()[1];
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static void removeByIdentity(List<PopManZombie> zombies, PopManZombie zombie) {
        for (int i = zombies.size() - 1; i >= 0; i--) {
            if (zombies.get(i) == zombie) {
                zombies.remove(i);
                return;
            }
        }
    }

    public static boolean isBroken() {
        return broken;
    }

    private static void fail(Throwable t) {
        broken = true;
        LOGGER.error(
                "PopManSaveAdoptFix disabled after an error; the transpiled adoption is back", t);
    }

    private static final class Snapshot {
        final List<PopManZombie> staged = new ArrayList<>();
        final Map<PopManCell, short[]> counts = new IdentityHashMap<>();
    }
}
