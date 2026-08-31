package io.pzstorm.storm.popman;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongSupplier;

/**
 * The native half of {@code zombie.MapCollisionData}: the update batches the main thread hands the
 * worker, the path-request queue the population feeds, and the {@code n_update} loop that applies
 * one and walks the other.
 *
 * <p>Threading is the DLL's. {@link #chunkUpdateTask}, {@link #squareUpdateTask} and {@link
 * #hasDataForThread} run on the main thread and only touch the current batch; a finished batch
 * crosses to the worker through a concurrent queue. Everything else runs on the {@code
 * MapCollisionData} thread under its render lock — the same thread the population ticks on, which
 * is why {@link #requestPath} and the callbacks it fires need no locking.
 */
public final class PopManMapCollision {

    public interface PathCallback {
        void onPathResult(int status, int endX, int endY);
    }

    /** Path steps between clock checks. */
    static final int STEPS_PER_CLOCK_CHECK = 100;

    /** Path time per {@code n_update} before yielding, in milliseconds. */
    static final long PATH_BUDGET_MS = 51;

    private static final int CHUNK_BYTES = PopManChunkData.SQUARES;

    private record Record(int x, int y, byte[] squares, byte bits) {}

    private static final class Task {
        final int startX;
        final int startY;
        final int endX;
        final int endY;
        final PathCallback callback;

        Task(int startX, int startY, int endX, int endY, PathCallback callback) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.callback = callback;
        }
    }

    private final LongSupplier clockMs;
    private final PopManCollisionGrid grid;
    private final PopManPathSystem path;
    private final ConcurrentLinkedQueue<List<Record>> queued = new ConcurrentLinkedQueue<>();
    private final List<Task> pending = new ArrayList<>();
    private List<Record> batch = new ArrayList<>();
    private Task active;

    public PopManMapCollision(PopManCollisionGrid grid) {
        this(grid, () -> System.nanoTime() / 1_000_000L);
    }

    PopManMapCollision(PopManCollisionGrid grid, LongSupplier clockMs) {
        this.grid = grid;
        this.clockMs = clockMs;
        this.path = new PopManPathSystem(grid);
    }

    public PopManCollisionGrid grid() {
        return grid;
    }

    /** The worker's path system, which the debug renderer draws while a request is walking. */
    public PopManPathSystem pathSystem() {
        return path;
    }

    // --- main thread --------------------------------------------------------

    /** {@code n_chunkUpdateTask}: a freshly loaded chunk's 64 collision bytes. */
    public void chunkUpdateTask(int chunkX, int chunkY, byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (data.length < CHUNK_BYTES) {
            throw new IllegalArgumentException("data.length < 10 * 10");
        }
        byte[] copy = new byte[CHUNK_BYTES];
        System.arraycopy(data, 0, copy, 0, CHUNK_BYTES);
        batch.add(new Record(chunkX, chunkY, copy, (byte) 0));
    }

    /**
     * {@code n_squareUpdateTask}: {@code count} records of big-endian {@code int x, int y, byte
     * bits}, read from the start of the buffer regardless of its position.
     */
    public void squareUpdateTask(int count, ByteBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException("buffer");
        }
        if (count < 0) {
            throw new IllegalArgumentException("invalid count");
        }
        ByteBuffer in = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        in.clear();
        for (int i = 0; i < count; i++) {
            if (in.remaining() < 9) {
                throw new BufferUnderflowException();
            }
            int x = in.getInt();
            int y = in.getInt();
            byte bits = in.get();
            batch.add(new Record(x, y, null, bits));
        }
    }

    /** {@code n_hasDataForThread}: hands the batch over if it holds anything. */
    public boolean hasDataForThread() {
        if (batch.isEmpty()) {
            return false;
        }
        queued.add(batch);
        batch = new ArrayList<>();
        return true;
    }

    // --- worker thread ------------------------------------------------------

    /** {@code n_shouldWait}: nothing queued, nothing pending, nothing walking. */
    public boolean shouldWait() {
        return pending.isEmpty() && active == null && queued.isEmpty();
    }

    /** The population's path request; served LIFO by {@link #update}. */
    public void requestPath(int startX, int startY, int endX, int endY, PathCallback callback) {
        pending.add(new Task(startX, startY, endX, endY, callback));
    }

    public int pendingPaths() {
        return pending.size();
    }

    public boolean hasActivePath() {
        return active != null;
    }

    /**
     * {@code n_pathTask}: walks the whole path in one call and returns its status. The array is
     * validated but never written — the JNI wrapper released it with {@code JNI_ABORT}, so the
     * caller's copy stayed untouched, and this keeps that.
     */
    public int pathTask(int startX, int startY, int endX, int endY, int[] curXY) {
        if (curXY == null) {
            throw new NullPointerException("curXY");
        }
        if (curXY.length < 2) {
            throw new IllegalArgumentException("curXY.length < 2");
        }
        path.begin(startX, startY, endX, endY);
        int status;
        do {
            status = path.step();
        } while (status == PopManPathSystem.STATUS_WALKING);
        return status;
    }

    /** {@code n_update}: apply every queued batch, walk the active path within budget, evict. */
    public void update() {
        for (List<Record> records = queued.poll(); records != null; records = queued.poll()) {
            for (Record record : records) {
                if (record.squares() != null) {
                    grid.applyChunk(record.x(), record.y(), record.squares());
                } else {
                    grid.applySquare(record.x(), record.y(), record.bits());
                }
            }
        }
        if (active == null && !pending.isEmpty()) {
            active = pending.remove(pending.size() - 1);
            path.begin(active.startX, active.startY, active.endX, active.endY);
        }
        if (active != null) {
            long begun = clockMs.getAsLong();
            int steps = 0;
            while (true) {
                int status = path.step();
                if (status != PopManPathSystem.STATUS_WALKING) {
                    Task done = active;
                    active = null;
                    if (done.callback != null) {
                        done.callback.onPathResult(status, path.currentX(), path.currentY());
                    }
                    break;
                }
                if (++steps == STEPS_PER_CLOCK_CHECK) {
                    if (clockMs.getAsLong() - begun >= PATH_BUDGET_MS) {
                        return;
                    }
                    steps = 0;
                }
            }
        }
        grid.evictOneIdle();
    }

    public void save() {
        grid.save();
    }

    /** {@code n_stop}: pending requests are dropped without their callback. */
    public void stop() {
        batch = new ArrayList<>();
        queued.clear();
        grid.stop();
        pending.clear();
        active = null;
    }
}
