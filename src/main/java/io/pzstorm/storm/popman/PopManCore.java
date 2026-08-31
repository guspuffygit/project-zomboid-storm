package io.pzstorm.storm.popman;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * State and behaviour of the virtual-zombie population simulation, replacing the native {@code
 * popman} root object. Bookkeeping that the decompiled Java call sites pin exactly is implemented
 * here; the simulation proper is being filled in from {@code docs/re-popman/}.
 *
 * <p>Seven vanilla wrappers reach their native without the {@code !GameClient.client} guard —
 * {@code readyToPause}, {@code shouldWait}, {@code updateThread}, {@code setAggroTarget}, both
 * {@code createHorde*} and {@code updateLoadedAreas} — and {@code IngameState} calls {@code
 * readyToPause()} unconditionally on MP clients, where {@code n_init} never ran. The DLL tolerates
 * that, so those paths must stay inert before {@link #init} rather than fail.
 */
public final class PopManCore {

    /**
     * The two things the simulation cannot supply for itself: the map, and somewhere to put the
     * save files. Both arrive from the game, so they are injected rather than constructed here.
     */
    public interface Environment {

        PopManWorld world();

        /** The directory that holds the {@code zpop} folder, or null to run without saving. */
        Path saveDirectory();

        /**
         * Asks the pathfinder whether repopulation can walk zombies from one square to another. The
         * answer comes back through {@link #completePath}.
         */
        void requestPath(int fromX, int fromY, int toX, int toY, PopManRepopulateTask task);

        /** Runs on the worker at the top of every tick, before the simulation reads the map. */
        default void beforeTick() {}

        /** The switches {@code MapCollisionData.setGameState} writes; shared with the map. */
        default PopManGameState gameState() {
            return new PopManGameState();
        }
    }

    /**
     * What runs before the game binds a real map: a world that is solid everywhere, so nothing can
     * spawn and nothing can move, rather than a null that would take the worker thread down.
     */
    public static final Environment UNBOUND =
            new Environment() {
                @Override
                public PopManWorld world() {
                    return new PopManWorld() {
                        @Override
                        public int squareFlags(int squareX, int squareY) {
                            return PopManMap.BIT_SOLID;
                        }

                        @Override
                        public int densityByte(int chunkX, int chunkY) {
                            return PopManPopulation.NO_DENSITY_DATA;
                        }
                    };
                }

                @Override
                public Path saveDirectory() {
                    return null;
                }

                @Override
                public void requestPath(int fx, int fy, int tx, int ty, PopManRepopulateTask t) {}
            };

    private final PopManConfig config = new PopManConfig();
    private final PopManHandoff handoff = new PopManHandoff();
    private final WorldSoundList worldSounds = new WorldSoundList();

    private Environment environment = UNBOUND;

    private final List<PopManGroup> groups = new ArrayList<>();
    private PopManMap map;
    private PopManCellMap cells;
    private PopManStore store;
    private PopManSpawnSource spawnSource;
    private PopManRepopulation repopulation;
    private PopManGrouping grouping;
    private PopManHordeSpawn hordeSpawn;
    private PopManTargeting targeting;
    private PopManGroupTick groupTick;

    /** Worker-side copy: the input frame only carries it on the ticks it changed. */
    private float speedMultiplier = 1.0F;

    private boolean client;
    private boolean server;
    private int minX;
    private int minY;
    private int width;
    private int height;
    private boolean stopped = true;

    private final List<int[]> spawnOrigins = new ArrayList<>();
    private final List<PopManZombie> stagedRealZombies = new ArrayList<>();
    private String[] outfitNames = new String[0];
    private final Map<String, Integer> outfitIndex = new LinkedHashMap<>();

    /** Steady, not wall time: the native clock was {@code _Xtime_get_ticks()}. */
    private LongSupplier clockNanos = System::nanoTime;

    /**
     * Worker-side clock. The input frame only carries the time when it actually changed, so a tick
     * that publishes nothing must leave the worker's copy standing.
     */
    private int worldAgeHours;

    private float lastTimeMultiplier = Float.NaN;
    private double lastWorldAgeHours = Double.NaN;

    public PopManConfig config() {
        return config;
    }

    public PopManHandoff handoff() {
        return handoff;
    }

    public WorldSoundList worldSounds() {
        return worldSounds;
    }

    void setClockNanos(LongSupplier clockNanos) {
        this.clockNanos = clockNanos;
    }

    private long nowMs() {
        return clockNanos.getAsLong() / 1_000_000L;
    }

    public boolean isStopped() {
        return stopped;
    }

    public boolean isClient() {
        return client;
    }

    public boolean isServer() {
        return server;
    }

    public int getMinX() {
        return minX;
    }

    public int getMinY() {
        return minY;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Flattened {@code x,y,w,h} quads, in the order they were registered. */
    public int[] getSpawnOrigins() {
        int[] flat = new int[spawnOrigins.size() * 4];
        int at = 0;
        for (int[] origin : spawnOrigins) {
            System.arraycopy(origin, 0, flat, at, 4);
            at += 4;
        }
        return flat;
    }

    public String[] getOutfitNames() {
        return outfitNames.clone();
    }

    /** Index a saved zombie's descriptor refers to, or {@code null} if the outfit is gone. */
    public Integer outfitIndex(String lowercasedName) {
        return outfitIndex.get(lowercasedName);
    }

    /**
     * Native {@code n_init} returns immediately when {@code isClient}, leaving the whole subsystem
     * inert on an MP client. Vanilla only reaches this under {@code !GameClient.client} so the flag
     * is always false in practice, but the parity matters for the paths that call in unguarded.
     */
    /** Must be called before {@link #init}; afterwards the simulation is already built. */
    public void setEnvironment(Environment environment) {
        this.environment = environment == null ? UNBOUND : environment;
    }

    public PopManMap map() {
        return map;
    }

    public PopManCellMap cells() {
        return cells;
    }

    public List<PopManGroup> groups() {
        return groups;
    }

    /** Null until {@link #init}; the debug renderer draws its last flood fill. */
    public PopManRepopulation repopulation() {
        return repopulation;
    }

    /** Null until {@link #init}; the map owns it, so the game binds it after building. */
    public PopManGameState gameState() {
        return map == null ? null : map.gameState();
    }

    /** Worker-side world age, whole hours — what every clock in the simulation is compared to. */
    public double worldAgeHours() {
        return worldAgeHours;
    }

    public void init(
            boolean isClient, boolean isServer, int minX, int minY, int width, int height) {
        this.client = isClient;
        this.server = isServer;
        if (isClient) {
            return;
        }
        this.minX = minX;
        this.minY = minY;
        this.width = width;
        this.height = height;
        buildSimulation();
        this.nextSaveMs = nowMs() + AUTOSAVE_MS;
        this.stopped = false;
    }

    private void buildSimulation() {
        map = new PopManMap(environment.world(), environment.gameState(), new PopManRandom());
        map.setWorldBounds(minX, minY, width, height);
        map.setServer(server);

        Path saveDirectory = environment.saveDirectory();
        store =
                saveDirectory == null
                        ? null
                        : new PopManStore(saveDirectory, () -> List.of(outfitNames));
        cells = new PopManCellMap(config, map, store == null ? cell -> false : store::load);

        spawnSource = new PopManSpawnSource(map);
        for (int[] origin : spawnOrigins) {
            spawnSource.add(origin[0], origin[1], origin[2], origin[3]);
        }
        repopulation =
                new PopManRepopulation(
                        config, map, cells, spawnSource::pick, environment::requestPath);
        grouping = new PopManGrouping(config, map, cells, groups);
        hordeSpawn = new PopManHordeSpawn(map, groups::add);
        targeting = new PopManTargeting(map);
        groupTick = new PopManGroupTick(config, map, cells, groups, handoff::output);
    }

    public void stop() {
        this.stopped = true;
        spawnOrigins.clear();
    }

    /** Appends rather than replacing, exactly as the native does — calling twice duplicates. */
    public void setSpawnOrigins(int[] xywh) {
        if (xywh == null) {
            throw new NullPointerException("xywh");
        }
        if (xywh.length % 4 != 0) {
            throw new IllegalArgumentException("xywh.length should multiple of 4");
        }
        for (int i = 0; i < xywh.length; i += 4) {
            int[] origin = Arrays.copyOfRange(xywh, i, i + 4);
            spawnOrigins.add(origin);
            if (spawnSource != null) {
                spawnSource.add(origin[0], origin[1], origin[2], origin[3]);
            }
        }
    }

    /**
     * Replaces the whole table. Saved zombies reference outfits by index into it, so the order the
     * caller supplies is what they will be wearing after a reload.
     */
    public void setOutfitNames(String[] lowercasedNames) {
        if (lowercasedNames == null) {
            throw new NullPointerException("names");
        }
        outfitNames = lowercasedNames.clone();
        outfitIndex.clear();
        for (int i = 0; i < outfitNames.length; i++) {
            outfitIndex.put(outfitNames[i], i);
        }
    }

    public void configFloat(String key, float value) {
        PopManConfig.requireFloatKey(key);
        handoff.input().floatConfig.put(key, value);
        handoff.input().configDirty = true;
    }

    public void configInt(String key, int value) {
        PopManConfig.requireIntKey(key);
        handoff.input().intConfig.put(key, value);
        handoff.input().configDirty = true;
    }

    // --- main thread --------------------------------------------------------

    /**
     * Publishes the pending input, if any. Named for {@code n_hasDataForThread}, which despite the
     * name is the publish step; its result tells the caller whether to wake the worker.
     */
    public boolean hasDataForThread() {
        if (stopped) {
            return false;
        }
        return handoff.publish();
    }

    public void updateMain(float timeMultiplier, double worldAgeHours) {
        if (stopped) {
            return;
        }
        PopManInputFrame input = handoff.input();
        input.timeChanged |=
                lastTimeMultiplier != timeMultiplier || lastWorldAgeHours != worldAgeHours;
        input.timeMultiplier = timeMultiplier;
        input.worldAgeHours = worldAgeHours;
        lastTimeMultiplier = timeMultiplier;
        lastWorldAgeHours = worldAgeHours;

        handoff.drainResults();
    }

    public void addZombie(
            float x,
            float y,
            float z,
            byte dir,
            int descriptorID,
            int stateFlags,
            int pathTargetX,
            int pathTargetY) {
        if (stopped) {
            return;
        }
        PopManZombie zombie = new PopManZombie();
        zombie.x = x;
        zombie.y = y;
        zombie.z = z;
        zombie.dir = dir;
        zombie.descriptorID = descriptorID;
        zombie.stateFlags = stateFlags;
        zombie.pathTargetX = pathTargetX;
        zombie.pathTargetY = pathTargetY;
        handoff.input().addZombies.add(zombie);
    }

    public void aggroTarget(int id, int x, int y) {
        if (stopped) {
            return;
        }
        handoff.input().aggroTargets.add(new PopManInputFrame.AggroTarget(id, x, y));
    }

    public void spawnHorde(
            int spawnX,
            int spawnY,
            int spawnW,
            int spawnH,
            float targetX,
            float targetY,
            int count) {
        if (stopped) {
            return;
        }
        handoff.input()
                .hordes
                .add(
                        new PopManInputFrame.HordeRequest(
                                spawnX, spawnY, spawnW, spawnH, targetX, targetY, count));
    }

    /**
     * Java re-sends every live sound each frame; the worker deduplicates and stamps them. The
     * caller has already applied the hearing multiplier to {@code radius} and filtered out
     * client-side, quiet, zombie-made and non-stressing sounds.
     */
    public void worldSound(int x, int y, int radius, int volume) {
        if (stopped) {
            return;
        }
        handoff.input().sounds.add(new PopManWorldSound(x, y, radius, volume));
    }

    /** {@code count} cells, three shorts each: cell x, cell y, live zombies. */
    public void realZombieCount(short count, short[] triples) {
        if (stopped) {
            return;
        }
        handoff.input().realZombieCounts = Arrays.copyOf(triples, count * 3);
    }

    public void loadChunk(int wx, int wy, boolean loaded) {
        if (stopped) {
            return;
        }
        handoff.input().chunkLoads.add(new PopManInputFrame.ChunkLoad(wx, wy, loaded));
    }

    /** {@code count} areas, four ints each, in chunk coordinates. */
    public void loadedAreas(int count, int[] areas, boolean isServerCells) {
        if (stopped) {
            return;
        }
        int[] copy = Arrays.copyOf(areas, count * 4);
        if (isServerCells) {
            handoff.input().loadedServerCells = copy;
        } else {
            handoff.input().loadedAreas = copy;
        }
    }

    public int getAddZombieCount() {
        return handoff.results().spawns.size();
    }

    /**
     * Fills {@code buf} with as many spawn records as fit, starting at {@code offset}, and returns
     * how many were written. Vanilla reads them back relatively from position zero, so the writes
     * here are absolute and leave the position alone.
     */
    public int getAddZombieData(int offset, ByteBuffer buf) {
        List<PopManZombie> spawns = handoff.results().spawns;
        int written = 0;
        while (offset + written < spawns.size() && written < PopManZombie.MAX_ADD_RECORDS) {
            spawns.get(offset + written).writeAddRecord(buf, written);
            written++;
        }
        return written;
    }

    public boolean hasRadarData() {
        return handoff.results().radarSet;
    }

    public void requestRadarData() {
        if (stopped) {
            return;
        }
        handoff.input().radarRequested = true;
    }

    /**
     * {@code n_debugCommand}: absolute cell coordinates, applied on the worker after the group
     * loop. The native also accepted these while stopped; here they would only queue up for a
     * simulation that no longer exists, so they are dropped instead.
     */
    public void debugCommand(int type, int cellX, int cellY) {
        if (stopped) {
            return;
        }
        handoff.input().debugCommands.add(new PopManInputFrame.DebugCommand(type, cellX, cellY));
    }

    // --- MPDebugInfo -------------------------------------------------------

    public static final int MP_DEBUG_BUFFER_BYTES = 1024;
    public static final int MP_DEBUG_CELL_BYTES = 12;
    public static final int MP_DEBUG_AREA_BYTES = 9;
    public static final int MP_DEBUG_REPOP_BYTES = 8;

    public void requestMpDebugData() {
        if (stopped) {
            return;
        }
        handoff.input().mpDebugRequested = true;
    }

    /** {@code n_hasData(false)}: a loaded-cells snapshot arrived this tick. */
    public boolean hasMpDebugData() {
        return handoff.results().mpDebugSet;
    }

    /** {@code n_hasData(true)}: repopulation events arrived this tick. */
    public boolean hasRepopEvents() {
        return !handoff.results().repopEvents.isEmpty();
    }

    public int getLoadedCellsCount() {
        return handoff.results().mpDebugCells.size();
    }

    public int getLoadedCellsData(int offset, ByteBuffer buf) {
        List<PopManResultFrame.MpDebugCell> cells = handoff.results().mpDebugCells;
        int max = MP_DEBUG_BUFFER_BYTES / MP_DEBUG_CELL_BYTES;
        int written = 0;
        while (offset + written < cells.size() && written < max) {
            PopManResultFrame.MpDebugCell cell = cells.get(offset + written);
            int at = written * MP_DEBUG_CELL_BYTES;
            buf.putShort(at, cell.cellX());
            buf.putShort(at + 2, cell.cellY());
            buf.putShort(at + 4, cell.currentPopulation());
            buf.putShort(at + 6, cell.desiredPopulation());
            buf.putFloat(at + 8, cell.lastRepopTime());
            written++;
        }
        return written;
    }

    private static int quadCount(int[] packed) {
        return packed == null ? 0 : packed.length / 4;
    }

    /** Player-loaded areas first, then the server's own cells; the flag byte tells them apart. */
    public int getLoadedAreasCount() {
        PopManResultFrame results = handoff.results();
        return quadCount(results.loadedAreas) + quadCount(results.loadedServerCells);
    }

    public int getLoadedAreasData(int offset, ByteBuffer buf) {
        PopManResultFrame results = handoff.results();
        int areas = quadCount(results.loadedAreas);
        int total = areas + quadCount(results.loadedServerCells);
        int max = MP_DEBUG_BUFFER_BYTES / MP_DEBUG_AREA_BYTES;
        int written = 0;
        while (offset + written < total && written < max) {
            int index = offset + written;
            boolean serverCell = index >= areas;
            int[] packed = serverCell ? results.loadedServerCells : results.loadedAreas;
            int quad = (serverCell ? index - areas : index) * 4;
            int at = written * MP_DEBUG_AREA_BYTES;
            buf.put(at, (byte) (serverCell ? 0 : 1));
            buf.putShort(at + 1, (short) packed[quad]);
            buf.putShort(at + 3, (short) packed[quad + 1]);
            buf.putShort(at + 5, (short) packed[quad + 2]);
            buf.putShort(at + 7, (short) packed[quad + 3]);
            written++;
        }
        return written;
    }

    public int getRepopEventCount() {
        return handoff.results().repopEvents.size();
    }

    public int getRepopEventData(int offset, ByteBuffer buf) {
        List<PopManResultFrame.RepopEvent> events = handoff.results().repopEvents;
        int max = MP_DEBUG_BUFFER_BYTES / MP_DEBUG_REPOP_BYTES;
        int written = 0;
        while (offset + written < events.size() && written < max) {
            PopManResultFrame.RepopEvent event = events.get(offset + written);
            int at = written * MP_DEBUG_REPOP_BYTES;
            buf.putShort(at, (short) event.chunkX());
            buf.putShort(at + 2, (short) event.chunkY());
            buf.putFloat(at + 4, event.worldAgeHours());
            written++;
        }
        return written;
    }

    public int getRadarZombieData(float[] xy) {
        float[] radar = handoff.results().radarXY;
        if (radar == null) {
            return 0;
        }
        int values = Math.min(radar.length, xy.length);
        System.arraycopy(radar, 0, xy, 0, values);
        return values / 2;
    }

    /**
     * Pausing is safe once the world has nothing left to hand over. Anything still queued describes
     * a world state the simulation has not seen yet, and pausing on top of it strands the work
     * until the next unpause.
     */
    public boolean readyToPause() {
        if (stopped) {
            return true;
        }
        PopManInputFrame input = handoff.input();
        return input.chunkLoads.isEmpty() && input.addZombies.isEmpty();
    }

    // --- real-zombie staging ------------------------------------------------

    /** How long the worker goes between unprompted full saves. */
    static final long AUTOSAVE_MS = 300_000;

    private long nextSaveMs;

    /** Live zombies handed over since the last {@link #beginSaveRealZombies}. */
    public List<PopManZombie> stagedRealZombies() {
        return stagedRealZombies;
    }

    /** The native ignores the count and only clears the staging array; so does this. */
    public void beginSaveRealZombies(int totalCount) {
        if (stopped) {
            return;
        }
        stagedRealZombies.clear();
    }

    /**
     * Appends one batch of live zombies, read from absolute offset zero — the caller never flips.
     * Unlike the on-disk record this one honours {@code buf}'s own byte order and puts the outfit
     * before the state flags, so decoding it as a disk record silently swaps the two.
     */
    public void saveRealZombies(int count, ByteBuffer buf) {
        if (stopped) {
            return;
        }
        for (int i = 0; i < count; i++) {
            PopManZombie zombie = new PopManZombie();
            zombie.readSaveRecord(buf, i);
            stagedRealZombies.add(zombie);
        }
    }

    // --- worker thread ------------------------------------------------------

    public void updateThread() {
        if (stopped) {
            return;
        }
        handoff.drainInput();
        environment.beforeTick();
        absorbInput(handoff.workerInput());
        simulate();
        handoff.publishResults();
    }

    /**
     * The pathfinder's answer to {@link Environment#requestPath}. Worker thread only — the game's
     * pathfinder runs on the same thread under the same lock, so the answer is synchronous there.
     */
    public void completePath(PopManRepopulateTask task, int status, int endX, int endY) {
        if (cells == null) {
            return;
        }
        repopulation.completePath(
                task, status, endX, endY, worldAgeHours, nowMs(), handoff.output(), server);
    }

    /**
     * The worker free-runs rather than ticking at a fixed rate: it parks only when there is nothing
     * queued, nobody walking, and no cell owing zombies. A busy server never reaches that state,
     * which is the point — repopulation debt is what keeps it spinning.
     */
    public boolean shouldWait() {
        if (stopped || cells == null) {
            return true;
        }
        if (handoff.hasPendingInput() || !handoff.input().isEmpty() || !groups.isEmpty()) {
            return false;
        }
        double age = worldAgeHours;
        long now = nowMs();
        for (PopManCell cell : cells.active()) {
            if (repopulation.isCellDue(cell, age, now)) {
                return false;
            }
        }
        return true;
    }

    /** Applies everything the main thread sent that does not need the simulation to exist yet. */
    void absorbInput(PopManInputFrame input) {
        if (input.timeChanged) {
            worldAgeHours = (int) input.worldAgeHours;
            speedMultiplier = input.timeMultiplier;
        }
        if (input.configDirty) {
            input.floatConfig.forEach(config::setFloat);
            input.intConfig.forEach(config::setInt);
        }
        long now = nowMs();
        for (PopManWorldSound sound : input.sounds) {
            worldSounds.merge(sound.x, sound.y, sound.radius, sound.volume, now, worldAgeHours);
        }
    }

    /**
     * Expires stale sounds and returns those whose recruit cooldown has elapsed. Pulling zombies
     * towards them needs the chunk population, so that half lands with the repopulation port.
     */
    List<PopManWorldSound> collectSoundRecruiters() {
        return worldSounds.ageAndCollectRecruiters(nowMs());
    }

    // --- simulation ---------------------------------------------------------

    /**
     * One worker tick. Everything the world sent is applied first, then the population is brought
     * up to strength, and only then is anybody allowed to move — repopulation and redistribution
     * both read the counts that the group loop is about to change.
     */
    private void simulate() {
        PopManInputFrame input = handoff.workerInput();
        PopManResultFrame out = handoff.output();
        long now = nowMs();
        double age = worldAgeHours;

        applyAreas(input);
        applyRealCounts(input);

        Set<PopManCell> touched = cells.refreshSeenClocks(map.loadedAreas(), age, now, false);

        for (PopManInputFrame.ChunkLoad load : input.chunkLoads) {
            PopManStreaming.applyChunkLoad(
                    cells, load.worldX(), load.worldY(), load.loaded(), now, out);
        }
        takeBackZombies(input, now);

        evictIdleCells(now, touched);

        for (PopManCell cell : cells.active()) {
            repopulation.repopulateCell(cell, age, now);
        }
        for (PopManInputFrame.HordeRequest request : input.hordes) {
            hordeSpawn.spawn(request);
        }
        for (PopManWorldSound sound : collectSoundRecruiters()) {
            grouping.recruitForSound(sound, age, now);
        }

        targeting.assignTargets(groups, input.aggroTargets, worldSounds.sounds());
        groupTick.setSpeedMultiplier(speedMultiplier);
        groupTick.tickAll(input.aggroTargets, now);
        applyDebugCommands(input, age);
        grouping.redistributeAll(age);

        if (nextSaveMs < now) {
            nextSaveMs = now + AUTOSAVE_MS;
            save();
        }
        if (input.radarRequested) {
            publishRadar(out);
        }
        if (input.mpDebugRequested) {
            publishMpDebug(out, age);
        }
    }

    /**
     * The three admin-panel buttons. {@code SpawnNow} sets every clock exactly to its threshold so
     * one pass fires at quota multiplier 1; {@code SpawnTimeToZero} zeroes them so the next pass
     * catches up on everything ever missed; {@code ClearZombies} drops only the virtual ones —
     * travelling groups and the base population are untouched, so the cell refills.
     */
    private void applyDebugCommands(PopManInputFrame input, double age) {
        for (PopManInputFrame.DebugCommand command : input.debugCommands) {
            PopManCell cell = cells.resident(command.cellX(), command.cellY());
            if (cell == null) {
                continue;
            }
            switch (command.type()) {
                case PopManInputFrame.DebugCommand.SPAWN_TIME_TO_ZERO -> {
                    for (PopManChunk chunk : cell.chunks) {
                        chunk.lastSeenTime = 0;
                        chunk.lastRepopTime = 0;
                    }
                    cell.lastRepopTime = 0;
                    cell.dirty = true;
                }
                case PopManInputFrame.DebugCommand.CLEAR_ZOMBIES -> {
                    for (PopManChunk chunk : cell.chunks) {
                        chunk.zombies.clear();
                    }
                    cell.virtualCount = 0;
                    cell.realCount = 0;
                }
                case PopManInputFrame.DebugCommand.SPAWN_NOW -> {
                    float ageF = (float) age;
                    for (PopManChunk chunk : cell.chunks) {
                        chunk.lastSeenTime = ageF - config.respawnUnseenHours;
                        chunk.lastRepopTime = ageF - config.respawnHours;
                    }
                    cell.lastRepopTime = ageF - config.respawnHours;
                    cell.dirty = true;
                }
                default -> {}
            }
        }
    }

    private void publishMpDebug(PopManResultFrame out, double age) {
        out.mpDebugCells.clear();
        for (PopManCell cell : cells.active()) {
            out.mpDebugCells.add(
                    new PopManResultFrame.MpDebugCell(
                            (short) cell.cellX,
                            (short) cell.cellY,
                            cell.currentPopulation(),
                            (short)
                                    PopManPopulation.desiredCellPopulation(
                                            config, cell.basePopSum, age),
                            cell.lastRepopTime));
        }
        out.loadedAreas = Arrays.copyOf(map.loadedAreas().packed(), map.loadedAreas().count() * 4);
        out.loadedServerCells =
                Arrays.copyOf(map.serverCells().packed(), map.serverCells().count() * 4);
        out.mpDebugSet = true;
    }

    private void applyAreas(PopManInputFrame input) {
        if (input.loadedAreas != null) {
            map.loadedAreas().set(input.loadedAreas, input.loadedAreas.length / 4);
        }
        if (input.loadedServerCells != null) {
            map.serverCells().set(input.loadedServerCells, input.loadedServerCells.length / 4);
        }
    }

    /**
     * Cell coordinates here are indices into the metagrid, not world cell coordinates — the one
     * channel that differs, where {@code n_saveCell} and {@code n_debugCommand} both take absolute
     * ones. Every cell is zeroed first: the game reports only the cells it has zombies in, so a
     * cell that has just been cleared reports nothing at all and would otherwise keep its old count
     * forever.
     */
    private void applyRealCounts(PopManInputFrame input) {
        short[] triples = input.realZombieCounts;
        if (triples == null) {
            return;
        }
        for (PopManCell cell : cells.resident()) {
            cell.realCount = 0;
        }
        for (int i = 0; i + 2 < triples.length; i += 3) {
            int cellX = triples[i];
            int cellY = triples[i + 1];
            if (cellX < 0 || cellY < 0 || cellX >= map.widthCells() || cellY >= map.heightCells()) {
                continue;
            }
            PopManCell cell = cells.resident(cellX + map.minCellX(), cellY + map.minCellY());
            if (cell != null) {
                cell.realCount = triples[i + 2];
            }
        }
    }

    /** A zombie the game no longer wants to simulate goes back to being a number in a chunk. */
    private void takeBackZombies(PopManInputFrame input, long nowMs) {
        for (PopManZombie zombie : input.addZombies) {
            int squareX = (int) Math.floor(zombie.x);
            int squareY = (int) Math.floor(zombie.y);
            PopManCell cell = cells.residentForSquare(squareX, squareY);
            if (cell == null) {
                continue;
            }
            cell.chunkAtSquare(squareX, squareY).zombies.add(zombie);
            cell.virtualCount++;
            if (cell.realCount > 0) {
                cell.realCount--;
            }
            cell.lastTouchedMs = nowMs;
            cell.dirty = true;
        }
    }

    /** A cell nobody has touched lately is written out and dropped; its file is the population. */
    private void evictIdleCells(long nowMs, Set<PopManCell> touched) {
        for (PopManCell cell : cells.evictIdle(nowMs, touched)) {
            if (cell.dirty && store != null) {
                store.save(cell);
            }
        }
    }

    private void publishRadar(PopManResultFrame out) {
        List<PopManZombie> everyone = new ArrayList<>();
        for (PopManCell cell : cells.active()) {
            for (PopManChunk chunk : cell.chunks) {
                everyone.addAll(chunk.zombies);
            }
        }
        for (PopManGroup group : groups) {
            everyone.add(group.leader);
        }
        float[] xy = new float[everyone.size() * 2];
        for (int i = 0; i < everyone.size(); i++) {
            xy[i * 2] = everyone.get(i).x;
            xy[i * 2 + 1] = everyone.get(i).y;
        }
        out.radarXY = xy;
        out.radarSet = true;
    }

    // --- persistence --------------------------------------------------------

    /**
     * Writes every cell that has changed, plus the travelling hordes, which live in one file of
     * their own because they belong to no cell.
     */
    public void save() {
        if (!canWrite()) {
            return;
        }
        rehomeStagedZombies();
        for (PopManCell cell : cells.resident()) {
            if (cell.dirty) {
                store.save(cell);
            }
        }
        store.saveGroups(groups);
    }

    /**
     * Writes one cell, because the chunk that was holding it up has just unloaded. The live zombies
     * staged by the caller are folded in first — they are what the cell is losing.
     */
    public void saveCell(int cellX, int cellY) {
        if (!canWrite()) {
            stagedRealZombies.clear();
            return;
        }
        PopManCell cell = cells.resident(cellX, cellY);
        if (cell != null && cell.loaded) {
            for (PopManZombie zombie : stagedRealZombies) {
                adopt(cell, zombie);
            }
            store.save(cell);
        }
        stagedRealZombies.clear();
    }

    /**
     * {@code Core.noSave} makes both writers silent, so a debug session cannot scribble on a save.
     */
    private boolean canWrite() {
        return !stopped && store != null && !map.gameState().noSave;
    }

    private void rehomeStagedZombies() {
        for (PopManZombie zombie : stagedRealZombies) {
            adopt(
                    cells.residentForSquare((int) Math.floor(zombie.x), (int) Math.floor(zombie.y)),
                    zombie);
        }
        stagedRealZombies.clear();
    }

    /** Only takes the zombie if it really stands in this cell; a stray is dropped, not moved. */
    private void adopt(PopManCell cell, PopManZombie zombie) {
        int squareX = (int) Math.floor(zombie.x);
        int squareY = (int) Math.floor(zombie.y);
        if (cell == null
                || PopManGeometry.cellOfSquare(squareX) != cell.cellX
                || PopManGeometry.cellOfSquare(squareY) != cell.cellY) {
            return;
        }
        cell.chunkAtSquare(squareX, squareY).zombies.add(zombie);
        cell.virtualCount++;
        if (cell.realCount > 0) {
            cell.realCount--;
        }
        cell.dirty = true;
    }
}
