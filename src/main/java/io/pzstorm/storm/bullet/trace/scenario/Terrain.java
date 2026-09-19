package io.pzstorm.storm.bullet.trace.scenario;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.BiPredicate;

/**
 * A stand-in for the game's chunks as far as physics sees them: which chunks are loaded, their
 * level range, and the per-square collision shape bytes that {@code IsoChunk.updatePhysicsForLevel}
 * sends (0..4 bytes per square: {@code IsoChunk.PhysicsShapes.ordinal() + 1}, mesh shapes from
 * {@code FIRST_MESH + 1 + meshIndex}). Shapes are a pure function of (seed, wx, wy, level, x, y),
 * so an answered {@code updatePhysicsForLevelIfNeeded} upcall is reproducible.
 */
public final class Terrain {

    /** {@code IsoChunk.PhysicsShapes} ordinals + 1, as bytes on the wire. */
    public static final byte SOLID = 1,
            WALL_N = 2,
            WALL_W = 3,
            WALL_S = 4,
            WALL_E = 5,
            TREE = 6,
            FLOOR = 7,
            STAIRS_MIDDLE_NORTH = 8,
            STAIRS_MIDDLE_WEST = 9,
            SOLID_STAIRS = 10,
            FIRST_MESH = 11;

    public static final class Chunk {
        public final int wx;
        public final int wy;
        public int minLevel;
        public int maxLevel;

        /** Indexed by level - {@link #LEVEL_BASE}. */
        final boolean[] physicsCheck = new boolean[64];

        Chunk(int wx, int wy, int minLevel, int maxLevel) {
            this.wx = wx;
            this.wy = wy;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
        }
    }

    static final int LEVEL_BASE = 32;

    private final long seed;
    private final Map<Long, Chunk> chunks = new HashMap<>();
    private int minChunkX = Integer.MIN_VALUE;
    private int minChunkY = Integer.MIN_VALUE;
    private int maxChunkX = Integer.MAX_VALUE;
    private int maxChunkY = Integer.MAX_VALUE;

    /** Number of meshes defined with definePhysicsMesh; 0 = no mesh shapes are emitted. */
    public int meshCount;

    /** 0..1: how built-up the terrain is (walls, solids, trees). */
    public double density = 0.25;

    public Terrain(long seed) {
        this.seed = seed;
    }

    /** {@code IsoMetaGrid.isValidChunk}: world bounds in chunks (cells * 32). */
    public void setBoundsCells(int minCellX, int minCellY, int maxCellX, int maxCellY) {
        minChunkX = minCellX * 32;
        minChunkY = minCellY * 32;
        maxChunkX = (maxCellX + 1) * 32 - 1;
        maxChunkY = (maxCellY + 1) * 32 - 1;
    }

    public boolean isValidChunk(int wx, int wy) {
        return wx >= minChunkX && wx <= maxChunkX && wy >= minChunkY && wy <= maxChunkY;
    }

    /** A chunk finished loading: every level is flagged for a physics check (RecalcProperties). */
    public Chunk load(int wx, int wy, int minLevel, int maxLevel) {
        Chunk c = new Chunk(wx, wy, minLevel, maxLevel);
        for (int l = minLevel; l <= maxLevel; l++) {
            c.physicsCheck[l + LEVEL_BASE] = true;
        }
        chunks.put(key(wx, wy), c);
        return c;
    }

    /** Forget every chunk (a new world is created: {@code IsoChunkMap} starts empty). */
    public void clear() {
        chunks.clear();
        meshCount = 0;
    }

    public void unload(int wx, int wy) {
        chunks.remove(key(wx, wy));
    }

    public Chunk get(int wx, int wy) {
        return chunks.get(key(wx, wy));
    }

    /** {@code IsoChunk.checkPhysicsLater}: something changed on that level. */
    public void checkPhysicsLater(int wx, int wy, int level) {
        Chunk c = get(wx, wy);
        if (c != null && level >= c.minLevel && level <= c.maxLevel) {
            c.physicsCheck[level + LEVEL_BASE] = true;
        }
    }

    /**
     * {@code Bullet.updatePhysicsForLevelIfNeeded}: sends the level's shapes through {@code cmd} (a
     * nested ToBullet inside the upcall) when it is flagged.
     */
    public boolean updatePhysicsForLevelIfNeeded(CmdBuf cmd, int wx, int wy, int level) {
        if (!isValidChunk(wx, wy)) {
            return false;
        }
        Chunk c = get(wx, wy);
        if (c == null || level < c.minLevel || level > c.maxLevel) {
            return false;
        }
        if (!c.physicsCheck[level + LEVEL_BASE]) {
            return false;
        }
        c.physicsCheck[level + LEVEL_BASE] = false;
        updatePhysicsForLevel(cmd, c, level);
        return true;
    }

