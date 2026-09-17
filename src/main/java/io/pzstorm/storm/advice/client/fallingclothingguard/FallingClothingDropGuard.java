package io.pzstorm.storm.advice.client.fallingclothingguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.objects.IsoFallingClothing;

/**
 * Recovery for a falling clothing item that reached {@code drop()} with no current square, invoked
 * from {@link IsoFallingClothingDropGuardAdvice}.
 *
 * <p>Vanilla {@code drop()} reads {@code getCurrentSquare()} unguarded, and {@code
 * IsoMovingObject.update} can leave it null: on a collision it assigns {@code current = last}, but
 * {@code last} is only set in {@code postupdate()}, so an item that collides on its very first
 * simulated frame ends the update with both null, and {@code IsoPhysicsObject.update} then calls
 * {@code collideWall()} → {@code drop()} straight away. An item drifting onto an unloaded square
 * ends up in the same state. Either way the NPE escapes the world tick and vanilla answers with
 * {@code force-disconnect "crash"}.
 *
 * <p>The guard first resolves the square from the item's own position — the target the server sent
 * when there is one, else where the physics left it — which is what the vanilla body is about to
 * look up anyway. If a square exists the vanilla body runs and the item lands normally. If none is
 * loaded there, the item is destroyed and removed from the cell instead of dropped: the server is
 * authoritative for the ground item ({@code drop()} adds it with {@code transmit=false}), so the
 * client loses a visual and nothing else.
 *
 * <p>Fail-soft: any failure inside the recovery skips the vanilla body for that call, because a
 * skipped drop is strictly cheaper than the disconnect the vanilla body would produce.
 */
public class FallingClothingDropGuard {

    /** Drops that arrived with a null square and were given one from the item's position. */
    public static final AtomicLong RECOVERED = new AtomicLong();

    /** Drops whose position had no loaded square; the item was discarded. */
    public static final AtomicLong DISCARDED = new AtomicLong();

    /** Recoveries that threw; the drop was skipped for that call. */
    public static final AtomicLong FAILED = new AtomicLong();

    /** Returns {@code true} when the vanilla {@code drop()} body must be skipped. */
    public static boolean onNullSquare(Object fallingObj) {
        try {
            IsoFallingClothing falling = (IsoFallingClothing) fallingObj;
            if (falling.isDestroyed()) {
                return true;
            }
            boolean hasTarget = falling.targetX != 0.0F;
            float x = hasTarget ? falling.targetX : falling.getX();
            float y = hasTarget ? falling.targetY : falling.getY();
            float z = hasTarget ? falling.targetZ : falling.getZ();
            falling.setCurrentSquareFromPosition(x, y, z);
            if (falling.getCurrentSquare() != null) {
                if (RECOVERED.incrementAndGet() == 1) {
                    LOGGER.warn(
                            "IsoFallingClothingDropNullGuardPatch: falling clothing reached drop()"
                                    + " with no current square; resolved it from x={} y={} z={}",
                            x,
                            y,
                            z);
                }
                return false;
            }
            falling.setDestroyed(true);
            falling.getCell().Remove(falling);
            if (DISCARDED.incrementAndGet() == 1) {
                LOGGER.warn(
                        "IsoFallingClothingDropNullGuardPatch: falling clothing reached drop() at"
                                + " x={} y={} z={} with no loaded square; discarded the client-side"
                                + " item instead of crashing the world tick",
                        x,
                        y,
                        z);
            }
            return true;
        } catch (Throwable t) {
            if (FAILED.incrementAndGet() == 1) {
                LOGGER.error(
                        "IsoFallingClothingDropNullGuardPatch: recovery failed, skipping drop()",
                        t);
            }
            return true;
        }
    }
}
