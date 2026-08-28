package io.pzstorm.storm.advice.client.worlditemspriteguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteManager;

/**
 * Repair for a dropped world item that reached the render path with a null {@code sprite}, invoked
 * from {@link IsoWorldInventoryObjectRenderSpriteGuardAdvice}.
 *
 * <p>Restores the invariant every constructor and load path of {@code IsoWorldInventoryObject}
 * establishes: an anonymous {@code IsoSprite}, then {@code updateSprite()} to reload the item's
 * world texture so the item is drawn again instead of merely not crashing. A fresh sprite reports
 * {@code hasNoTextures()}, so even if the texture reload fails the vanilla body simply skips the 2D
 * fallback and the 3D model path — the normal way these items are drawn — is untouched.
 *
 * <p>The producer of the null is not known: it is reachable from {@code
 * IsoObject.setSpriteFromName} with a name {@code IsoSpriteManager} cannot resolve (any mod's Lua)
 * and from the {@code IsoObjectChange.SPRITE} receive when the transmitted sprite id is 0. The
 * once-per-item-type warning below names the item so the next field report identifies it.
 *
 * <p>Fail-soft: on any failure the field is left null and vanilla throws exactly as it does today,
 * and the guard permanently disables itself so a broken repair cannot itself become the new
 * per-frame wall.
 */
public class WorldItemSpriteGuard {

    /** Distinct item types already reported, so the repair never becomes its own log wall. */
    public static final Set<String> LOGGED_TYPES = ConcurrentHashMap.newKeySet();

    /** Total repairs performed, reported alongside each newly seen item type. */
    public static final AtomicLong REPAIRS = new AtomicLong();

    private static final int MAX_LOGGED_TYPES = 16;

    private static volatile boolean failed;

    public static void restoreSprite(Object worldItemObj) {
        if (failed) {
            return;
        }
        try {
            IsoWorldInventoryObject worldItem = (IsoWorldInventoryObject) worldItemObj;
            worldItem.sprite = IsoSprite.CreateSprite(IsoSpriteManager.instance);
            InventoryItem item = worldItem.getItem();
            if (item != null) {
                worldItem.updateSprite();
            }
            report(worldItem, item);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error(
                    "WorldItemSpriteGuard: null-sprite repair failed, reverting to vanilla render"
                            + " for the rest of this session",
                    t);
        }
    }

    private static void report(IsoWorldInventoryObject worldItem, InventoryItem item) {
        long repairs = REPAIRS.incrementAndGet();
        String type = item == null ? "<null item>" : item.getFullType();
        if (LOGGED_TYPES.size() >= MAX_LOGGED_TYPES || !LOGGED_TYPES.add(type)) {
            return;
        }
        IsoGridSquare square = worldItem.getSquare();
        LOGGER.warn(
                "WorldItemSpriteGuard: world item '{}' had a null sprite at render; rebuilt it"
                        + " (square={} totalRepairs={})",
                type,
                square == null
                        ? "<null>"
                        : square.getX() + "," + square.getY() + "," + square.getZ(),
                repairs);
    }
}
