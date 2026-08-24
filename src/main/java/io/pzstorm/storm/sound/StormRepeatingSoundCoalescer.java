package io.pzstorm.storm.sound;

import io.pzstorm.storm.logging.StormLogger;
import java.util.IdentityHashMap;
import zombie.Lua.LuaEventManager;
import zombie.WorldSoundManager;
import zombie.iso.FishSchoolManager;
import zombie.iso.IsoUtils;
import zombie.network.GameServer;
import zombie.popman.ZombiePopulationManager;

/**
 * Server-side coalescing of repeating {@code WorldSound}s, wired in by {@code
 * WorldSoundServerChunkIndexPatch}.
 *
 * <p>A repeating emitter (running engine, generator, washer, siren) calls {@code
 * WorldSoundManager.addSound} every tick, and each call allocates a fresh pooled {@code WorldSound}
 * with {@code life = 16} — so one steady noise is represented by ~16 live copies. Profiling on ATF
 * production (2026-08-24, 95 players) found 6,960 of 7,415 live sounds were repeating (~435 actual
 * emitters), and keeping the per-chunk sound index up to date across ~460 births and ~460 expiries
 * per tick cost ~5 ms of a ~62 ms tick. This class collapses each steady emitter to ONE live sound:
 * when a repeating emission matches its source's current slot exactly, the slot sound's {@code
 * life} is reset in place and the vanilla body (pool allocation, list append, chunk indexing) is
 * skipped.
 *
 * <p><b>Coalescing predicate</b> — an emission refreshes the slot for (source identity, stress-flag
 * combination) only when the slot is alive and the emission is at the <em>same tile</em> with
 * radius/volume <em>at least</em> the slot's and identical {@code zombieIgnoreDist}/{@code
 * stressMod}. Anything else — a moving vehicle, a louder burst among steady small emissions ({@code
 * VehicleEngine.updateWorldSounds} randomly emits up to 3 larger-radius sounds beside its
 * every-tick one), a quieter emission after a burst — falls through to the untouched vanilla body:
 * the new copy becomes the slot and the old slot sound decays over its remaining ≤16 ticks exactly
 * like any vanilla copy. All zombie/animal readers take a max over live sounds, and a
 * greater-or-equal refresh only ever grows the slot at the same position, so the audible envelope
 * matches vanilla; moving sources keep their vanilla breadcrumb trail by never coalescing.
 *
 * <p><b>Per-tick side effects are preserved.</b> Vanilla's per-add work — the {@code OnWorldSound}
 * Lua event and fish noise (both fired from {@code WorldSound.init}), {@code
 * ZombiePopulationManager.addWorldSound} and the {@code GameServer.sendWorldSound} broadcast — runs
 * on every coalesced refresh too, so Lua mods, fish, native zombie attraction and connected clients
 * see the identical per-tick stream. (Deduplicating the broadcast is deliberately NOT done here:
 * clients age their own 16-tick copies, so suppressing re-sends would change client-side state.)
 *
 * <p><b>Stress stays vanilla-weighted.</b> {@code getStressFromSounds} SUMS contributions over
 * copies, so a steady emitter's 16 vanilla copies count 16 times. {@link #stressFromSounds} — the
 * server-side replacement wired by the same patch — multiplies each coalesced slot's contribution
 * by the copy count vanilla would have alive: {@code ceil(life / gap)} with {@code gap} the
 * observed emission interval (steady per-tick emitter: weight = {@code life} ≈ 15-16 while running,
 * decaying one per tick after it stops — exact except the emitter's first second). No vanilla
 * repeating stress-humans emitter with a non-null source emits slower than its sound's lifetime, so
 * in practice this path is exercised by per-tick emitters where the weight is exact. Non-coalesced
 * sounds keep weight 1, byte-identical to vanilla.
 *
 * <p>Known remaining divergences, both bounded: {@code FishSchoolManager.addSoundNoise} receives
 * the refreshed coordinates rather than a fresh allocation (same values, same cadence); and a
 * coalesced slot's {@code OnWorldSound} Lua event passes the same {@code WorldSound}-independent
 * primitives vanilla passes, so no Lua-visible difference exists there.
 *
 * <p>Kill switch: {@code -Dstorm.sound.coalesce=false}. Fail-soft: any throw latches {@link
 * #failed} permanently, clears the registry and reverts to the vanilla add path; already-coalesced
 * sounds then simply decay within 16 ticks (during which stress from them is under-weighted — the
 * only transient of a failure). Everything here runs under {@code synchronized
 * (manager.soundList)}, the same lock vanilla's add path holds.
 */
