package io.pzstorm.storm.los;

import io.pzstorm.storm.cache.ServerLOSPlayerDataCache;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.metrics.PlayerLosFastPathMetrics;
import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;
import io.pzstorm.storm.spatial.StormChunkIndex;
import io.pzstorm.storm.spatial.StormObjectList;
import io.pzstorm.storm.spatial.StormSpatialIndex;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Stack;
import zombie.GameTime;
import zombie.MovingObjectUpdateScheduler;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoSurvivor;
import zombie.characters.IsoZombie;
import zombie.characters.Stats;
import zombie.core.math.PZMath;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoPhysicsObject;
import zombie.iso.IsoUtils;
import zombie.iso.LosUtil;
import zombie.network.ServerLOS;
import zombie.vehicles.BaseVehicle;

/**
 * Server-only replacement for the body of {@code IsoPlayer.updateLOS()}, wired in by {@code
 * IsoPlayerUpdateLOSFastPathPatch}. Realizes options A (strip server-dead work) and C (distance
 * cull) of {@code docs/LOS_OPTIMIZATION_FINDINGS.md}.
 *
 * <p>Vanilla walks every {@code IsoMovingObject} in the loaded cell (thousands on a busy server)
 * per player per tick and, for each, runs a {@code ServerLOS.isCouldSee} lookup plus a pile of
 * client-only bookkeeping (alpha targets, {@code musicZombies*} music-intensity fields, jump-scare
 * state) that no server-side code ever reads. This replacement:
 *
 * <ol>
 *   <li><b>Distance-culls first.</b> {@code ServerLOS.isCouldSee} reads a per-player visibility
 *       cube of {@code 96×96×LosUtil.sizeZ} squares anchored at the player position captured by the
 *       last LOS-thread scan ({@code PlayerData.px/py/pz - 48}); any square outside it reads {@code
 *       false}. An object whose {@code couldSee} is false produces <em>zero</em> server-visible
 *       effects in the vanilla loop body (only alpha writes, which are render-only), so objects
 *       outside the cube — widened by {@link #CULL_SLACK} for square-vs-float drift — are skipped
 *       before any cast / {@code getCurrentSquare} / visibility work. The cube half-extent is
 *       derived from the live {@code PlayerData.visible} array dimensions, not hardcoded.
 *   <li><b>Reads the visibility cube directly.</b> For surviving objects, {@code couldSee} is the
 *       same bounds-check-plus-array-read {@code ServerLOS.isCouldSee} performs, minus the
 *       per-object {@code findData} map lookup (the cube reference and its {@code px/py/pz} anchor
 *       are snapshotted once per call; they cannot change mid-call because the LOS thread only
 *       rewrites them while the player's {@code PlayerData.status} is {@code WaitingInLOS}, never
 *       during {@code BusyInMain}).
 *   <li><b>Keeps every server-consumed effect bit-identical:</b> {@code TestZombieSpotPlayer} on
 *       both the lit and could-see-but-not-lit branches, {@code TestIfSeen} and its {@code
 *       spottedList} / panic ({@code numVisibleZombies}) / boredom ({@code numSurvivorsInVicinity})
 *       accounting including the {@code vclose} tally, the {@code GameTime.setMultiplier(1.0F)}
 *       zombie-proximity speed reset, and the post-loop {@code lastSpotted} / {@code
 *       clearSpottedTimer} / {@code timeSinceLastStab} maintenance.
 * </ol>
 *
 * <p>Stripped (verified server-dead — see the findings doc and the greps recorded there): all
 * {@code setAlpha*} / {@code setTargetAlpha} rendering writes (every base-game reader is gated on
 * {@code !GameServer.server}, {@code GameClient.client}, or {@code isLocalPlayer()}), the {@code
 * isSeeEveryone()} debug-alpha branch, the dead {@code close} tally, the {@code musicZombies*}
 * per-object increments (all five consumers are {@code !GameServer.server}-gated; the pre-loop
 * zero-resets are kept so the fields read deterministically 0), the {@code lastSeenZombieTime} /
 * {@code ticksSinceSeenZombie} resets (zero Java readers of their getters in the base game), the
 * client-gated invisible-character and jump-scare-sound branches, and the {@code
 * UIManager.getSpeedControls()} call (already {@code !bServer}-gated in vanilla).
 *
 * <p>Vanilla behavior is restored wholesale with the {@code Storm.PlayerLosFastPath} sandbox option
 * (set {@code false}), automatically for any call where the player has no {@code
 * ServerLOS$PlayerData} cached yet (first tick after join), and permanently if the fast path ever
 * throws.
 *
 * <p><b>Candidate source.</b> When the shared per-tick {@link StormSpatialIndex} snapshot is
 * published for the current scheduler frame, the loop walks only the objects bucketed in the chunk
 * rectangle covering the (slack-widened) visibility cube plus {@link #SNAPSHOT_SLACK_CHUNKS} of
 * movement slack, instead of the whole {@code IsoCell.objectList}. The per-object cube cull and
 * every downstream check still run against live positions, so the result is identical to the full
 * walk for any object that moved less than a chunk since tick start; if the index is not ready
 * (rebuild failed this tick, or patch not woven) the full walk runs as before.
 *
 * <p>Single-threaded by design: {@code IsoPlayer.updateLOS()} only runs on the server main thread
 * (via {@code ServerLOS.updateLOS}), so the counters, scratch list and latch need no
 * synchronization.
 */
