package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.logging.StormLogger;
import java.util.HashSet;
import java.util.List;
import zombie.core.raknet.UdpConnection;
import zombie.iso.Vector3;

/**
 * Per-tick set of world cells under player influence, replacing the per-cell connection sweep of
 * vanilla's {@code ServerMap.outsidePlayerInfluence} in {@code StormCellWarmer}.
 *
 * <p>Vanilla's predicate asks, for every loaded cell, whether any of its 4 corners is {@code
 * UdpConnection.isRelevantTo} for any connection — O(loadedCells × connections × 4) full relevance
 * tests per tick (~600k point tests at 1024 warm cells and 116 players on ATF, ~1.4% of the main
 * thread). This class inverts the loop: each connection's influence primitives (up to 4 {@code
 * connectArea} rectangles and 4 {@code releventPos} squares) are rasterized to a conservative
 * candidate cell range, and each candidate is verified with the exact vanilla predicate — the same
 * {@code isRelevantTo} calls on the same 4 corner points, so a cell is in the set if and only if
 * vanilla's {@code !outsidePlayerInfluence} would return true. Cost is O(connections ×
 * influence-area cells), independent of how many cells are loaded or warm.
 *
 * <p>The rasterization is only a candidate generator, so it may over-approximate freely (bounds are
 * widened by one cell against float edge cases); correctness rests solely on it never
 * under-approximating: a corner point can only satisfy a primitive if it lies inside that
 * primitive's bounding box, and every cell owning such a corner lies inside the widened cell range
 * of that box.
 *
 * <p>Server main-thread only, like everything in the warmer.
 */
final class StormPlayerInfluenceGrid {

    /**
     * Defensive per-axis cap on a single primitive's candidate range. The largest legitimate
     * primitive is a {@code releventPos} square of half-width {@code relevantRange * 8} ≤ 1016
     * tiles ≈ 32 cells; a span beyond this cap means a corrupt/hostile coordinate (NaN and infinity
     * are skipped outright) and is clamped around the box centre — under-clamping only risks
     * evicting a warm cell early, which is a vanilla unload, not a correctness loss.
     */
    private static final int MAX_PRIMITIVE_SPAN_CELLS = 1024;

    private static boolean loggedSpanClamp;

    private final HashSet<Long> influenced = new HashSet<>();

    private static long key(int wx, int wy) {
        return ((long) wx & 0xffffffffL) | (((long) wy & 0xffffffffL) << 32);
    }

    /** True if any corner of cell ({@code wx},{@code wy}) is under player influence. */
    boolean containsCell(int wx, int wy) {
        return influenced.contains(key(wx, wy));
    }

    /**
     * True if any cell within Chebyshev distance {@code margin} of ({@code wx},{@code wy}) is
     * influenced — the dilated test the eviction policy uses to prefer evicting cells no player is
     * near.
     */
    boolean nearInfluence(int wx, int wy, int margin) {
        for (int dx = -margin; dx <= margin; dx++) {
            for (int dy = -margin; dy <= margin; dy++) {
                if (influenced.contains(key(wx + dx, wy + dy))) {
                    return true;
                }
            }
        }
        return false;
    }

    int size() {
        return influenced.size();
    }

    /** Rebuilds the set from the current connections. Call once per tick before consulting it. */
    void rebuild(List<UdpConnection> connections) {
        influenced.clear();
        for (int n = 0; n < connections.size(); n++) {
            UdpConnection c = connections.get(n);
            for (int i = 0; i < 4; i++) {
                Vector3 area = c.connectArea[i];
                if (area != null) {
                    // Mirrors isRelevantTo's connectArea box (half-open upper edge; the widened
                    // candidate range absorbs the open/closed distinction).
                    int chunkMapWidth = (int) area.z;
                    int minX = zombie.core.math.PZMath.fastfloor(area.x - chunkMapWidth / 2) * 8;
                    int minY = zombie.core.math.PZMath.fastfloor(area.y - chunkMapWidth / 2) * 8;
                    rasterize(c, minX, minY, minX + chunkMapWidth * 8, minY + chunkMapWidth * 8);
                }
                Vector3 pos = c.releventPos[i];
                if (pos != null) {
                    int range = c.getRelevantRange() * 8;
                    rasterize(c, pos.x - range, pos.y - range, pos.x + range, pos.y + range);
                }
            }
        }
    }

    // Adds every influenced cell whose corner could lie in the tile-space box [bx1..bx2]x[by1..by2]
    // for this connection. Cell k owns corners k*64 and (k+1)*64, so the candidate k-range for an
    // axis is [floor(b1/64)-1, floor(b2/64)+1]; each candidate is then verified with the exact
    // vanilla corner predicate against the full connection, never the box alone.
    private void rasterize(UdpConnection c, float bx1, float by1, float bx2, float by2) {
        if (!Float.isFinite(bx1)
                || !Float.isFinite(by1)
                || !Float.isFinite(bx2)
                || !Float.isFinite(by2)) {
            return;
        }
        int kx1 = (int) Math.floor(bx1 / 64.0) - 1;
        int kx2 = (int) Math.floor(bx2 / 64.0) + 1;
        int ky1 = (int) Math.floor(by1 / 64.0) - 1;
        int ky2 = (int) Math.floor(by2 / 64.0) + 1;
        if ((long) kx2 - kx1 > MAX_PRIMITIVE_SPAN_CELLS
                || (long) ky2 - ky1 > MAX_PRIMITIVE_SPAN_CELLS) {
            if (!loggedSpanClamp) {
                loggedSpanClamp = true;
                StormLogger.LOGGER.error(
                        "StormPlayerInfluenceGrid: influence primitive spans {}x{} cells — corrupt"
                                + " coordinates? Clamping (logged once)",
                        (long) kx2 - kx1,
                        (long) ky2 - ky1);
            }
            long cx = ((long) kx1 + kx2) / 2;
            long cy = ((long) ky1 + ky2) / 2;
            kx1 = (int) Math.max(kx1, cx - MAX_PRIMITIVE_SPAN_CELLS / 2);
            kx2 = (int) Math.min(kx2, cx + MAX_PRIMITIVE_SPAN_CELLS / 2);
            ky1 = (int) Math.max(ky1, cy - MAX_PRIMITIVE_SPAN_CELLS / 2);
            ky2 = (int) Math.min(ky2, cy + MAX_PRIMITIVE_SPAN_CELLS / 2);
        }
        for (int wx = kx1; wx <= kx2; wx++) {
            for (int wy = ky1; wy <= ky2; wy++) {
                long k = key(wx, wy);
                if (influenced.contains(k)) {
                    continue;
                }
                int x1 = wx * 64;
                int y1 = wy * 64;
                int x2 = (wx + 1) * 64;
                int y2 = (wy + 1) * 64;
                if (c.isRelevantTo(x1, y1)
                        || c.isRelevantTo(x2, y1)
                        || c.isRelevantTo(x2, y2)
                        || c.isRelevantTo(x1, y2)) {
                    influenced.add(k);
                }
            }
        }
    }
}
