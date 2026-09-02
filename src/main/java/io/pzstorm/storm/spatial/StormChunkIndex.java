package io.pzstorm.storm.spatial;

import java.util.Arrays;

/**
 * Per-tick spatial index of moving objects, bucketed by {@link #CHUNK_SIZE}-tile chunk and
 * partitioned by object type. Game-type-free so it can be unit-tested without a PZ runtime; {@link
 * StormSpatialIndex} is the typed facade that feeds and queries it.
 *
 * <p><b>Model.</b> The index is a snapshot: {@link #beginTick(long)} wipes it, {@link #add} is
 * called once per object with its position at that instant, {@link #endTick()} publishes it for the
 * rest of the tick. Queries return a <em>candidate</em> set — everything in the chunk rectangle
 * covering the caller's region, which the caller then filters with its own exact, live checks. The
 * candidate set is stale by at most one tick of movement; callers widen their query region
 * accordingly (one chunk of slack covers anything short of teleportation).
 *
 * <p><b>Layout.</b> An open-addressed {@code long}-keyed table maps packed {@code (cx, cy)} chunk
 * keys to pooled {@link Bucket}s; each bucket holds one growable {@code Object[]} per type. Bucket
 * and item arrays are retained across ticks so a steady-state rebuild allocates nothing. Everything
 * is single-threaded by contract (server main thread).
 */
public final class StormChunkIndex {

    public static final int TYPE_PLAYER = 0;
    public static final int TYPE_ZOMBIE = 1;
    public static final int TYPE_ANIMAL = 2;
    public static final int TYPE_VEHICLE = 3;
    public static final int TYPE_OTHER = 4;
    public static final int NUM_TYPES = 5;

    public static final int MASK_PLAYER = 1 << TYPE_PLAYER;
    public static final int MASK_ZOMBIE = 1 << TYPE_ZOMBIE;
    public static final int MASK_ANIMAL = 1 << TYPE_ANIMAL;
    public static final int MASK_VEHICLE = 1 << TYPE_VEHICLE;
    public static final int MASK_OTHER = 1 << TYPE_OTHER;
    public static final int MASK_ALL = (1 << NUM_TYPES) - 1;

    /** Bucket edge in tiles. Matches the game's 8×8-tile {@code IsoChunk}. */
    public static final int CHUNK_SIZE = 8;

    private static final int INITIAL_TABLE_CAPACITY = 4096;
    private static final int INITIAL_ITEMS_CAPACITY = 8;
    private static final int EMPTY_SLOT = -1;

    static final class Bucket {
        int cx;
        int cy;
        final Object[][] items = new Object[NUM_TYPES][];

        /** Packed integer tile position of each item (see {@link #packPos}), parallel to items. */
        final long[][] pos = new long[NUM_TYPES][];

        final int[] counts = new int[NUM_TYPES];

        void reset(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
            for (int t = 0; t < NUM_TYPES; t++) {
                if (counts[t] > 0) {
                    Arrays.fill(items[t], 0, counts[t], null);
                    counts[t] = 0;
                }
            }
        }

        void add(int type, Object o, long packedPos) {
            Object[] arr = items[type];
            long[] p = pos[type];
            int n = counts[type];
            if (arr == null) {
                arr = new Object[INITIAL_ITEMS_CAPACITY];
                p = new long[INITIAL_ITEMS_CAPACITY];
                items[type] = arr;
                pos[type] = p;
            } else if (n == arr.length) {
                arr = Arrays.copyOf(arr, n << 1);
                p = Arrays.copyOf(p, n << 1);
                items[type] = arr;
                pos[type] = p;
            }
            arr[n] = o;
            p[n] = packedPos;
            counts[type] = n + 1;
        }
    }

    private long[] keys = new long[INITIAL_TABLE_CAPACITY];
    private int[] slots = new int[INITIAL_TABLE_CAPACITY];
    private int tableMask = INITIAL_TABLE_CAPACITY - 1;

    private Bucket[] buckets = new Bucket[256];
    private int bucketCount;

    private final int[] typeTotals = new int[NUM_TYPES];
    private int size;
    private long frame = -1L;
    private boolean ready;

    public StormChunkIndex() {
        Arrays.fill(slots, EMPTY_SLOT);
    }

    /** Chunk coordinate of a tile coordinate; floors toward negative infinity. */
    public static int chunkOf(float tileCoord) {
        return Math.floorDiv((int) Math.floor(tileCoord), CHUNK_SIZE);
    }

    /** Chunk coordinate of an integer tile coordinate; floors toward negative infinity. */
    public static int chunkOf(int tile) {
        return Math.floorDiv(tile, CHUNK_SIZE);
    }

