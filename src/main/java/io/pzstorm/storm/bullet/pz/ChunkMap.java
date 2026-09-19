// Port of PZ glue ChunkMap (Chunk.cpp): ctor @0014d650, ~ChunkMap @0014d760, adoptChunk @0014da70,
// createChunk @0014daa0, load @0014d9f0, scrollDown @0014ded0, scrollLeft @0014db10, scrollRight
// @0014dc40, scrollUp @0014dd90, setNeed @0014d840.
package io.pzstorm.storm.bullet.pz;

/** A player's square window of chunks; chunks can be shared with other players' maps. */
public class ChunkMap {

    /** +0: player index (index into Chunk.referenced). */
    public int playerIndex;

    /** +4: world chunk x of column 0. */
    public int wx;

    /** +8: world chunk y of row 0. */
    public int wy;

    /** +0xc */
    public int width;

    /** +0x10: max(width / 2 - 1, 0) (never read by the glue). */
    public int m_centerMin;

    /** +0x14: min(width / 2 + 1, width) (never read by the glue). */
    public int m_centerMax;

    /** +0x18: Chunk**[width][width], indexed [x][y]. */
    public Chunk[][] chunks;

    public ChunkMap(int playerIndex, int wx, int wy, int width) {
        this.playerIndex = playerIndex;
        this.wx = wx;
        this.wy = wy;
        this.width = width;
        int lo = width / 2 - 1;
        if (lo < 0) {
            lo = 0;
        }
        int hi = width / 2 + 1;
        if (width < hi) {
            hi = width;
        }
        m_centerMin = lo;
        m_centerMax = hi;
        if (width < 0) {
            // new Chunk**[width] -> __cxa_throw_bad_array_new_length
            throw new NegativeArraySizeException("std::bad_array_new_length");
        }
        chunks = new Chunk[width][width];
    }

    /** ~ChunkMap: drops this player's reference; deletes chunks nobody else references. */
    public void destroy() {
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                Chunk c = chunks[x][y];
                if (c != null) {
                    c.setReferenced(playerIndex, false);
                    if (!c.isReferenced()) {
                        chunks[x][y].destroy();
                        chunks[x][y] = null;
                    }
                }
            }
        }
    }

    public void adoptChunk(Chunk chunk) {
        chunks[chunk.wx - wx][chunk.wy - wy] = chunk;
        chunk.setReferenced(playerIndex, true);
    }

    public Chunk createChunk(int x, int y) {
        Chunk c = new Chunk(wx + x, wy + y);
        c.setReferenced(playerIndex, true);
        chunks[x][y] = c;
        return c;
    }

    public void load() {
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) {
                chunks[x][y].load();
            }
        }
    }

    /** ~Chunk + delete when the dropped chunk is referenced by no other map. */
    private void dropChunk(Chunk old) {
        old.setReferenced(playerIndex, false);
        boolean ref = old.isReferenced();
        if (old != null && !ref) {
            old.destroy();
        }
    }

    /** Window moves +1 in y: row 0 drops, a new row enters at width-1. */
    public void scrollDown() {
        for (int x = 0; x < width; x++) {
            Chunk[] col = chunks[x];
            Chunk old = col[0];
            if (1 < width) {
                System.arraycopy(col, 1, col, 0, width - 1);
            }
            col[width - 1] = null;
            dropChunk(old);
            Chunk c = WorldSimulation.instance.getChunkForAnyPlayer(wx + x, wy + width);
            if (c == null) {
                Chunk n = createChunk(x, width - 1);
                n.wy = n.wy + 1;
            } else {
                chunks[x][width - 1] = c;
                c.setReferenced(playerIndex, true);
            }
        }
        wy += 1;
    }

    /** Window moves -1 in x: column width-1 drops, a new column enters at 0. */
    public void scrollLeft() {
        for (int y = 0; y < width; y++) {
            Chunk old = chunks[width - 1][y];
            for (int i = width - 1; i != 0; i--) {
                chunks[i][y] = chunks[i - 1][y];
            }
            chunks[0][y] = null;
            dropChunk(old);
            Chunk c = WorldSimulation.instance.getChunkForAnyPlayer(wx - 1, wy + y);
            if (c == null) {
                Chunk n = createChunk(0, y);
                n.wx = n.wx - 1;
            } else {
                chunks[0][y] = c;
                c.setReferenced(playerIndex, true);
            }
        }
        wx -= 1;
    }

    /** Window moves +1 in x: column 0 drops, a new column enters at width-1. */
    public void scrollRight() {
        for (int y = 0; y < width; y++) {
            Chunk old = chunks[0][y];
            if (1 < width) {
                for (int i = 0; i != width - 1; i++) {
                    chunks[i][y] = chunks[i + 1][y];
                }
            }
            chunks[width - 1][y] = null;
            dropChunk(old);
            Chunk c = WorldSimulation.instance.getChunkForAnyPlayer(width + wx, wy + y);
            if (c == null) {
                Chunk n = createChunk(width - 1, y);
                n.wx = n.wx + 1;
            } else {
                chunks[width - 1][y] = c;
                c.setReferenced(playerIndex, true);
            }
        }
        wx += 1;
    }

    /** Window moves -1 in y: row width-1 drops, a new row enters at 0. */
    public void scrollUp() {
        for (int x = 0; x < width; x++) {
            Chunk[] col = chunks[x];
            Chunk old = col[width - 1];
            if (width - 1 != 0) {
                System.arraycopy(col, 0, col, 1, width - 1);
            }
            col[0] = null;
            dropChunk(old);
            Chunk c = WorldSimulation.instance.getChunkForAnyPlayer(wx + x, wy - 1);
            if (c == null) {
                Chunk n = createChunk(x, 0);
                n.wy = n.wy - 1;
            } else {
                chunks[x][0] = c;
                c.setReferenced(playerIndex, true);
            }
        }
        wy -= 1;
    }

    /** Marks levels level-1..level+1 of the 3x3 chunks around square (x, y) as needed. */
    public void setNeed(int x, int y, int level) {
        int cx = (int) (float) Math.floor((float) x * 0.125f);
        int cy = (int) (float) Math.floor((float) y * 0.125f);
        int mapX0 = wx;
        int mapY0 = wy;
        int ix = cx - mapX0 - 1;
        int iy0 = cy - mapY0 - 1;
        boolean more;
        do {
            if (-1 < ix) {
                int iy = iy0;
                while (true) {
                    if (iy < width && -1 < iy && ix < width) {
                        int l = level - 1;
                        boolean b;
                        do {
                            chunks[ix][iy].setNeed(l);
                            b = l <= level;
                            l++;
                        } while (b);
                    }
                    if (cy - mapY0 < iy) {
                        break;
                    }
                    iy++;
                }
            }
            more = ix <= cx - mapX0;
            ix++;
        } while (more);
    }
}