public final class StormRepeatingSoundCoalescer {

    private static final boolean DISABLED =
            "false".equals(System.getProperty("storm.sound.coalesce"));
    private static final int VANILLA_SOUND_LIFE = 16;

    private static volatile boolean failed;

    /** Per (source, flags) live slot; index = stress bits (animals|humans<<1|zombies<<2). */
    private static final IdentityHashMap<Object, Slot[]> BY_SOURCE = new IdentityHashMap<>();

    /** Current slot sounds only — decayed-out copies that lost their slot are not members. */
    private static final IdentityHashMap<WorldSoundManager.WorldSound, Slot> SLOTS =
            new IdentityHashMap<>();

    private static final class Slot {
        WorldSoundManager.WorldSound sound;
        Object source;
        int index;

        /**
         * Observed emission interval in ticks (1 = every tick), for stress copy-count weighting.
         */
        int gap = 1;

        /**
         * Refresh counter; every 16th refresh forces a chunk re-index so newly loaded chunks pick
         * the sound up with the same ≤16-tick staleness bound a vanilla copy stream has.
         */
        int refreshes;
    }

    private StormRepeatingSoundCoalescer() {}

    private static boolean enabled() {
        return !DISABLED && !failed;
    }

    /**
     * Called from enter advice on the 13-arg {@code addSound} body overload. Returns the refreshed
     * slot sound — making the advice skip the vanilla body and return it — or {@code null} to run
     * vanilla untouched.
     */
    public static Object tryCoalesce(
            Object managerObj,
            Object source,
            int x,
            int y,
            int z,
            int radius,
            int volume,
            float zombieIgnoreDist,
            float stressMod,
            boolean doSend,
            boolean repeating,
            short flags) {
        if (!enabled() || !GameServer.server || !repeating || source == null || radius <= 0) {
            return null;
        }
        try {
            WorldSoundManager manager = (WorldSoundManager) managerObj;
            WorldSoundManager.WorldSound s;
            synchronized (manager.soundList) {
                Slot[] slots = BY_SOURCE.get(source);
                Slot slot = slots == null ? null : slots[slotIndex(flags)];
                if (slot == null) {
                    return null;
                }
                s = slot.sound;
                if (s.life <= 0
                        || s.x != x
                        || s.y != y
                        || s.z != z
                        || radius < s.radius
                        || volume < s.volume
                        || zombieIgnoreDist != s.zombieIgnoreDist
                        || stressMod != s.stressMod) {
                    return null;
                }
                slot.gap = Math.max(1, Math.min(VANILLA_SOUND_LIFE, VANILLA_SOUND_LIFE - s.life));
                slot.refreshes++;
                boolean forceReindex = (slot.refreshes & 15) == 0;
                StormServerChunkSoundIndex.refreshFootprint(manager, s, radius, forceReindex);
                s.volume = volume;
                s.life = VANILLA_SOUND_LIFE;
                // The per-add side effects vanilla fires on this exact per-tick cadence: the Lua
                // event and fish noise from WorldSound.init, then the popman notification the
                // addSound body makes while still holding the lock.
                LuaEventManager.triggerEvent("OnWorldSound", x, y, z, radius, volume, source);
                FishSchoolManager.getInstance().addSoundNoise(x, y, radius / 6);
                ZombiePopulationManager.instance.addWorldSound(s, doSend);
            }
            if (doSend) {
                // Outside the lock, like vanilla. Clients age their own 16-tick copies, so the
                // per-tick broadcast must continue for client-side state to stay vanilla.
                GameServer.sendWorldSound(s, null);
            }
            return s;
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    /**
     * Registers a freshly created repeating sound as its (source, flags) slot, displacing any
     * previous slot sound (which keeps decaying as an ordinary vanilla copy). Called from {@link
     * StormServerChunkSoundIndex#onSoundAdded} under the sound-list lock.
     */
    static void onSoundCreated(WorldSoundManager.WorldSound sound) {
        if (!enabled() || !sound.repeating || sound.source == null) {
            return;
        }
        try {
            Slot[] slots = BY_SOURCE.computeIfAbsent(sound.source, k -> new Slot[8]);
            int index = slotIndex(sound);
            Slot previous = slots[index];
            if (previous != null) {
                SLOTS.remove(previous.sound);
            }
            Slot slot = new Slot();
            slot.sound = sound;
            slot.source = sound.source;
            slot.index = index;
            slots[index] = slot;
            SLOTS.put(sound, slot);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Forgets a sound the expiry sweep is about to release. Called from {@link
     * StormServerChunkSoundIndex#onUpdateStart} under the sound-list lock; no-op for decayed copies
     * that already lost their slot.
     */
    static void onSoundReleased(WorldSoundManager.WorldSound sound) {
        Slot slot = SLOTS.remove(sound);
        if (slot == null) {
            return;
        }
        Slot[] slots = BY_SOURCE.get(slot.source);
        if (slots != null && slots[slot.index] == slot) {
            slots[slot.index] = null;
            for (Slot remaining : slots) {
                if (remaining != null) {
                    return;
                }
            }
            BY_SOURCE.remove(slot.source);
        }
    }

    /** Forgets everything (world teardown). */
    static void clear() {
        BY_SOURCE.clear();
        SLOTS.clear();
    }

    /**
     * Server-side replacement for {@code WorldSoundManager.getStressFromSounds}, weighting each
     * coalesced slot by the copy count vanilla would have alive (see class doc). Returns the boxed
     * result, or {@code null} to fall through to the vanilla loop (coalescing disabled/failed —
     * with an empty registry every weight is 1, so the vanilla loop is then exact).
     */
    public static Object stressFromSounds(Object managerObj, int x, int y, int z) {
        if (!enabled()) {
            return null;
        }
        try {
            WorldSoundManager manager = (WorldSoundManager) managerObj;
            float ret = 0.0F;
            // Same unsynchronized main-thread iteration as the vanilla method body.
            for (int i = 0; i < manager.soundList.size(); i++) {
                WorldSoundManager.WorldSound sound = manager.soundList.get(i);
                if (sound.stresshumans && sound.radius != 0) {
                    float dist = IsoUtils.DistanceManhatten(x, y, sound.x, sound.y);
                    float delta = 1.0F - dist / sound.radius;
                    if (!(delta <= 0.0F)) {
                        if (delta > 1.0F) {
                            delta = 1.0F;
                        }
                        ret += delta * sound.stressMod * copyWeight(sound);
                    }
                }
            }
            return ret;
        } catch (Throwable t) {
            fail(t);
            return null;
        }
    }

    private static int copyWeight(WorldSoundManager.WorldSound sound) {
        Slot slot = SLOTS.get(sound);
        if (slot == null) {
            return 1;
        }
        return Math.max(1, (sound.life + slot.gap - 1) / slot.gap);
    }

    private static int slotIndex(short flags) {
        return flags & 7;
    }

    private static int slotIndex(WorldSoundManager.WorldSound sound) {
        return (sound.stressAnimals ? 1 : 0)
                | (sound.stresshumans ? 2 : 0)
                | (sound.stressZombies ? 4 : 0);
    }

    private static void fail(Throwable t) {
        failed = true;
        BY_SOURCE.clear();
        SLOTS.clear();
        StormLogger.LOGGER.error(
                "StormRepeatingSoundCoalescer failed — reverting to the vanilla per-tick"
                        + " WorldSound add path (already-coalesced sounds decay within 16 ticks)",
                t);
    }
}
