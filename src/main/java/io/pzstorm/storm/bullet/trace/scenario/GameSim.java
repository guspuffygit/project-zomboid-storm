package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;
import java.util.ArrayList;
import java.util.List;

/**
 * The game-side routines scenarios share, each mirroring a specific game caller: boot ({@code
 * Bullet.init}), {@code WorldSimulation.create}, the chunk map ({@code IsoChunkMap} + {@code
 * IsoChunk.update}), the fixed 10 ms step loop and the per-frame readbacks of {@code
 * WorldSimulation.updateInternal}.
 */
public final class GameSim {

    /** One level of height in physics units ({@code 3 * 0.8164967F}). */
    public static final float LEVEL_HEIGHT = 3 * 0.8164967F;

    public static final int CHUNK_SQUARES = 8;
    public static final int CELL_SQUARES = 256;

    /** Parsed {@code getVehiclePhysics} record. */
    public record VehicleState(
            int id,
            float x,
            float y,
            float z,
            float qx,
            float qy,
            float qz,
            float qw,
            float vx,
            float vy,
            float vz,
            float speedKmh,
            boolean collide,
            float[] wheels) {}

    private final ScenarioContext ctx;
    private final BulletBackend b;

    /** {@code WorldSimulation.ff}. */
    public final float[] ff = new float[8192];

    public float offsetX;
    public float offsetY;
    public int gridWidth;
    public int chunkMapMinX;
    public int chunkMapMinY;
    public boolean server;
    public int frames;

    /** Game time in seconds: 10 ms per physics step. */
    public float time;

    /** Chunk-map origin per slot (0..3), null when the slot is inactive. */
    private final int[][] extraMaps = new int[4][];

    public GameSim(ScenarioContext ctx) {
        this.ctx = ctx;
        this.b = ctx.bullet;
    }

    /** {@code Bullet.init} after the library is loaded, plus the version query the game logs. */
    public void boot() {
        if (ctx.booted) {
            return;
        }
        ctx.booted = true;
        ctx.note("boot");
        b.getPZBulletVersion();
        b.initPZBullet();
    }

    /**
     * {@code WorldSimulation.create} → {@code Bullet.initWorld(9 args)} for a player standing at
     * square (playerX, playerY), then every chunk of the chunk map loaded with levels [0,
     * maxLevel].
     */
    public void createWorld(
            int minCellX,
            int minCellY,
            int maxCellX,
            int maxCellY,
            float playerX,
            float playerY,
            int gridWidth,
            boolean server) {
        ctx.note("createWorld");
        ctx.terrain.clear();
        ctx.drainImpulses();
        ctx.onImpulse = null;
        this.server = server;
        this.gridWidth = gridWidth;
        this.offsetX = minCellX * CELL_SQUARES;
        this.offsetY = minCellY * CELL_SQUARES;
        ctx.terrain.setBoundsCells(minCellX, minCellY, maxCellX, maxCellY);
        chunkMapMinX = (int) Math.floor(playerX / CHUNK_SQUARES) - gridWidth / 2;
        chunkMapMinY = (int) Math.floor(playerY / CHUNK_SQUARES) - gridWidth / 2;
        b.isWorldInit();
        b.initWorld(minCellX, minCellY, maxCellX, maxCellY, (int) offsetX, (int) offsetY, server);
        b.activateChunkMap(0, chunkMapMinX, chunkMapMinY, gridWidth);
        for (int y = 0; y < gridWidth; y++) {
            for (int x = 0; x < gridWidth; x++) {
                loadChunk(chunkMapMinX + x, chunkMapMinY + y);
            }
        }
    }

    /** A chunk streams in: random level range, then {@code IsoChunk.update}'s min/max sync. */
    public void loadChunk(int wx, int wy) {
        int maxLevel = ctx.random.nextInt(10) < 7 ? 0 : 1 + ctx.random.nextInt(3);
        int minLevel = ctx.random.nextInt(20) == 0 ? -1 : 0;
        ctx.terrain.load(wx, wy, minLevel, maxLevel);
        if (!server && (minLevel != 0 || maxLevel != 0)) {
            b.setChunkMinMaxLevel(wx, wy, minLevel, maxLevel);
        }
    }

