package io.pzstorm.storm.popman;

import java.nio.ByteBuffer;
import java.util.function.IntSupplier;

/**
 * One zombie in the population — native {@code popman::Zombie}, 32 bytes, the same eight fields in
 * the same order. Position, facing, outfit and state, plus an optional path target; that is the
 * whole of what the native population kept per zombie, and exactly what crosses the ByteBuffer
 * boundary in both directions.
 *
 * <p>Deliberately not called {@code VirtualZombie}: native {@code popman::VirtualZombie} is an
 * 80-byte <em>horde</em> — a leader plus a member list — not a single zombie.
 *
 * <p>Both record layouts are big-endian and addressed absolutely from offset 0: vanilla hands the
 * buffer over without {@code flip()}, so position and limit carry no meaning. See {@code
 * docs/re-popman/01-java-contract.md} §4.
 */
public final class PopManZombie {

    /** {@code ZombiePopulationManager.INVALID_PATH_XY} — "this zombie has no path target". */
    public static final int INVALID_PATH_XY = Integer.MIN_VALUE;

    public static final int BUFFER_BYTES = 1024;

    /** Java writes these when handing real zombies over to be saved. */
    public static final int SAVE_RECORD_BYTES = 21;

    /**
     * Native writes these when handing zombies back to be realised; the save record plus a path.
     */
    public static final int ADD_RECORD_BYTES = 29;

    /**
     * The on-disk {@code zpop} record. Deliberately not the wire record: disk writes {@code state}
     * before {@code descriptorID} where the buffer writes them the other way round, and {@code z}
     * narrows to a signed byte. Getting that swap wrong yields files that parse cleanly and
     * silently mis-dress every zombie.
     */
    public static final int DISK_RECORD_BYTES = 18;

    public static final int MAX_SAVE_RECORDS = BUFFER_BYTES / SAVE_RECORD_BYTES;
    public static final int MAX_ADD_RECORDS = BUFFER_BYTES / ADD_RECORD_BYTES;

    public float x;
    public float y;
    public float z;
    public byte dir;
    public int descriptorID;
    public int stateFlags;
    public int pathTargetX = INVALID_PATH_XY;
    public int pathTargetY = INVALID_PATH_XY;

    /**
     * {@code ZombieStateFlag.CanWalk}, and nothing else. The native leaves {@code Initialized}
     * clear on a freshly spawned zombie so that the Java side knows it still has to be dressed and
     * given a descriptor.
     */
    public static final int SPAWN_STATE_FLAGS = 4;

    /** How many facings a spawned zombie picks between. */
    public static final int DIRECTION_COUNT = 8;

    /**
     * A zombie the population invented, placed at the centre of a square with no path target. The
     * native picks its facing through a permutation table of the eight {@code IsoDirections}
     * ordinals; drawing the ordinal directly is the same distribution, so the table's contents do
     * not matter.
     *
     * <p>{@code descriptorID} is zero because the native hard-codes it — the outfit names handed
     * over by {@code n_setOutfitNames} are never consulted on the spawn path.
     *
     * @param randomDirection supplies {@code rand(8)}, uniform over {@code 0..7}
     */
    public static PopManZombie spawnedAt(int squareX, int squareY, IntSupplier randomDirection) {
        return spawnedAt(squareX + 0.5F, squareY + 0.5F, randomDirection);
    }

    /**
     * The same zombie at an arbitrary position. Horde members are placed on quarter-square offsets
     * rather than square centres, so they cannot go through the square-indexed factory above.
     */
    public static PopManZombie spawnedAt(float x, float y, IntSupplier randomDirection) {
        PopManZombie zombie = new PopManZombie();
        zombie.x = x;
        zombie.y = y;
        zombie.z = 0.0F;
        zombie.dir = (byte) randomDirection.getAsInt();
        zombie.descriptorID = 0;
        zombie.stateFlags = SPAWN_STATE_FLAGS;
        return zombie;
    }

    public boolean hasPathTarget() {
        return pathTargetX != INVALID_PATH_XY && pathTargetY != INVALID_PATH_XY;
    }

    public void clearPathTarget() {
        pathTargetX = INVALID_PATH_XY;
        pathTargetY = INVALID_PATH_XY;
    }

    /** Reads the {@code index}-th 21-byte record Java packed for saving. */
    public void readSaveRecord(ByteBuffer buf, int index) {
        int at = index * SAVE_RECORD_BYTES;
        x = buf.getFloat(at);
        y = buf.getFloat(at + 4);
        z = buf.getFloat(at + 8);
        dir = buf.get(at + 12);
        descriptorID = buf.getInt(at + 13);
        stateFlags = buf.getInt(at + 17);
        clearPathTarget();
    }

    /** Writes the {@code index}-th 29-byte record for Java to realise. */
    public void writeAddRecord(ByteBuffer buf, int index) {
        int at = index * ADD_RECORD_BYTES;
        buf.putFloat(at, x);
        buf.putFloat(at + 4, y);
        buf.putFloat(at + 8, z);
        buf.put(at + 12, dir);
        buf.putInt(at + 13, descriptorID);
        buf.putInt(at + 17, stateFlags);
        buf.putInt(at + 21, pathTargetX);
        buf.putInt(at + 25, pathTargetY);
    }

    /** Mirror of {@link #writeAddRecord}, for tests and for reading back our own output. */
    public void readAddRecord(ByteBuffer buf, int index) {
        int at = index * ADD_RECORD_BYTES;
        x = buf.getFloat(at);
        y = buf.getFloat(at + 4);
        z = buf.getFloat(at + 8);
        dir = buf.get(at + 12);
        descriptorID = buf.getInt(at + 13);
        stateFlags = buf.getInt(at + 17);
        pathTargetX = buf.getInt(at + 21);
        pathTargetY = buf.getInt(at + 25);
    }

    /** Writes the 18-byte {@code zpop} form at an absolute byte offset. */
    public void writeDiskRecord(ByteBuffer buf, int at) {
        buf.putFloat(at, x);
        buf.putFloat(at + 4, y);
        buf.put(at + 8, (byte) Math.floor(z));
        buf.put(at + 9, dir);
        buf.putInt(at + 10, stateFlags);
        buf.putInt(at + 14, descriptorID);
    }

    /** Reads the 18-byte {@code zpop} form from an absolute byte offset. */
    public void readDiskRecord(ByteBuffer buf, int at) {
        x = buf.getFloat(at);
        y = buf.getFloat(at + 4);
        z = buf.get(at + 8);
        dir = buf.get(at + 9);
        stateFlags = buf.getInt(at + 10);
        descriptorID = buf.getInt(at + 14);
        clearPathTarget();
    }

    /** Mirror of {@link #readSaveRecord}, for tests. */
    public void writeSaveRecord(ByteBuffer buf, int index) {
        int at = index * SAVE_RECORD_BYTES;
        buf.putFloat(at, x);
        buf.putFloat(at + 4, y);
        buf.putFloat(at + 8, z);
        buf.put(at + 12, dir);
        buf.putInt(at + 13, descriptorID);
        buf.putInt(at + 17, stateFlags);
    }
}
