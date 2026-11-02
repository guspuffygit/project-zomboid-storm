package io.pzstorm.storm.patch.fixes;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.metrics.PvpHitsAlwaysDamageMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoLivingCharacter;
import zombie.network.GameServer;
import zombie.network.fields.hit.Hit;
import zombie.network.packets.hit.PlayerHitPlayerPacket;

/**
 * Server-side: every player-on-player hit that reaches the server does damage, gated on {@code
 * Storm.PvpHitsAlwaysDamage} (default off = vanilla).
 *
 * <p>The server rolls hit chance for player targets in {@code Hit.roll}. Under {@code
 * FirearmUseDamageChance = 3} a failed roll still lands, as a hit whose {@code HitData} has {@code
 * isIgnoreDamage} set, and {@code IsoGameCharacter.Hit} then skips the body damage. Before 42.21
 * that flag never reached player hits, so every hit the server received did damage. Clearing it on
 * entry to {@code PlayerHitPlayerPacket.process}, after the roll and before the damage, restores
 * that, so the server's own hit, the relay to other clients, and the combat log all see a normal
 * hit. Zombie and animal hits use other packets and are untouched.
 *
 * <p>{@code CombatManager.getHitData} also seeds {@code isIgnoreDamage} from the wielder's shove
 * state ({@code isIgnoreHitCountDamage}). A shove, or a stomp on a target who is not prone, is a
 * push with no body damage by design. Those hits keep the flag so the option only restores failed
 * rolls.
 */
public final class PvpHitsAlwaysDamage {

    public static final boolean DEFAULT_ENABLED = false;

    private static final long FAILURE_LOG_INTERVAL_MS = 5000L;
    private static final VarHandle IGNORE_DAMAGE = hitHandle("isIgnoreDamage", boolean.class);
    private static final VarHandle HIT_DATA = hitHandle("hitData", CombatManager.HitData.class);

    public static final LongAdder restored = new LongAdder();

    private static volatile boolean enabled = DEFAULT_ENABLED;
    private static long lastFailureLogMs;

    static {
        PvpHitsAlwaysDamageMetrics.init();
    }

    private PvpHitsAlwaysDamage() {}

    /**
     * Applies the {@code Storm.PvpHitsAlwaysDamage} sandbox option and pushes it to the gauge.
     * Single mutation point.
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setPvpHitsAlwaysDamage(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Runs on entry to {@code PlayerHitPlayerPacket.process}; acts on the server only. */
    public static void beforeProcess(Object packet, List<?> hits) {
        if (!enabled || !GameServer.server) {
            return;
        }
        try {
            if (isShoveHit(packet)) {
                return;
            }
            int cleared = clearIgnoreDamage(hits);
            if (cleared > 0) {
                restored.add(cleared);
            }
        } catch (Throwable t) {
            logFailure(t);
        }
    }

    /** True when the wielder's shove state, not a failed roll, set {@code isIgnoreDamage}. */
    static boolean isShoveHit(Object packet) {
        if (!(packet instanceof PlayerHitPlayerPacket hitPacket)) {
            return false;
        }
        IsoGameCharacter wielder = hitPacket.getCharacter();
        IsoGameCharacter target = hitPacket.target.getCharacter();
        if (!(wielder instanceof IsoLivingCharacter living) || target == null) {
            return false;
        }
        return isShoveHit(living.isDoShove(), living.isShoveStompAnim(), target.isProne());
    }

    /** Mirrors {@code CombatManager.isIgnoreHitCountDamage}. */
    static boolean isShoveHit(boolean doShove, boolean stompAnim, boolean targetProne) {
        return doShove && (!stompAnim || !targetProne);
    }

    /** Clears the rolled {@code isIgnoreDamage} on each hit and returns how many were set. */
    static int clearIgnoreDamage(List<?> hits) {
        if (hits == null || IGNORE_DAMAGE == null || HIT_DATA == null) {
            return 0;
        }
        int cleared = 0;
        for (Object o : hits) {
            if (!(o instanceof Hit hit)
                    || !(HIT_DATA.get(hit) instanceof CombatManager.HitData data)
                    || !data.isIgnoreDamage()) {
                continue;
            }
            HIT_DATA.set(
                    hit,
                    new CombatManager.HitData(
                            data.hitHead(),
                            data.hitLegs(),
                            false,
                            data.isPiercedTargetDamageReduction(),
                            data.isValidHitLocation()));
            IGNORE_DAMAGE.set(hit, false);
            cleared++;
        }
        return cleared;
    }

    private static VarHandle hitHandle(String name, Class<?> type) {
        try {
            return MethodHandles.privateLookupIn(Hit.class, MethodHandles.lookup())
                    .findVarHandle(Hit.class, name, type);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("Storm: Hit.{} is gone; PvpHitsAlwaysDamage is inert", name, e);
            return null;
        }
    }

    private static void logFailure(Throwable t) {
        long now = System.currentTimeMillis();
        if (now - lastFailureLogMs < FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastFailureLogMs = now;
        LOGGER.warn("Storm: PvP hit damage restore failed: {}", t.toString());
    }
}