    /** {@code currentCell.getChunk(wx, wy).loaded}: inside some active chunk map and streamed. */
    public boolean chunkLoaded(int wx, int wy) {
        boolean in =
                wx >= chunkMapMinX
                        && wy >= chunkMapMinY
                        && wx < chunkMapMinX + gridWidth
                        && wy < chunkMapMinY + gridWidth;
        for (int[] m : extraMaps) {
            if (m != null
                    && wx >= m[0]
                    && wy >= m[1]
                    && wx < m[0] + gridWidth
                    && wy < m[1] + gridWidth) {
                in = true;
            }
        }
        return in && ctx.terrain.get(wx, wy) != null;
    }

    /** Loads a chunk only if the terrain does not already have it (shared by several maps). */
    public void loadChunkIfAbsent(int wx, int wy) {
        if (ctx.terrain.get(wx, wy) == null) {
            loadChunk(wx, wy);
        }
    }

    /**
     * Another player's chunk map (split-screen slot 1..3, or a server-side player area): {@code
     * IsoChunkMap} activation, then its chunks stream in. Chunks already loaded by another map are
     * shared, as the game's {@code IsoChunkMap.SharedChunks} are.
     */
    public void activateChunkMap(int slot, float playerX, float playerY) {
        int mx = (int) Math.floor(playerX / CHUNK_SQUARES) - gridWidth / 2;
        int my = (int) Math.floor(playerY / CHUNK_SQUARES) - gridWidth / 2;
        extraMaps[slot] = new int[] {mx, my};
        b.activateChunkMap(slot, mx, my, gridWidth);
        for (int y = 0; y < gridWidth; y++) {
            for (int x = 0; x < gridWidth; x++) {
                loadChunkIfAbsent(mx + x, my + y);
            }
        }
    }

    /** {@code IsoChunkMap.Unload} for a secondary slot. */
    public void deactivateChunkMap(int slot) {
        extraMaps[slot] = null;
        b.deactivateChunkMap(slot);
    }

    /** Scrolls a secondary chunk map one chunk toward (x, y) squares, if it is not there yet. */
    public void followSlot(int slot, float x, float y) {
        int[] m = extraMaps[slot];
        if (m == null) {
            return;
        }
        int cx = (int) Math.floor(x / CHUNK_SQUARES) - gridWidth / 2;
        int cy = (int) Math.floor(y / CHUNK_SQUARES) - gridWidth / 2;
        int guard = 0;
        while ((cx != m[0] || cy != m[1]) && guard++ < 64) {
            int dir = cx < m[0] ? 0 : cx > m[0] ? 1 : cy < m[1] ? 2 : 3;
            b.scrollChunkMap(slot, dir);
            switch (dir) {
                case 0 -> {
                    m[0]--;
                    for (int j = 0; j < gridWidth; j++) loadChunkIfAbsent(m[0], m[1] + j);
                }
                case 1 -> {
                    m[0]++;
                    for (int j = 0; j < gridWidth; j++)
                        loadChunkIfAbsent(m[0] + gridWidth - 1, m[1] + j);
                }
                case 2 -> {
                    m[1]--;
                    for (int j = 0; j < gridWidth; j++) loadChunkIfAbsent(m[0] + j, m[1]);
                }
                default -> {
                    m[1]++;
                    for (int j = 0; j < gridWidth; j++)
                        loadChunkIfAbsent(m[0] + j, m[1] + gridWidth - 1);
                }
            }
        }
    }

    /** {@code WorldSimulation.destroy}: the world goes away, and every secondary map with it. */
    public void destroyWorld() {
        ctx.note("destroyWorld");
        for (int i = 0; i < extraMaps.length; i++) {
            extraMaps[i] = null;
        }
        b.destroyWorld();
    }

    /** Quaternion for a yaw around +Y, as {@code BaseVehicle.savedRot} holds it. */
    public static float[] yawQuat(double yaw) {
        return new float[] {0.0F, (float) Math.sin(yaw / 2), 0.0F, (float) Math.cos(yaw / 2)};
    }

