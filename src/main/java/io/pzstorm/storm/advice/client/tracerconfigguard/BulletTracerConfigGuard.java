package io.pzstorm.storm.advice.client.tracerconfigguard;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.util.concurrent.atomic.AtomicLong;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;
import zombie.iso.objects.IsoBulletTracerEffects;
import zombie.scripting.objects.AmmoType;

/**
 * Recovery for a tracer effect whose ammo type has no config entry, invoked from {@link
 * IsoBulletTracerEffectsCreateEffectAdvice} before the vanilla {@code createEffect} body runs.
 *
 * <p>Vanilla {@code createEffect} reads the per-ammo config with a plain {@code HashMap.get} and
 * dereferences it on the next line, while the sibling {@code getOrCreate} in the same class creates
 * the entry on a miss. The map is only populated from {@code Item.resolveItemTypes()}, which
 * silently skips a weapon script whose {@code AmmoType} names an unregistered id, whose ammo item
 * is not installed ({@code ScriptManager.resolveItemType} returns {@code "???."} + name rather than
 * null), or whose registered item key does not string-equal the module-qualified name. In
 * multiplayer the ammo type is read off the local copy of the shooter's equipped weapon, which
 * equipment sync can leave stale.
 *
 * <p>When the weapon is present and its ammo type is registered but missing from the map, the guard
 * seeds the entry through the public {@code load(AmmoType)} — the class's own defaults plus the
 * optional {@code media/effects} override file — and lets the vanilla body run, so the shot keeps
 * its tracer. When there is no attacking weapon or its ammo type is null the body is skipped and
 * {@code createEffect} returns null, which every caller already handles; vanilla would have thrown,
 * and, because the pooled {@code Effect} is allocated before the throw and only released after,
 * leaked one pool object per occurrence.
 *
 * <p>Each outcome warns once with the weapon's full type, which is the missing diagnostic: it names
 * the mod whose gun script is misconfigured.
 */
public class BulletTracerConfigGuard {

    /** Shots whose registered ammo type had no config entry; one was seeded from defaults. */
    public static final AtomicLong SEEDED = new AtomicLong();

    /** Shots whose shooter had no attacking weapon; the tracer was skipped. */
    public static final AtomicLong NO_WEAPON = new AtomicLong();

    /** Shots whose weapon has a null ammo type; the tracer was skipped. */
    public static final AtomicLong NO_AMMO_TYPE = new AtomicLong();

    /** Recoveries that threw; the tracer was skipped for that shot. */
    public static final AtomicLong FAILED = new AtomicLong();

    /** Returns {@code true} when the vanilla {@code createEffect} body must be skipped. */
    public static boolean onCreateEffect(Object selfObj, Object characterObj) {
        try {
            if (characterObj == null) {
                return false;
            }
            IsoBulletTracerEffects self = (IsoBulletTracerEffects) selfObj;
            IsoGameCharacter character = (IsoGameCharacter) characterObj;
            HandWeapon weapon = character.getAttackingWeapon();
            if (weapon == null) {
                if (NO_WEAPON.incrementAndGet() == 1) {
                    LOGGER.warn(
                            "IsoBulletTracerEffectsConfigNullGuardPatch: {} fired a tracer with no"
                                    + " attacking weapon; skipped the tracer",
                            character.getClass().getSimpleName());
                }
                return true;
            }
            AmmoType ammoType = weapon.getAmmoType();
            if (ammoType == null) {
                if (NO_AMMO_TYPE.incrementAndGet() == 1) {
                    LOGGER.warn(
                            "IsoBulletTracerEffectsConfigNullGuardPatch: weapon {} has a null ammo"
                                    + " type (its script names an unregistered AmmoType); skipped"
                                    + " the tracer",
                            weapon.getFullType());
                }
                return true;
            }
            if (self.getIsoBulletTracerEffectsConfigOptionsHashMap().containsKey(ammoType)) {
                return false;
            }
            self.load(ammoType);
            if (SEEDED.incrementAndGet() == 1) {
                LOGGER.warn(
                        "IsoBulletTracerEffectsConfigNullGuardPatch: ammo type {} of weapon {} had"
                                + " no tracer config (its script never resolved an ammo item);"
                                + " seeded defaults",
                        ammoType,
                        weapon.getFullType());
            }
            return false;
        } catch (Throwable t) {
            if (FAILED.incrementAndGet() == 1) {
                LOGGER.error(
                        "IsoBulletTracerEffectsConfigNullGuardPatch: recovery failed, skipping"
                                + " the tracer",
                        t);
            }
            return true;
        }
    }
}
