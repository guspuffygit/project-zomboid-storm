package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.GlassAttachmentRemovalGuardMetrics;
import zombie.core.properties.IsoPropertyType;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.objects.IsoWindow;
import zombie.util.list.PZArrayList;

/**
 * Loop-safe replacement for {@code IsoGridSquare.removeGlassAttachments(IsoWindow)}, behind {@link
 * IsoGridSquareRemoveGlassAttachmentsPatch}.
 *
 * <h2>The bug this heals</h2>
 *
 * <p>Vanilla walks the square's objects and, for every object attached to the glass (or a light
 * switch mounted on the window's wall), calls {@code RemoveTileObject(o)} and then steps the index
 * back one so the slot is re-examined. It never checks that the removal happened. {@code
 * RemoveTileObject} routes through {@code IsoObjectUtils.safelyRemoveTileObjectFromSquare}, which
 * returns {@code -1} <b>without removing anything</b> when the object is multi-tile and {@code
 * getAllMultiTileObjects} cannot find every part. A two-sprite light switch beside a window is
 * exactly that case, so the loop re-examines the same object forever: main thread {@code RUNNABLE},
 * 0 TPS, no exception, nothing logged. Any player smashing that window triggers it. Observed live
 * 2026-09-14 on the ATF pvp-raids instance (three such switches, ~14 minutes wedged, 16 players
 * dropped).
 *
 * <h2>The fix</h2>
 *
 * <p>Same predicate, same removal call, but the index only steps back when the list actually
 * shrank. A removal that leaves the list unchanged is logged, counted, and skipped. Every step back
 * is paid for by a real removal, so the walk terminates after at most {@code size + removals}
 * iterations. The index arithmetic is split into {@link #nextIndex} so it can be unit-tested
 * without game classes.
 */
public final class GlassAttachmentRemovalGuard {

    private GlassAttachmentRemovalGuard() {}

    /**
     * Pure decision: where does the walk go after attempting to remove the object at {@code n}?
     *
     * @param n index of the object the removal was attempted on
     * @param sizeBefore list size before the removal call
     * @param sizeAfter list size after the removal call
     * @return the index to examine next: {@code n} if the list shrank (the slot now holds a new
     *     object), {@code n + 1} if nothing was removed
     */
    public static int nextIndex(int n, int sizeBefore, int sizeAfter) {
        return sizeAfter < sizeBefore ? n : n + 1;
    }

    /**
     * Driver called from the {@code removeGlassAttachments} enter advice in place of the vanilla
     * body. Parameters are typed {@code Object} so the inlined advice does not embed checkcasts
     * against game classes into the patched method; the casts happen here, when both classes are
     * guaranteed loaded.
     *
     * @param squareRef the {@code IsoGridSquare} holding the smashed window
     * @param windowRef the {@code IsoWindow} that was just smashed
     */
    public static void removeGlassAttachments(Object squareRef, Object windowRef) {
        IsoGridSquare square = (IsoGridSquare) squareRef;
        IsoWindow window = (IsoWindow) windowRef;
        IsoDirections sideA = window.getNorth() ? IsoDirections.N : IsoDirections.W;
        IsoDirections sideB = window.getNorth() ? IsoDirections.S : IsoDirections.E;
        PZArrayList<IsoObject> objects = square.getObjects();

        int n = 0;
        while (n < objects.size()) {
            IsoObject o = objects.get(n);
            if (o.sprite == null || !shouldRemove(o, sideA, sideB)) {
                n++;
                continue;
            }
            int before = objects.size();
            square.RemoveTileObject(o);
            int after = objects.size();
            if (after >= before) {
                GlassAttachmentRemovalGuardMetrics.recordFailure();
                LOGGER.warn(
                        "removeGlassAttachments: RemoveTileObject left {} ({}) on square {},{},{};"
                                + " skipping it instead of retrying forever",
                        o.getClass().getSimpleName(),
                        o.sprite.getName(),
                        square.x,
                        square.y,
                        square.z);
            }
            n = nextIndex(n, before, after);
        }
    }

    private static boolean shouldRemove(IsoObject o, IsoDirections sideA, IsoDirections sideB) {
        if (o.getProperties().has(IsoPropertyType.ATTACHED_TO_GLASS)) {
            return true;
        }
        if (!(o instanceof IsoLightSwitch)) {
            return false;
        }
        boolean wallObject =
                o.getProperties().has(IsoPropertyType.IS_MOVE_ABLE)
                        && (o.getProperties().has(IsoPropertyType.IS_HIGH)
                                || "WallObject"
                                        .equals(o.getProperties().get(IsoPropertyType.MOVE_TYPE)));
        if (!wallObject) {
            return false;
        }
        IsoDirections facing = o.getFacing();
        return facing == sideA || facing == sideB;
    }
}