    /**
     * The player crossed a chunk border: {@code IsoChunkMap} scrolls and the new row/column of
     * chunks loads. dir: 0 left, 1 right, 2 up, 3 down ({@code Bullet.scrollChunkMapLeft} ...).
     */
    public void scroll(int dir) {
        b.scrollChunkMap(0, dir);
        switch (dir) {
            case 0 -> {
                chunkMapMinX--;
                for (int y = 0; y < gridWidth; y++) loadChunk(chunkMapMinX, chunkMapMinY + y);
            }
            case 1 -> {
                chunkMapMinX++;
                for (int y = 0; y < gridWidth; y++)
                    loadChunk(chunkMapMinX + gridWidth - 1, chunkMapMinY + y);
            }
            case 2 -> {
                chunkMapMinY--;
                for (int x = 0; x < gridWidth; x++) loadChunk(chunkMapMinX + x, chunkMapMinY);
            }
            default -> {
                chunkMapMinY++;
                for (int x = 0; x < gridWidth; x++)
                    loadChunk(chunkMapMinX + x, chunkMapMinY + gridWidth - 1);
            }
        }
    }

    /** Keeps the chunk map centred on (x, y) squares, scrolling one chunk at a time. */
    public void follow(float x, float y) {
        int cx = (int) Math.floor(x / CHUNK_SQUARES) - gridWidth / 2;
        int cy = (int) Math.floor(y / CHUNK_SQUARES) - gridWidth / 2;
        int guard = 0;
        while ((cx != chunkMapMinX || cy != chunkMapMinY) && guard++ < 64) {
            if (cx < chunkMapMinX) scroll(0);
            else if (cx > chunkMapMinX) scroll(1);
            else if (cy < chunkMapMinY) scroll(2);
            else scroll(3);
        }
    }

    /** {@code WorldSimulation.updatePhysic}: {@code n} fixed 10 ms steps. */
    public void step(int n) {
        for (int i = 0; i < n; i++) {
            b.stepSimulation(0.01F, 0, 0.0F);
            time += 0.01F;
        }
        frames++;
    }

    /** One game frame: steps, then the vehicle and object readbacks of {@code updateInternal}. */
    public List<VehicleState> frame(int substeps) {
        step(substeps);
        List<VehicleState> v = readVehicles();
        b.getObjectPhysics(ff);
        return v;
    }

    /** {@code getVehicleCount} + paged {@code getVehiclePhysics}, parsed like the game does. */
    public List<VehicleState> readVehicles() {
        List<VehicleState> out = new ArrayList<>();
        int total = b.getVehicleCount();
        int offset = 0;
        while (offset < total) {
            int n = b.getVehiclePhysics(offset, ff);
            if (n <= 0) {
                break;
            }
            offset += n;
            int fn = 0;
            for (int i = 0; i < n; i++) {
                int id = (int) ff[fn++];
                float x = ff[fn++], y = ff[fn++], z = ff[fn++];
                float qx = ff[fn++], qy = ff[fn++], qz = ff[fn++], qw = ff[fn++];
                float vx = ff[fn++], vy = ff[fn++], vz = ff[fn++];
                float speed = ff[fn++];
                float collide = ff[fn++];
                int wheelCount = (int) ff[fn++];
                float[] wheels = new float[Math.max(0, wheelCount) * 4];
                for (int w = 0; w < wheels.length && fn < ff.length; w++) {
                    wheels[w] = ff[fn++];
                }
                out.add(
                        new VehicleState(
                                id,
                                x,
                                y,
                                z,
                                qx,
                                qy,
                                qz,
                                qw,
                                vx,
                                vy,
                                vz,
                                speed,
                                collide > 0.5F,
                                wheels));
            }
        }
        return out;
    }

    /**
     * {@code CarController} constructor: physics height of a vehicle standing on level {@code z}.
     */
    public static float physicsZ(VehicleScripts.Script s, int level) {
        float wheelBottom = 0;
        if (s.wheels().length > 0) {
            wheelBottom += s.modelOffsetY();
            wheelBottom += s.wheels()[0].offsetY() - s.wheels()[0].radius();
        }
        float chassisBottom = s.comY() - s.extentsY() / 2.0F;
        float z = level * 3 * 0.8164967F - Math.min(wheelBottom, chassisBottom);
        if (s.wheels().length == 0) {
            z = Math.max(z, level * 3 * 0.8164967F + 0.1F);
        }
        return z;
    }
}