public final class StormPlayerLos {

    /** Default for {@code Storm.PlayerLosFastPath}: fast path on. */
    public static final boolean DEFAULT_ENABLED = true;

    /**
     * Extra squares of tolerance added around the visibility cube before culling. Covers an
     * object's {@code getCurrentSquare()} lagging its float position by a square (the cull uses
     * {@code fastfloor(getX())}; the vanilla visibility read uses the square's own coordinates).
     */
    private static final int CULL_SLACK = 1;

    /**
     * Chunks of tolerance added around the cube's chunk rectangle when querying the spatial index,
     * covering movement between the tick-start snapshot and this call.
     */
    private static final int SNAPSHOT_SLACK_CHUNKS = 1;

    /** Candidate objects for the current call; main-thread only, reused across calls. */
    private static final StormObjectList CANDIDATES = new StormObjectList(1024);

    /**
     * Kill switch, driven by the {@code Storm.PlayerLosFastPath} sandbox option through {@link
     * #setEnabled(boolean)}. Volatile because the sandbox applier may push updates from outside the
     * main thread; the per-call read is a single volatile load.
     */
    private static volatile boolean enabled = DEFAULT_ENABLED;

    /** Permanent revert-to-vanilla latch; set on the first {@link Throwable} out of the body. */
    private static boolean failed;

    private static volatile boolean initialized;

    // ServerLOS$PlayerData internals (the type is private; same bridge as StormServerLos).
    private static Field fPx;
    private static Field fPy;
    private static Field fPz;
    private static Field fVisible;

    /** {@code protected boolean IsoGameCharacter.TestIfSeen(int, IsoPlayer)}. */
    private static MethodHandle testIfSeen;

    private StormPlayerLos() {}