    static long packKey(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFFFFFFL);
    }

    /** Packs an integer tile position; the inverse is {@link #posX}/{@link #posY}. */
    public static long packPos(int tx, int ty) {
        return ((long) tx << 32) | (ty & 0xFFFFFFFFL);
    }

    public static int posX(long packedPos) {
        return (int) (packedPos >> 32);
    }

    public static int posY(long packedPos) {
        return (int) packedPos;
    }

    /** Wipes the snapshot and stamps it with the tick it is being built for. */
    public void beginTick(long frame) {
        ready = false;
        this.frame = frame;
        size = 0;
        Arrays.fill(typeTotals, 0);
        if (bucketCount > 0) {
            for (int i = 0; i < bucketCount; i++) {
                buckets[i].reset(0, 0);
            }
            bucketCount = 0;
            Arrays.fill(slots, EMPTY_SLOT);
        }
    }

    /**
     * Records {@code o} at tile position {@code (x, y)} under {@code type}; the floored position is
     * stored beside the object so {@link Cursor} can cull on it without touching the object.
     */
    public void add(Object o, float x, float y, int type) {
        int tx = (int) Math.floor(x);
        int ty = (int) Math.floor(y);
        int cx = Math.floorDiv(tx, CHUNK_SIZE);
        int cy = Math.floorDiv(ty, CHUNK_SIZE);
        bucketFor(cx, cy, true).add(type, o, packPos(tx, ty));
        typeTotals[type]++;
        size++;
    }

    /** Publishes the snapshot for queries. */
    public void endTick() {
        ready = true;
    }

    /** Marks the snapshot unusable (a rebuild threw midway). */
    public void invalidate() {
        ready = false;
    }

    public boolean isReady() {
        return ready;
    }

    public long frame() {
        return frame;
    }

    public int size() {
        return size;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public int totalOf(int type) {
        return typeTotals[type];
    }

    /**
     * Appends every indexed object of a type in {@code typeMask} whose chunk lies in the inclusive
     * chunk rectangle {@code [cx0..cx1] × [cy0..cy1]} to {@code out}.
     *
     * @return number of objects appended
     */
    public int collectChunkRect(
            int cx0, int cy0, int cx1, int cy1, int typeMask, StormObjectList out) {
        if (cx1 < cx0 || cy1 < cy0 || bucketCount == 0) {
            return 0;
        }
        long area = (long) (cx1 - cx0 + 1) * (long) (cy1 - cy0 + 1);
        int before = out.size();
        if (area <= bucketCount) {
            for (int cy = cy0; cy <= cy1; cy++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    Bucket b = bucketFor(cx, cy, false);
                    if (b != null) {
                        drain(b, typeMask, out);
                    }
                }
            }
        } else {
            for (int i = 0; i < bucketCount; i++) {
                Bucket b = buckets[i];
                if (b.cx >= cx0 && b.cx <= cx1 && b.cy >= cy0 && b.cy <= cy1) {
                    drain(b, typeMask, out);
                }
            }
        }
        return out.size() - before;
    }

    /**
     * Appends every indexed object of a type in {@code typeMask} whose chunk intersects the tile
     * rectangle {@code [minX..maxX] × [minY..maxY]} to {@code out}. Objects are returned at chunk
     * granularity — callers apply their own exact distance tests.
     *
     * @return number of objects appended
     */
    public int collectTileRect(
            float minX, float minY, float maxX, float maxY, int typeMask, StormObjectList out) {
        return collectChunkRect(
                chunkOf(minX), chunkOf(minY), chunkOf(maxX), chunkOf(maxY), typeMask, out);
    }

    private static void drain(Bucket b, int typeMask, StormObjectList out) {
        for (int t = 0; t < NUM_TYPES; t++) {
            if ((typeMask & (1 << t)) == 0) {
                continue;
            }
            int n = b.counts[t];
            if (n == 0) {
                continue;
            }
            Object[] arr = b.items[t];
            for (int i = 0; i < n; i++) {
                out.add(arr[i]);
            }
        }
    }

    /** A reusable in-place walker over this index; see {@link Cursor}. */
    public Cursor newCursor() {
        return new Cursor();
    }

    /**
     * Walks the objects of an inclusive tile rectangle in place — no candidate list, no copy — and
     * culls on the snapshot position stored beside each object, so an object outside the rectangle
     * costs one {@code long} read from a contiguous array and never a dereference. {@link #begin}
     * derives the covering chunk rectangle; {@link #next} returns the next object whose snapshot
     * tile lies inside the tile rectangle, or {@code null} when exhausted; {@link #culled} counts
     * the objects in the chunk rectangle that the tile test rejected. Not valid across {@link
     * StormChunkIndex#beginTick}; call {@link #end} when done so no world objects stay pinned.
     */
    public final class Cursor {
        private int cx0;
        private int cy0;
        private int cx1;
        private int cy1;
        private int typeMask;
        private int tileMinX;
        private int tileMinY;
        private int tileMaxX;
        private int tileMaxY;

        private boolean active;
        private boolean scanAll;
        private int cellX;
        private int cellY;
        private int bucketPos;
        private Bucket bucket;
        private int type;
        private Object[] arr;
        private long[] pos;
        private int n;
        private int i;
        private int culled;

        private Cursor() {}

        /** Starts a walk over every object of a type in {@code typeMask} inside the tile rect. */
        public void begin(int tileMinX, int tileMinY, int tileMaxX, int tileMaxY, int typeMask) {
            this.tileMinX = tileMinX;
            this.tileMinY = tileMinY;
            this.tileMaxX = tileMaxX;
            this.tileMaxY = tileMaxY;
            this.typeMask = typeMask;
            cx0 = chunkOf(tileMinX);
            cy0 = chunkOf(tileMinY);
            cx1 = chunkOf(tileMaxX);
            cy1 = chunkOf(tileMaxY);
            culled = 0;
            bucket = null;
            arr = null;
            pos = null;
            n = 0;
            i = 0;
            type = NUM_TYPES;
            bucketPos = 0;
            cellX = cx0;
            cellY = cy0;
            active = bucketCount > 0 && cx1 >= cx0 && cy1 >= cy0;
            scanAll = active && (long) (cx1 - cx0 + 1) * (long) (cy1 - cy0 + 1) > bucketCount;
        }

        /** Next object inside the tile rect, or {@code null} once the walk is exhausted. */
        public Object next() {
            while (true) {
                while (i < n) {
                    int k = i++;
                    long p = pos[k];
                    int tx = posX(p);
                    int ty = posY(p);
                    if (tx < tileMinX || tx > tileMaxX || ty < tileMinY || ty > tileMaxY) {
                        culled++;
                        continue;
                    }
                    return arr[k];
                }
                if (bucket != null) {
                    while (++type < NUM_TYPES) {
                        if ((typeMask & (1 << type)) != 0 && bucket.counts[type] > 0) {
                            arr = bucket.items[type];
                            pos = bucket.pos[type];
                            n = bucket.counts[type];
                            i = 0;
                            break;
                        }
                    }
                    if (type < NUM_TYPES) {
                        continue;
                    }
                }
                Bucket b = nextBucket();
                if (b == null) {
                    return null;
                }
                bucket = b;
                type = -1;
                n = 0;
                i = 0;
            }
        }

        private Bucket nextBucket() {
            if (!active) {
                return null;
            }
            if (scanAll) {
                while (bucketPos < bucketCount) {
                    Bucket b = buckets[bucketPos++];
                    if (b.cx >= cx0 && b.cx <= cx1 && b.cy >= cy0 && b.cy <= cy1) {
                        return b;
                    }
                }
                active = false;
                return null;
            }
            while (cellY <= cy1) {
                Bucket b = bucketFor(cellX, cellY, false);
                if (++cellX > cx1) {
                    cellX = cx0;
                    cellY++;
                }
                if (b != null) {
                    return b;
                }
            }
            active = false;
            return null;
        }

        /** Objects in the chunk rectangle whose snapshot tile fell outside the tile rectangle. */
        public int culled() {
            return culled;
        }

        /** Releases bucket references; the cursor can be reused with {@link #begin}. */
        public void end() {
            active = false;
            bucket = null;
            arr = null;
            pos = null;
            n = 0;
            i = 0;
        }
    }

    private Bucket bucketFor(int cx, int cy, boolean create) {
        long key = packKey(cx, cy);
        int idx = hash(key) & tableMask;
        while (true) {
            int slot = slots[idx];
            if (slot == EMPTY_SLOT) {
                if (!create) {
                    return null;
                }
                Bucket b = allocateBucket(cx, cy);
                keys[idx] = key;
                slots[idx] = bucketCount - 1;
                if (bucketCount * 2 > keys.length) {
                    growTable();
                }
                return b;
            }
            if (keys[idx] == key) {
                return buckets[slot];
            }
            idx = (idx + 1) & tableMask;
        }
    }

    private Bucket allocateBucket(int cx, int cy) {
        if (bucketCount == buckets.length) {
            buckets = Arrays.copyOf(buckets, bucketCount << 1);
        }
        Bucket b = buckets[bucketCount];
        if (b == null) {
            b = new Bucket();
            buckets[bucketCount] = b;
        }
        b.reset(cx, cy);
        bucketCount++;
        return b;
    }

    private void growTable() {
        int newCap = keys.length << 1;
        long[] newKeys = new long[newCap];
        int[] newSlots = new int[newCap];
        Arrays.fill(newSlots, EMPTY_SLOT);
        int newMask = newCap - 1;
        for (int i = 0; i < bucketCount; i++) {
            Bucket b = buckets[i];
            long key = packKey(b.cx, b.cy);
            int idx = hash(key) & newMask;
            while (newSlots[idx] != EMPTY_SLOT) {
                idx = (idx + 1) & newMask;
            }
            newKeys[idx] = key;
            newSlots[idx] = i;
        }
        keys = newKeys;
        slots = newSlots;
        tableMask = newMask;
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
