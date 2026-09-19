// Port of PZ glue Chunk (Chunk.cpp): ctor @00148cd0, ~Chunk @001490c0, getLevelData @00149000,
// isAdjacentToEdgeOfWorld[_N/_S/_W/_E] @00148ec0.., isReferenced @00148d40, setReferenced
// @00148d30, load @00148f80, removePhysicsBodiesAndClearShapes @00149080, setMinMaxLevel
// @00148d60, setNeed @00149020, setShapes @00149040.
package io.pzstorm.storm.bullet.pz;

/** One 8x8 square chunk: a vertical range of {@link ChunkLevel}s. */
public class Chunk {

    /** +0 */
    public int wx;

    /** +4 */
    public int wy;

    /** +8 */
    public int minLevel;

    /** +0xc */
    public int maxLevel;

    /** +0x10 ChunkLevel*[] (new[]; the old array is leaked when resized) */
    public ChunkLevel[] levels;

    /** +0x18..+0x1b: one "referenced" byte per ChunkMap (player index). */
    public final boolean[] referenced = new boolean[4];

    public Chunk(int wx, int wy) {
        this.wx = wx;
        this.wy = wy;
        this.minLevel = 0;
        this.maxLevel = 0;
        this.levels = new ChunkLevel[1];
        this.levels[0] = ChunkLevel.alloc().init(this, 0);
        referenced[0] = false;
        referenced[1] = false;
        referenced[2] = false;
        referenced[3] = false;
    }

    /** ~Chunk: removes bodies of every level, returns the levels to the pool. */
    public void destroy() {
        removePhysicsBodiesAndClearShapes();
        for (int l = minLevel; l <= maxLevel; l++) {
            levels[l - minLevel].release();
            levels[l - minLevel] = null;
        }
    }

    public ChunkLevel getLevelData(int level) {
        if (minLevel <= level && level <= maxLevel) {
            return levels[level - minLevel];
        }
        return null;
    }

    public boolean isAdjacentToEdgeOfWorld_N() {
        return WorldSimulation.instance.m_minCellY << 5 == wy + 1;
    }

    public boolean isAdjacentToEdgeOfWorld_S() {
        return (WorldSimulation.instance.m_maxCellY + 1) * 32 == wy;
    }

    public boolean isAdjacentToEdgeOfWorld_W() {
        return WorldSimulation.instance.m_minCellX << 5 == wx + 1;
    }

    public boolean isAdjacentToEdgeOfWorld_E() {
        return (WorldSimulation.instance.m_maxCellX + 1) * 32 == wx;
    }

    public boolean isAdjacentToEdgeOfWorld() {
        if (isAdjacentToEdgeOfWorld_N()) {
            return true;
        }
        if (isAdjacentToEdgeOfWorld_S()) {
            return true;
        }
        if (isAdjacentToEdgeOfWorld_W()) {
            return true;
        }
        return isAdjacentToEdgeOfWorld_E();
    }

    public boolean isReferenced() {
        return referenced[0] || referenced[1] || referenced[2] || referenced[3];
    }

    public void setReferenced(int index, boolean b) {
        referenced[index] = b;
    }

    public void load() {
        if (isAdjacentToEdgeOfWorld()) {
            setMinMaxLevel(0, 63);
        }
        for (int l = minLevel; l <= maxLevel; l++) {
            levels[l - minLevel].load();
        }
    }

    public void removePhysicsBodiesAndClearShapes() {
        for (int l = minLevel; l <= maxLevel; l++) {
            levels[l - minLevel].removePhysicsBodiesAndClearShapes();
        }
    }

    public void setMinMaxLevel(int newMin, int newMax) {
        if (minLevel == newMin && maxLevel == newMax) {
            return;
        }
        for (int l = minLevel; l <= maxLevel; l++) {
            if (l < newMin || l > newMax) {
                ChunkLevel lvl = levels[l - minLevel];
                if (lvl != null) {
                    lvl.removePhysicsBodiesAndClearShapes();
                    lvl.release();
                    levels[l - minLevel] = null;
                }
            }
        }
        // (ulong)(int)(newMax - newMin + 1): a negative count sign-extends past the new[] limit
        int size = newMax - newMin + 1;
        if (size < 0) {
            // operator new[] -> std::__throw_bad_array_new_length
            throw new NegativeArraySizeException("std::bad_array_new_length");
        }
        ChunkLevel[] newLevels = new ChunkLevel[size];
        for (int l = newMin; l <= newMax; l++) {
            if (minLevel <= l && l <= maxLevel) {
                newLevels[l - newMin] = levels[l - minLevel];
            } else {
                newLevels[l - newMin] = ChunkLevel.alloc().init(this, l);
            }
        }
        levels = newLevels;
        minLevel = newMin;
        maxLevel = newMax;
    }

    public void setNeed(int level) {
        ChunkLevel lvl = getLevelData(level);
        if (lvl != null) {
            lvl.need = true;
        }
    }

    public void setShapes(int x, int y, int level, int n, int[] shapes) {
        getLevelData(level).setShapes(x, y, n, shapes);
    }
}