    /** {@code IsoChunk.updatePhysicsForLevel}. */
    public void updatePhysicsForLevel(CmdBuf cmd, Chunk c, int level) {
        cmd.beginUpdateChunk(c.wx, c.wy, c.minLevel, c.maxLevel, level);
        byte[] shapes = new byte[4];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int n = shapesAt(c.wx, c.wy, level, x, y, shapes);
                cmd.updateChunk(x, y, n, shapes);
            }
        }
        cmd.endUpdateChunk();
    }

    /**
     * The shape bytes of one square; returns how many (0..4). Follows the order of {@code
     * IsoChunk.calcPhysics}: stairs, then tree or solid (or solid stairs), then {@code Floor}
     * (every {@code solidfloor} square, which is nearly all outdoor ground), then walls, then a
     * physics mesh; duplicates are dropped and the list is capped at 4 like {@code
     * addPhysicsShape}.
     */
    public int shapesAt(int wx, int wy, int level, int x, int y, byte[] out) {
        SplittableRandom r =
                new SplittableRandom(
                        seed
                                ^ (wx * 0x9E3779B97F4A7C15L)
                                ^ (wy * 0xC2B2AE3D27D4EB4FL)
                                ^ ((long) (level + LEVEL_BASE) << 48)
                                ^ ((long) x << 8)
                                ^ y);
        int n = 0;
        boolean stairs = r.nextDouble() < density * 0.03;
        if (stairs) {
            n = add(out, n, r.nextBoolean() ? STAIRS_MIDDLE_NORTH : STAIRS_MIDDLE_WEST);
        }
        if (r.nextDouble() < density * (level > 0 ? 0.02 : 0.15)) {
            n = add(out, n, TREE);
        } else if (stairs) {
            n = add(out, n, SOLID_STAIRS);
        } else if (r.nextDouble() < density * 0.1) {
            n = add(out, n, SOLID);
        }
        // ground level: solidfloor almost everywhere (rare water/holes); upper floors: buildings
        // only
        if (r.nextDouble() < (level == 0 ? 0.98 : level > 0 ? 0.6 : 0.9)) {
            n = add(out, n, FLOOR);
        }
        if (r.nextDouble() < density * 0.3) {
            n = add(out, n, r.nextBoolean() ? WALL_N : WALL_W);
        }
        if (r.nextDouble() < density * 0.02) {
            n = add(out, n, r.nextBoolean() ? WALL_S : WALL_E);
        }
        if (meshCount > 0 && r.nextDouble() < density * 0.1) {
            n = add(out, n, (byte) (FIRST_MESH + r.nextInt(Math.min(meshCount, 100))));
        }
        return n;
    }

    /**
     * Whether a vehicle can stand at square (x, y): solid ground with no box (solid, tree, stairs,
     * mesh) within {@code boxRadius} squares and no wall within {@code wallRadius}. The game never
     * spawns or drives a vehicle into such shapes; a body created inside one is pushed out through
     * the floor.
     */
    public boolean isClear(int x, int y, int boxRadius, int wallRadius) {
        byte[] sh = new byte[4];
        for (int dy = -boxRadius; dy <= boxRadius; dy++) {
            for (int dx = -boxRadius; dx <= boxRadius; dx++) {
                int qx = x + dx, qy = y + dy;
                int n =
                        shapesAt(
                                Math.floorDiv(qx, 8),
                                Math.floorDiv(qy, 8),
                                0,
                                Math.floorMod(qx, 8),
                                Math.floorMod(qy, 8),
                                sh);
                boolean floor = false;
                boolean nearWall = Math.abs(dx) <= wallRadius && Math.abs(dy) <= wallRadius;
                for (int i = 0; i < n; i++) {
                    byte b = sh[i];
                    if (b == FLOOR) {
                        floor = true;
                    } else if (b >= WALL_N && b <= WALL_E) {
                        if (nearWall) {
                            return false;
                        }
                    } else {
                        return false;
                    }
                }
                if (!floor) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The nearest clear square centre to (x, y), searched ring by ring (a deterministic order): no
     * box or wall within 2 squares (a 5 m vehicle body reaches 2 squares from its centre square)
     * out to 100 squares, else within 1 out to 40; (x, y) itself when nothing is clear.
     */
    public float[] clearSpot(float x, float y) {
        return clearSpot(x, y, new float[] {0, 0});
    }

    /**
     * As {@link #clearSpot(float, float)} for a group of vehicles placed relative to each other (a
     * truck and its trailer, two cars on a rope): every (dx, dy) pair of {@code offsets}, added to
     * the candidate, must be clear too. Returns the candidate for offset (0, 0).
     */
    public float[] clearSpot(float x, float y, float[] offsets) {
        return clearSpot(x, y, offsets, (px, py) -> false);
    }

    /**
     * As {@link #clearSpot(float, float, float[])}, also rejecting a candidate when {@code taken}
     * holds for any of its placed points (another vehicle stands there).
     */
    public float[] clearSpot(float x, float y, float[] offsets, BiPredicate<Float, Float> taken) {
        float[] at = clearSpot(x, y, offsets, taken, 2, 100);
        // very dense terrain: settle for a 3 x 3 patch rather than none (a vehicle standing there
        // may overlap a box at its ends)
        return at != null ? at : clearSpot(x, y, offsets, taken, 1, 40);
    }

    private float[] clearSpot(
            float x,
            float y,
            float[] offsets,
            BiPredicate<Float, Float> taken,
            int boxRadius,
            int maxRing) {
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y);
        for (int r = 0; r <= maxRing; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) {
                        continue;
                    }
                    float ax = cx + dx + 0.5F, ay = cy + dy + 0.5F;
                    boolean clear = true;
                    for (int i = 0; i + 1 < offsets.length && clear; i += 2) {
                        clear =
                                !taken.test(ax + offsets[i], ay + offsets[i + 1])
                                        && isClear(
                                                (int) Math.floor(ax + offsets[i]),
                                                (int) Math.floor(ay + offsets[i + 1]),
                                                boxRadius,
                                                boxRadius);
                    }
                    if (clear) {
                        return new float[] {ax, ay};
                    }
                }
            }
        }
        return boxRadius == 1 ? new float[] {x, y} : null;
    }

    /** {@code IsoChunk.addPhysicsShape}: skips duplicates and drops shapes past the 4th. */
    private static int add(byte[] out, int n, byte shape) {
        for (int i = 0; i < n; i++) {
            if (out[i] == shape) {
                return n;
            }
        }
        if (n == 4) {
            return n;
        }
        out[n] = shape;
        return n + 1;
    }

    private static long key(int wx, int wy) {
        return ((long) wx << 32) ^ (wy & 0xFFFFFFFFL);
    }
}
