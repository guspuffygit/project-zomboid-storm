package io.pzstorm.storm.sound;

import io.pzstorm.storm.logging.StormLogger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import zombie.SandboxOptions;
import zombie.WorldSoundManager;
import zombie.core.math.PZMath;
import zombie.iso.IsoChunk;
import zombie.iso.IsoWorld;
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
 * <p>Each indexed sound remembers the chunk rectangle it was indexed into (a {@link Footprint}), so
 * every later touch is compared rectangle-to-rectangle (ATF profile 2026-08-26, 135 players: the
 * old radius-keyed bookkeeping cost ~0.7% of main in {@code ArrayList.remove} churn):
 *
 * <ul>
 *   <li>Vanilla re-calls {@code addSound} on a <em>live</em> repeating-sound instance every refresh
 *       interval. With an unchanged rectangle this is now an incremental pass over chunks loaded
 *       since the last index pass instead of a full remove-and-re-add of every chunk list.
 *   <li>{@link StormRepeatingSoundCoalescer} regrowing a slot's radius ({@link #refreshFootprint})
 *       only touches chunk lists when the radius change actually moves an 8-tile chunk boundary.
 *   <li>A genuinely changed rectangle is applied as a diff: removals only from chunks leaving the
 *       footprint, plain adds only to chunks entering it, and a {@code loadedFrame}-guarded add for
 *       the intersection (a chunk loaded since the previous pass may be missing the sound).
 * </ul>
 *
 * <p>Un-indexing happens at the top of {@code WorldSoundManager.update()} — for exactly the sounds
 * vanilla is about to remove and release to its object pool that tick ({@code life <= 0} at entry)
 * — and in {@code KillCell()}. The server never runs vanilla's chunk-side aging ({@code
 * IsoChunk.updateSounds()} sits inside the client-only branch of {@code update()}), so these two
 * hooks are the only removal paths needed. The sweep collects the union of expiring footprints'
 * chunks, then clears each touched chunk list with one in-place compaction pass keyed directly on
 * {@code sound.life <= 0} — under the held {@code soundList} lock that predicate is exactly
 * "collected this pass", with no per-element scratch-map probe (the old identity-map probe was
 * ~0.7% of main in the 2026-08-26 profile). {@code KillCell} clears every touched chunk list
 * outright.
 *
 * <p>Known divergence from vanilla server behavior, matching vanilla <em>client</em> behavior by
 * construction: a chunk loaded mid-life of a short-lived sound misses that sound for the remainder
 * of its ≤16-tick life, and listeners outside a sound's hearing footprint no longer see it at all
 * (the global scan had no distance cut for {@code getSoundZomb}'s source-identity match).
 *
 * <p>Always on; if indexing ever throws, {@link #readServerFlag()} flips permanently to {@code
 * true} so the three patched read methods fall back to the vanilla global-list scan. Stale entries
 * left in chunk lists after a failure are never read (nothing else reads {@code IsoChunk.soundList}
 * on a server, and chunk recycling clears the list).
 */
public final class StormServerChunkSoundIndex {

    private static volatile boolean failed;

    private static final IdentityHashMap<WorldSoundManager.WorldSound, Footprint> FOOTPRINTS =
            new IdentityHashMap<>();

    /**
     * Sweep scratch (identity semantics), dedup only — iteration always goes through {@link
     * #TOUCHED_LIST}, and {@link #resetTouched()} rebuilds the map after any oversized pass. {@code
     * IdentityHashMap}'s table never shrinks, so iterating or clearing it costs the <em>historical
     * maximum</em> pass size on every tick: one expiry of a siren-scale footprint (thousands of
     * chunks) left every later sweep walking a huge empty table (ATF profile 2026-08-27: 1.13% of
     * main in this iteration). Reset after every use so chunks aren't held.
     */
    private static IdentityHashMap<IsoChunk, Boolean> TOUCHED = new IdentityHashMap<>();

    /** Insertion-ordered view of {@link #TOUCHED} for O(entries) iteration. */
    private static final ArrayList<IsoChunk> TOUCHED_LIST = new ArrayList<>();

    /**
     * A pass that touched more chunks than this gets a fresh {@link #TOUCHED} map instead of {@code
     * clear()}, bounding the table capacity every later per-tick sweep pays to iterate.
     */
    private static final int TOUCHED_REBUILD_THRESHOLD = 1024;

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
                reindex(sound, footprintRadius(manager, sound));
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
                Iterator<Map.Entry<WorldSoundManager.WorldSound, Footprint>> it =
                        FOOTPRINTS.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<WorldSoundManager.WorldSound, Footprint> entry = it.next();
                    WorldSoundManager.WorldSound sound = entry.getKey();
                    if (sound.life <= 0) {
                        collectChunks(entry.getValue());
                        StormRepeatingSoundCoalescer.onSoundReleased(sound);
                        it.remove();
                    }
                }
                compactTouchedChunks();
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
                for (Footprint footprint : FOOTPRINTS.values()) {
                    collectChunks(footprint);
                }
                FOOTPRINTS.clear();
                StormRepeatingSoundCoalescer.clear();
                for (int c = 0, n = TOUCHED_LIST.size(); c < n; c++) {
                    TOUCHED_LIST.get(c).soundList.clear();
                }
                resetTouched();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * Regrows a live coalesced sound's base radius in place for {@link
     * StormRepeatingSoundCoalescer}, touching chunk lists only when the footprint rectangle
     * actually changes; {@code forceReindex} with an unchanged rectangle runs the incremental
     * {@link #addToChunksLoadedSince} pass instead. Caller holds the sound-list lock.
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
            int radiusMax =
                    footprintRadius(manager, sound.stresshumans, sound.stressAnimals, radius);
            sound.radius = radius;
            Footprint stored = FOOTPRINTS.get(sound);
            if (stored != null
                    && stored.sameRect(new Footprint(sound.x, sound.y, radiusMax, 0L))
                    && !forceReindex) {
                return;
            }
            reindex(sound, radiusMax);
        } catch (Throwable t) {
            fail(t);
            sound.radius = radius;
        }
    }

    /**
     * Rectangle-compared index pass for a sound at its current position: no stored footprint →
     * plain adds over the whole rectangle; unchanged rectangle → incremental adds for chunks loaded
     * since the last pass only; changed rectangle → diff (remove old∖new, add new∖old, guarded add
     * for the intersection).
     */
    private static void reindex(WorldSoundManager.WorldSound sound, int radiusMax) {
        Footprint fresh = new Footprint(sound.x, sound.y, radiusMax, currentFrame());
        Footprint stored = FOOTPRINTS.get(sound);
        if (stored != null && stored.sameRect(fresh)) {
            addToChunksLoadedSince(sound, stored);
            return;
        }
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        if (stored != null) {
            for (int xx = stored.chunkMinX; xx < stored.chunkMaxX; xx++) {
                for (int yy = stored.chunkMinY; yy < stored.chunkMaxY; yy++) {
                    if (fresh.containsChunk(xx, yy)) {
                        continue;
                    }
                    IsoChunk chunk = map.getChunk(xx, yy);
                    if (chunk != null) {
                        chunk.soundList.remove(sound);
                    }
                }
            }
        }
        for (int xx = fresh.chunkMinX; xx < fresh.chunkMaxX; xx++) {
            for (int yy = fresh.chunkMinY; yy < fresh.chunkMaxY; yy++) {
                IsoChunk chunk = map.getChunk(xx, yy);
                if (chunk == null) {
                    continue;
                }
                if (stored != null && stored.containsChunk(xx, yy)) {
                    // Intersection chunk: it already has the sound unless it (re)loaded since
                    // the pass that stamped stored.indexFrame — same-frame ambiguity and
                    // loadedFrame == 0 (published without doLoadGridsquare) need the
                    // membership check.
                    if ((chunk.loadedFrame >= stored.indexFrame || chunk.loadedFrame == 0)
                            && !containsIdentity(chunk.soundList, sound)) {
                        chunk.soundList.add(sound);
                    }
                } else {
                    chunk.soundList.add(sound);
                }
            }
        }
        FOOTPRINTS.put(sound, fresh);
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

    /**
     * The incremental form of a re-index whose footprint rectangle is unchanged: every chunk that
     * was already loaded at the last index pass got the sound then, so only chunks loaded since
     * ({@code chunk.loadedFrame >= indexFrame}) can be missing it. Those get a membership-guarded
     * add — a chunk loaded in the same frame as the pass that stamped {@code indexFrame} may or may
     * not have been covered by it, so presence must be checked. {@code loadedFrame == 0} (a chunk
     * published without {@code doLoadGridsquare}, e.g. via {@code setSoftResetChunk}) is treated as
     * newly loaded.
     */
    private static void addToChunksLoadedSince(
            WorldSoundManager.WorldSound sound, Footprint footprint) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        long since = footprint.indexFrame;
        long now = currentFrame();
        for (int xx = footprint.chunkMinX; xx < footprint.chunkMaxX; xx++) {
            for (int yy = footprint.chunkMinY; yy < footprint.chunkMaxY; yy++) {
                IsoChunk chunk = map.getChunk(xx, yy);
                if (chunk != null
                        && (chunk.loadedFrame >= since || chunk.loadedFrame == 0)
                        && !containsIdentity(chunk.soundList, sound)) {
                    chunk.soundList.add(sound);
                }
            }
        }
        footprint.indexFrame = now;
    }

    private static boolean containsIdentity(
            ArrayList<WorldSoundManager.WorldSound> list, WorldSoundManager.WorldSound sound) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == sound) {
                return true;
            }
        }
        return false;
    }

    /**
     * Frame stamp compared against {@code IsoChunk.loadedFrame}. Both come from {@code
     * IsoWorld.frameNo}, which increments once per server tick; 0 (world not up yet) makes every
     * loaded chunk look newly loaded, degrading to membership-guarded adds — safe, just slower.
     */
    private static long currentFrame() {
        IsoWorld world = IsoWorld.instance;
        return world == null ? 0L : world.getFrameNo();
    }

    /** Adds every currently loaded chunk of the footprint's rectangle to {@link #TOUCHED}. */
    private static void collectChunks(Footprint footprint) {
        ServerMap map = ServerMap.instance;
        if (map == null) {
            return;
        }
        for (int xx = footprint.chunkMinX; xx < footprint.chunkMaxX; xx++) {
            for (int yy = footprint.chunkMinY; yy < footprint.chunkMaxY; yy++) {
                IsoChunk chunk = map.getChunk(xx, yy);
                if (chunk != null && TOUCHED.put(chunk, Boolean.TRUE) == null) {
                    TOUCHED_LIST.add(chunk);
                }
            }
        }
    }

    /**
     * One in-place compaction pass per touched chunk clears every dead sound at once, keyed
     * directly on {@code life <= 0} — under the held {@code soundList} lock this is exactly the
     * predicate the collection loop used, so no scratch-map probe per element. Hand-rolled rather
     * than {@code removeIf} because this runs for every touched chunk every tick and {@code
     * ArrayList.removeIf} allocates a bound predicate plus a survivor bitset per call.
     */
    private static void compactTouchedChunks() {
        for (int c = 0, n = TOUCHED_LIST.size(); c < n; c++) {
            ArrayList<WorldSoundManager.WorldSound> list = TOUCHED_LIST.get(c).soundList;
            int size = list.size();
            int live = 0;
            for (int i = 0; i < size; i++) {
                WorldSoundManager.WorldSound sound = list.get(i);
                if (sound.life > 0) {
                    if (live != i) {
                        list.set(live, sound);
                    }
                    live++;
                }
            }
            if (live < size) {
                list.subList(live, size).clear();
            }
        }
        resetTouched();
    }

    /**
     * Empties the sweep scratch. {@code IdentityHashMap.clear()} is O(table capacity) and the table
     * never shrinks, so a pass over the threshold swaps in a fresh map — every later pass's
     * iteration is O(entries) via {@link #TOUCHED_LIST} and its clear is O(bounded capacity).
     */
    private static void resetTouched() {
        if (TOUCHED_LIST.size() > TOUCHED_REBUILD_THRESHOLD) {
            TOUCHED = new IdentityHashMap<>();
        } else {
            TOUCHED.clear();
        }
        TOUCHED_LIST.clear();
    }

    /**
     * Per-sound index record: the chunk rectangle the sound was indexed into ({@code min}
     * inclusive, {@code max} exclusive; exact copy of the client chunk-rectangle arithmetic in
     * {@code WorldSoundManager.addSound}) and the frame of the last pass guaranteed to have covered
     * every chunk loaded before it.
     */
    static final class Footprint {

        final int chunkMinX;
        final int chunkMinY;
        final int chunkMaxX;
        final int chunkMaxY;
        long indexFrame;

        Footprint(int x, int y, int radiusMax, long indexFrame) {
            this.chunkMinX = (x - radiusMax) / 8;
            this.chunkMinY = (y - radiusMax) / 8;
            this.chunkMaxX = (int) Math.ceil(((float) x + radiusMax) / 8.0F);
            this.chunkMaxY = (int) Math.ceil(((float) y + radiusMax) / 8.0F);
            this.indexFrame = indexFrame;
        }

        boolean sameRect(Footprint other) {
            return chunkMinX == other.chunkMinX
                    && chunkMinY == other.chunkMinY
                    && chunkMaxX == other.chunkMaxX
                    && chunkMaxY == other.chunkMaxY;
        }

        boolean containsChunk(int xx, int yy) {
            return xx >= chunkMinX && xx < chunkMaxX && yy >= chunkMinY && yy < chunkMaxY;
        }
    }

    private static void fail(Throwable t) {
        failed = true;
        FOOTPRINTS.clear();
        resetTouched();
        StormLogger.LOGGER.error(
                "StormServerChunkSoundIndex failed — reverting sound lookups to the vanilla"
                        + " global-list scan",
                t);
    }
}
