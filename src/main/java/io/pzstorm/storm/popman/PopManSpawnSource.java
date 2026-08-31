package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a repopulating horde walks in from.
 *
 * <p>Vanilla hands the population a set of spawn-origin rectangles (roads leading off the map, and
 * whatever else the map author marked); when it hands over none, the population falls back to
 * walking zombies in from the edge of the world itself. Either way the chosen square must be
 * somewhere a zombie could actually stand, and the search simply gives up after a fixed number of
 * tries rather than widening.
 *
 * <p>These rectangles are in <em>squares</em>, unlike the chunk-based rectangles of {@link
 * PopManLoadedAreas}.
 */
public final class PopManSpawnSource {

    public static final int TRIES = 100;

    private static final int SIDE_WEST = 0;
    private static final int SIDE_NORTH = 1;
    private static final int SIDE_EAST = 2;

    private final PopManMap map;
    private final List<int[]> origins = new ArrayList<>();

    public PopManSpawnSource(PopManMap map) {
        this.map = map;
    }

    public void add(int x, int y, int width, int height) {
        origins.add(new int[] {x, y, width, height});
    }

    public void clear() {
        origins.clear();
    }

    public int count() {
        return origins.size();
    }

    /**
     * Picks a square to walk zombies in from, writing it into {@code out}.
     *
     * <p>{@code out} is overwritten on every attempt, not just the successful one, so a {@code
     * false} return leaves the last rejected candidate behind — the native did the same, and its
     * callers relied on checking the return value rather than the buffer.
     */
    public boolean pick(int[] out) {
        for (int attempt = 0; attempt < TRIES; attempt++) {
            if (origins.isEmpty()) {
                pickOnWorldEdge(out);
            } else {
                int[] rect = origins.get(map.random(origins.size()));
                out[0] = map.randomRange(rect[0], rect[0] + rect[2]);
                out[1] = map.randomRange(rect[1], rect[1] + rect[3]);
            }
            if (map.isValidSpawnSquare(out[0], out[1], 0)) {
                return true;
            }
        }
        return false;
    }

    private void pickOnWorldEdge(int[] out) {
        int minX = map.minCellX() * PopManGeometry.SQUARES_PER_CELL;
        int minY = map.minCellY() * PopManGeometry.SQUARES_PER_CELL;
        int width = map.widthCells() * PopManGeometry.SQUARES_PER_CELL;
        int height = map.heightCells() * PopManGeometry.SQUARES_PER_CELL;

        switch (map.random(4)) {
            case SIDE_WEST -> {
                out[0] = minX;
                out[1] = minY + map.random(height);
            }
            case SIDE_NORTH -> {
                out[0] = minX + map.random(width);
                out[1] = minY;
            }
            case SIDE_EAST -> {
                out[0] = minX + width - 1;
                out[1] = minY + map.random(height);
            }
            default -> {
                out[0] = minX + map.random(width);
                out[1] = minY + height - 1;
            }
        }
    }
}
