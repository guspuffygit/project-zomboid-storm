package io.pzstorm.storm.sound;

import io.pzstorm.storm.logging.StormLogger;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.SandboxOptions;
import zombie.WorldSoundManager;
import zombie.core.math.PZMath;
import zombie.iso.IsoChunk;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/**
 * Server-side per-chunk {@code WorldSound} index, wired in by {@code
 * WorldSoundServerChunkIndexPatch}.
 *
 * <p>Vanilla populates {@code IsoChunk.soundList} only on the client ({@code
 * WorldSoundManager.addSound} is gated on {@code !GameServer.server}), so on a dedicated server
 * every animal ({@code getSoundAnimal}, per animal per tick) and every zombie sound query ({@code
 * getSoundZomb}, {@code getBiggestSoundZomb}) walks the entire global {@code soundList}. This class
 * maintains the same index server-side with the exact client footprint formula (sandbox hearing
 * mapping, animal-stress multiplier, {@code getHearingMultiplier}, radius-derived chunk rectangle),
 * but resolves chunks through {@link ServerMap#getChunk} — the vanilla client path ({@code
 * IsoCell.getChunk}) iterates per-player chunk maps and always returns {@code null} on a dedicated
 * server.
 *
 * <p>The footprint radius of each sound is remembered at add time so removal clears the identical
 * chunk rectangle even if sandbox options are pushed mid-life. Removal happens at the top of {@code
 * WorldSoundManager.update()} — for exactly the sounds vanilla is about to remove and release to
 * its object pool that tick ({@code life <= 0} at entry) — and in {@code KillCell()}. The server
 * never runs vanilla's chunk-side aging ({@code IsoChunk.updateSounds()} sits inside the
 * client-only branch of {@code update()}), so these two hooks are the only removal paths needed.
 *
 * <p>Un-indexing is batched (ATF profile 2026-08-24: the per-sound {@code ArrayList.remove} against
 * chunk lists averaging 104 entries cost ~3.5 ms of a ~62 ms tick at ~460 expiries across ~138
 * chunks each): the sweep first collects every expiring sound into an identity set and the union of
 * their footprint chunks, then clears each touched chunk with a single {@code removeIf} pass —
 * O(touched chunk lists) per tick instead of O(expiring sounds × footprint chunks × list length).
 *
 * <p>The expiry sweep also drives {@link StormRepeatingSoundCoalescer}'s slot bookkeeping, and
 * {@link #onSoundAdded} registers new repeating sounds as coalescer slots — both under the same
 * {@code manager.soundList} lock as the index itself. {@link #refreshFootprint} lets the coalescer
 * regrow a live slot's radius, touching chunk lists only when the chunk rectangle actually changes
 * (plus a forced re-index every 16th refresh so newly loaded chunks pick up a long-lived slot with
 * the same ≤16-tick staleness bound a vanilla copy stream has).
 *
 * <p>Known divergence from vanilla server behavior, matching vanilla <em>client</em> behavior by
 * construction: a chunk loaded mid-life of a sound misses that sound for the remainder of its
 * ≤16-tick life, and listeners outside a sound's hearing footprint no longer see it at all (the
 * global scan had no distance cut for {@code getSoundZomb}'s source-identity match).
 *
 * <p>Always on; if indexing ever throws, {@link #readServerFlag()} flips permanently to {@code
 * true} so the three patched read methods fall back to the vanilla global-list scan. Stale entries
 * left in chunk lists after a failure are never read (nothing else reads {@code IsoChunk.soundList}
 * on a server, and chunk recycling clears the list).
 */
public final class StormServerChunkSoundIndex {

    private static volatile boolean failed;

    private static final IdentityHashMap<WorldSoundManager.WorldSound, Integer> FOOTPRINTS =
            new IdentityHashMap<>();

    /** Sweep scratch (identity semantics); cleared after every use so pooled sounds aren't held. */
    private static final IdentityHashMap<WorldSoundManager.WorldSound, Boolean> DEAD =
            new IdentityHashMap<>();