    /**
     * Applies the {@code Storm.PlayerLosFastPath} sandbox option ({@code false} = vanilla loop,
     * {@code true} = fast path) and pushes the applied value to the Prometheus gauge. Single
     * mutation point — sandbox apply and tests both funnel through here.
     *
     * @return the applied value
     */
    public static boolean setEnabled(boolean value) {
        enabled = value;
        StormPerformanceSandboxMetrics.setPlayerLosFastPath(value);
        return value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs the server-stripped {@code updateLOS} body for {@code playerObj}.
     *
     * @param playerObj the {@code IsoPlayer} ({@code @Advice.This}; typed {@code Object} so the
     *     advice never references the transform target).
     * @return {@code true} if the optimized body ran (the advice skips the vanilla body); {@code
     *     false} to fall through to vanilla (kill switch off, fast path failed, or no {@code
     *     PlayerData} cached for this player yet).
     */
    public static boolean runOptimized(Object playerObj) {
        if (failed || !enabled) {
            PlayerLosFastPathMetrics.recordVanilla();
            return false;
        }
        try {
            IsoPlayer player = (IsoPlayer) playerObj;
            if (ServerLOS.instance == null) {
                PlayerLosFastPathMetrics.recordVanilla();
                return false;
            }
            Object data = ServerLOSPlayerDataCache.get(player);
            if (data == null) {
                // Not cached yet (cache fills on the first vanilla findData call for this
                // player). Run vanilla this tick; its isCouldSee calls populate the cache.
                PlayerLosFastPathMetrics.recordVanilla();
                return false;
            }
            ensureInit();
            run(
                    player,
                    fPx.getInt(data),
                    fPy.getInt(data),
                    fPz.getInt(data),
                    (boolean[][][]) fVisible.get(data));
            return true;
        } catch (Throwable t) {
            failed = true;
            CANDIDATES.clear();
            StormLogger.LOGGER.error(
                    "StormPlayerLos failed — reverting to vanilla IsoPlayer.updateLOS", t);
            PlayerLosFastPathMetrics.recordVanilla();
            return false;
        }
    }

    /**
     * The server-relevant reduction of {@code IsoPlayer.updateLOS()} ({@code GameServer.server}
     * true, {@code GameClient.client} false). Every kept statement mirrors its vanilla counterpart
     * in order; comments name what vanilla does at each seam.
     */
    private static void run(IsoPlayer player, int px, int py, int pz, boolean[][][] visible)
            throws Throwable {
        Stats stats = player.getStats();
        Stack<IsoMovingObject> spottedList = player.getSpottedList();
        Stack<IsoMovingObject> lastSpotted = player.getLastSpotted();

        // Pre-loop resets — kept verbatim (numChasingZombies has server readers in
        // CombatManager; the musicZombies* resets keep those fields deterministically 0 now
        // that their per-object increments are stripped).
        spottedList.clear();
        stats.numVisibleZombies = 0;
        stats.setLastNumberChasingZombies(stats.numChasingZombies);
        stats.numChasingZombies = 0;
        stats.musicZombiesTargetingDistantNotMoving = 0;
        stats.musicZombiesTargetingNearbyNotMoving = 0;
        stats.musicZombiesTargetingDistantMoving = 0;
        stats.musicZombiesTargetingNearbyMoving = 0;
        stats.musicZombiesVisible = 0;
        player.setNumSurvivorsInVicinity(0);
        if (player.getCurrentSquare() == null) {
            PlayerLosFastPathMetrics.recordOptimized(0, 0, false);
            return;
        }

        int playerIndex = player.playerIndex;
        float locX = player.getX();
        float locY = player.getY();
        float locZ = player.getZ();
        int vclose = 0;
        long culled = 0;
        long processed = 0;

        // Visibility-cube geometry, exactly as ServerLOS.isCouldSee computes it: the cube is
        // visible.length × visible[0].length × LosUtil.sizeZ squares anchored at
        // (px - halfX, py - halfY, pz - LosUtil.sizeZ / 2). Vanilla hardcodes 96/48
        // (PD_SIZE_IN_SQUARES and its half); deriving from the array keeps this correct if the
        // cube is ever resized.
        int xDim = visible.length;
        int yDim = visible[0].length;
        int zDim = LosUtil.sizeZ;
        int minX = px - xDim / 2;
        int minY = py - yDim / 2;
        int minZ = pz - zDim / 2;

        // Vanilla adds the player itself to spottedList when the walk reaches it; order within
        // the Stack is arbitrary in vanilla (HashSet iteration), so adding self up front is
        // equivalent and keeps it present even if the snapshot missed a teleport.
        spottedList.add(player);
        boolean indexed = gatherCandidates(player, minX, minY, xDim, yDim);
        int candidateCount = CANDIDATES.size();

        for (int ci = 0; ci < candidateCount; ci++) {
            IsoMovingObject movingObject = (IsoMovingObject) CANDIDATES.get(ci);
            if (movingObject instanceof IsoPhysicsObject || movingObject instanceof BaseVehicle) {
                continue;
            }
            if (movingObject == player) {
                continue;
            }
            float movingObjectX = movingObject.getX();
            float movingObjectY = movingObject.getY();
            float movingObjectZ = movingObject.getZ();

            // Option C cull: outside the (slack-widened) cube, isCouldSee cannot be true, and
            // a couldSee=false object produces no server-visible effect in the vanilla body.
            int cullX = PZMath.fastfloor(movingObjectX) - minX;
            int cullY = PZMath.fastfloor(movingObjectY) - minY;
            if (cullX < -CULL_SLACK
                    || cullX >= xDim + CULL_SLACK
                    || cullY < -CULL_SLACK
                    || cullY >= yDim + CULL_SLACK) {
                culled++;
                continue;
            }
            processed++;

            float distanceToMovingObject =
                    IsoUtils.DistanceTo(movingObjectX, movingObjectY, locX, locY);
            // Vanilla: close++ if dist < 20 — local never read, dropped.

            IsoGridSquare chrCurrentSquare = movingObject.getCurrentSquare();
            if (chrCurrentSquare == null) {
                continue;
            }
            // Vanilla: isSeeEveryone() debug alpha — render-only, dropped.
            IsoGameCharacter movingCharacter =
                    movingObject instanceof IsoGameCharacter c ? c : null;
            IsoZombie movingZombie = movingCharacter instanceof IsoZombie z ? z : null;
            // Vanilla also derives movingAnimal / movingPlayer here; on the server both feed
            // only alpha writes and client-gated branches, so the casts are dropped.
            if (movingZombie != null && movingZombie.isReanimatedForGrappleOnly()) {
                // Vanilla: alpha mirror of the grappler, then no spotting work. Preserve the
                // control flow (no spotting), drop the alpha write.
                continue;
            }
            // Vanilla: GameClient.client invisible-character branch — dead on the server.

            // couldSee — same read ServerLOS.isCouldSee performs against this PlayerData.
            int sqX = chrCurrentSquare.x - minX;
            int sqY = chrCurrentSquare.y - minY;
            int sqZ = chrCurrentSquare.z - minZ;
            boolean couldSee =
                    sqX >= 0
                            && sqX < xDim
                            && sqY >= 0
                            && sqY < yDim
                            && sqZ >= 0
                            && sqZ < zDim
                            && visible[sqX][sqY][sqZ];
            // Vanilla: canSee = couldSee; the branches that could change it are bClient /
            // !bServer gated, so on the server the visible/not-visible gate reduces to
            // (!isAsleep() && couldSee). getDetectionRange() is side-effect-free, so not
            // evaluating it on the couldSee=false path is outcome-identical.
            if (player.isAsleep() || !couldSee) {
                // Vanilla: attached-animal targetAlpha write — render-only, dropped.
                if (couldSee) {
                    player.TestZombieSpotPlayer(movingObject);
                }
            } else {
                player.TestZombieSpotPlayer(movingObject);
                if (movingCharacter == null) {
                    continue;
                }
                boolean isVisibleToPlayer =
                        (boolean) testIfSeen.invokeExact(movingCharacter, playerIndex, player);
                if (isVisibleToPlayer) {
                    if (movingCharacter instanceof IsoSurvivor) {
                        player.setNumSurvivorsInVicinity(player.getNumSurvivorsInVicinity() + 1);
                    }
                    if (movingZombie != null) {
                        // Vanilla: lastSeenZombieTime = 0.0 — no readers, dropped.
                        if (movingObjectZ >= locZ - 1.0F
                                && distanceToMovingObject < 7.0F
                                && !movingZombie.ghost
                                && !movingZombie.isFakeDead()
                                && chrCurrentSquare.getRoom()
                                        == player.getCurrentSquare().getRoom()) {
                            // Vanilla: ticksSinceSeenZombie = 0 — no readers, dropped.
                            stats.numVisibleZombies++;
                        }
                        if (distanceToMovingObject < 3.0F) {
                            vclose++;
                        }
                        // Vanilla: musicZombies* accounting — all consumers are
                        // !GameServer.server-gated, dropped.
                    }
                    spottedList.add(movingCharacter);
                    // Vanilla: two setTargetAlpha writes — render-only, dropped.
                    float maxdist = 4.0F;
                    if (stats.numVisibleZombies > 4) {
                        maxdist = 7.0F;
                    }
                    if (distanceToMovingObject < maxdist
                            && movingCharacter instanceof IsoZombie
                            && PZMath.fastfloor(movingObjectZ) == PZMath.fastfloor(locZ)
                            && !player.isGhostMode()) {
                        // Vanilla runs this on the server (only the UIManager.getSpeedControls()
                        // call is !bServer-gated): a nearby zombie resets any fast-forward
                        // multiplier.
                        GameTime.instance.setMultiplier(1.0F);
                    }
                    if (distanceToMovingObject < maxdist
                            && movingCharacter instanceof IsoZombie
                            && PZMath.fastfloor(movingObjectZ) == PZMath.fastfloor(locZ)
                            && !lastSpotted.contains(movingCharacter)) {
                        stats.numVisibleZombies += 2;
                    }
                }
            }
            // Vanilla: dist<2 setAlpha promotion — render-only, dropped.
        }

        // Vanilla: jump-scare sound block — !bServer-gated, dead here.
        if (stats.numVisibleZombies > 0) {
            player.setTimeSinceLastStab(0.0F);
        }
        if (player.getTimeSinceLastStab() < 600.0F) {
            player.setTimeSinceLastStab(
                    player.getTimeSinceLastStab()
                            + GameTime.getInstance().getThirtyFPSMultiplier());
        }

        int actualSpotted = 0;
        for (int n = 0; n < spottedList.size(); n++) {
            if (!lastSpotted.contains(spottedList.get(n))) {
                lastSpotted.add(spottedList.get(n));
            }
            if (spottedList.get(n) instanceof IsoZombie) {
                actualSpotted++;
            }
        }
        if (player.getClearSpottedTimer() <= 0 && actualSpotted == 0) {
            lastSpotted.clear();
            player.setClearSpottedTimer(1000);
        } else {
            player.setClearSpottedTimer(player.getClearSpottedTimer() - 1);
        }
        stats.lastNumVisibleZombies = stats.numVisibleZombies;
        stats.lastVeryCloseZombies = vclose;

        CANDIDATES.clear();
        PlayerLosFastPathMetrics.recordOptimized(culled, processed, indexed);
    }

    /**
     * Fills {@link #CANDIDATES} with the objects this call must examine: the spatial-index
     * snapshot's contents for the chunk rectangle around the visibility cube when a snapshot for
     * the current frame is published, otherwise the whole {@code objectList} (vanilla's set).
     *
     * @return {@code true} if the index supplied the candidates
     */
    private static boolean gatherCandidates(
            IsoPlayer player, int minX, int minY, int xDim, int yDim) {
        CANDIDATES.clear();
        long frame = MovingObjectUpdateScheduler.instance.getFrameCounter();
        if (StormSpatialIndex.isReadyFor(frame)) {
            int cx0 = StormChunkIndex.chunkOf(minX - CULL_SLACK) - SNAPSHOT_SLACK_CHUNKS;
            int cy0 = StormChunkIndex.chunkOf(minY - CULL_SLACK) - SNAPSHOT_SLACK_CHUNKS;
            int cx1 = StormChunkIndex.chunkOf(minX + xDim + CULL_SLACK) + SNAPSHOT_SLACK_CHUNKS;
            int cy1 = StormChunkIndex.chunkOf(minY + yDim + CULL_SLACK) + SNAPSHOT_SLACK_CHUNKS;
            StormSpatialIndex.collectChunkRect(
                    cx0,
                    cy0,
                    cx1,
                    cy1,
                    StormChunkIndex.MASK_ALL & ~StormChunkIndex.MASK_VEHICLE,
                    CANDIDATES);
            return true;
        }
        for (IsoMovingObject movingObject : player.getCell().getObjectList()) {
            CANDIDATES.add(movingObject);
        }
        return false;
    }

    private static void ensureInit() throws ReflectiveOperationException {
        if (initialized) {
            return;
        }
        synchronized (StormPlayerLos.class) {
            if (initialized) {
                return;
            }
            Class<?> playerData = Class.forName("zombie.network.ServerLOS$PlayerData");
            fPx = playerData.getDeclaredField("px");
            fPy = playerData.getDeclaredField("py");
            fPz = playerData.getDeclaredField("pz");
            fVisible = playerData.getDeclaredField("visible");
            fPx.setAccessible(true);
            fPy.setAccessible(true);
            fPz.setAccessible(true);
            fVisible.setAccessible(true);

            Method m =
                    IsoGameCharacter.class.getDeclaredMethod(
                            "TestIfSeen", int.class, IsoPlayer.class);
            m.setAccessible(true);
            testIfSeen = MethodHandles.lookup().unreflect(m);

            initialized = true;
            StormLogger.LOGGER.info("StormPlayerLos: ServerLOS reflection bridge initialized");
        }
    }
}