    private static final IdentityHashMap<IsoChunk, Boolean> TOUCHED = new IdentityHashMap<>();

    private StormServerChunkSoundIndex() {}

    /**
     * Substituted for the {@code GameServer.server} field read inside {@code getSoundZomb}, {@code
     * getSoundAnimal} and {@code getBiggestSoundZomb}: {@code false} steers them onto the vanilla
     * client branch (per-chunk sound lists), {@code true} restores the vanilla server branch
     * (global scan) — the permanent fallback once indexing has failed.
     */
    public static boolean readServerFlag() {
        return failed;
    }

    /** Indexes a freshly added sound; called from exit advice on the 13-arg {@code addSound}. */
    public static void onSoundAdded(Object soundObj) {
        if (failed || soundObj == null || !GameServer.server) {
            return;
        }
        try {
            WorldSoundManager.WorldSound sound = (WorldSoundManager.WorldSound) soundObj;
            WorldSoundManager manager = WorldSoundManager.instance;
            synchronized (manager.soundList) {
                Integer stale = FOOTPRINTS.remove(sound);
                if (stale != null) {
                    forEachChunk(sound, stale, false);
                }
                int radiusMax = footprintRadius(manager, sound);
                forEachChunk(sound, radiusMax, true);
                FOOTPRINTS.put(sound, radiusMax);
                StormRepeatingSoundCoalescer.onSoundCreated(sound);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Un-indexes every sound vanilla's {@code update()} sweep is about to remove and release
     * ({@code life <= 0} at entry); called from enter advice on {@code update()} so it runs
     * strictly before the pooled instances can be recycled.
     */
    public static void onUpdateStart(Object managerObj) {
        if (failed || !GameServer.server) {
            return;
        }
        try {
            WorldSoundManager manager = (WorldSoundManager) managerObj;
            synchronized (manager.soundList) {
                Iterator<Map.Entry<WorldSoundManager.WorldSound, Integer>> it =
                        FOOTPRINTS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<WorldSoundManager.WorldSound, Integer> entry = it.next();
                    WorldSoundManager.WorldSound sound = entry.getKey();
                    if (sound.life <= 0) {
                        DEAD.put(sound, Boolean.TRUE);
                        collectChunks(sound, entry.getValue());
                        StormRepeatingSoundCoalescer.onSoundReleased(sound);
                        it.remove();
                    }
                }
                removeDeadFromTouchedChunks();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Un-indexes everything; called from enter advice on {@code KillCell()} (world teardown). */
    public static void onKillCell(Object managerObj) {
        if (failed || !GameServer.server) {
            return;
        }
        try {
            WorldSoundManager manager = (WorldSoundManager) managerObj;
            synchronized (manager.soundList) {
                for (Map.Entry<WorldSoundManager.WorldSound, Integer> entry :
                        FOOTPRINTS.entrySet()) {
                    DEAD.put(entry.getKey(), Boolean.TRUE);
                    collectChunks(entry.getKey(), entry.getValue());
                }
                FOOTPRINTS.clear();
                StormRepeatingSoundCoalescer.clear();
                removeDeadFromTouchedChunks();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Regrows a live coalesced sound's base radius in place for {@link
     * StormRepeatingSoundCoalescer}, re-indexing only when the chunk rectangle changes or {@code
     * forceReindex} demands a staleness-bounding refresh. The coalescer only ever refreshes at the
     * sound's existing position, so the rectangle depends solely on the footprint radius. Caller
     * holds the sound-list lock.
     */
    static void refreshFootprint(
            WorldSoundManager manager,
            WorldSoundManager.WorldSound sound,
            int radius,
            boolean forceReindex) {
        if (failed) {
            sound.radius = radius;
            return;
        }
        // A throw mid-reindex must latch THIS class's failure (not just the coalescer's): a
        // half-updated chunk index is only harmless once readServerFlag() reroutes every reader
        // back to the global scan.
        try {
            Integer stored = FOOTPRINTS.get(sound);
            int radiusMax =
                    footprintRadius(manager, sound.stresshumans, sound.stressAnimals, radius);
            if (stored != null && stored == radiusMax && !forceReindex) {
                sound.radius = radius;
                return;
            }
            if (stored != null) {
                forEachChunk(sound, stored, false);
            }
            sound.radius = radius;
            forEachChunk(sound, radiusMax, true);
            FOOTPRINTS.put(sound, radiusMax);
        } catch (Throwable t) {
            fail(t);
            sound.radius = radius;
        }
    }

    // Exact copy of the client footprint-radius expression in WorldSoundManager.addSound.
    private static int footprintRadius(
            WorldSoundManager manager, WorldSoundManager.WorldSound sound) {
        return footprintRadius(manager, sound.stresshumans, sound.stressAnimals, sound.radius);
    }

    private static int footprintRadius(
            WorldSoundManager manager, boolean stresshumans, boolean stressAnimals, int radius) {
        int hearing = SandboxOptions.instance.lore.hearing.getValue();
        if (hearing == 4) {
            hearing = 1;
        }
        if (hearing == 5) {
            hearing = 2;
        }
        float animalHearingMultiplier = !stresshumans && !stressAnimals ? 1.0F : 3.0F;
        float zombieHearingMultiplier = manager.getHearingMultiplier(hearing);
        float radiusMultiplier = PZMath.max(animalHearingMultiplier, zombieHearingMultiplier);
        return (int) PZMath.ceil(radius * radiusMultiplier);
    }

    // Exact copy of the client chunk-rectangle arithmetic in WorldSoundManager.addSound, but
    // resolving chunks through ServerMap (IsoCell.getChunk is always null on a dedicated server).
    private static void forEachChunk(
            WorldSoundManager.WorldSound sound, int radiusMax, boolean add) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        int chunkMinX = (sound.x - radiusMax) / 8;
        int chunkMinY = (sound.y - radiusMax) / 8;
        int chunkMaxX = (int) Math.ceil(((float) sound.x + radiusMax) / 8.0F);
        int chunkMaxY = (int) Math.ceil(((float) sound.y + radiusMax) / 8.0F);
        for (int xx = chunkMinX; xx < chunkMaxX; xx++) {
            for (int yy = chunkMinY; yy < chunkMaxY; yy++) {
                IsoChunk chunk = map.getChunk(xx, yy);
                if (chunk != null) {
                    if (add) {
                        chunk.soundList.add(sound);
                    } else {
                        chunk.soundList.remove(sound);
                    }
                }
            }
        }
    }

    /** Adds every currently loaded chunk of the sound's stored footprint to {@link #TOUCHED}. */
    private static void collectChunks(WorldSoundManager.WorldSound sound, int radiusMax) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        int chunkMinX = (sound.x - radiusMax) / 8;
        int chunkMinY = (sound.y - radiusMax) / 8;
        int chunkMaxX = (int) Math.ceil(((float) sound.x + radiusMax) / 8.0F);
        int chunkMaxY = (int) Math.ceil(((float) sound.y + radiusMax) / 8.0F);
        for (int xx = chunkMinX; xx < chunkMaxX; xx++) {
            for (int yy = chunkMinY; yy < chunkMaxY; yy++) {
                IsoChunk chunk = map.getChunk(xx, yy);
                if (chunk != null) {
                    TOUCHED.put(chunk, Boolean.TRUE);
                }
            }
        }
    }

    /** One {@code removeIf} pass per touched chunk clears every dead sound at once. */
    private static void removeDeadFromTouchedChunks() {
        if (!DEAD.isEmpty()) {
            for (IsoChunk chunk : TOUCHED.keySet()) {
                chunk.soundList.removeIf(DEAD::containsKey);
            }
        }
        DEAD.clear();
        TOUCHED.clear();
    }

    private static void fail(Throwable t) {
        failed = true;
        FOOTPRINTS.clear();
        DEAD.clear();
        TOUCHED.clear();
        StormLogger.LOGGER.error(
                "StormServerChunkSoundIndex failed — reverting sound lookups to the vanilla"
                        + " global-list scan",
                t);
    }
}
