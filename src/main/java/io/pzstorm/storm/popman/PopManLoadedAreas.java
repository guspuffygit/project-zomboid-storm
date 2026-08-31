package io.pzstorm.storm.popman;

import java.util.Arrays;

/**
 * The boxes players are currently streaming in. The population stays out of them: repopulation
 * skips any chunk that overlaps one, and its flood fill fences every such square off before it
 * starts.
 *
 * <p>Rectangles are in <em>chunk</em> coordinates and half-open, while every query arrives in
 * squares — the same mismatch the native carried, and the reason the conversion lives in one place.
 */
public final class PopManLoadedAreas {

    /** Vanilla never sends more than this many, and silently drops the rest. */
    public static final int MAX_AREAS = 64;

    private static final int INTS_PER_AREA = 4;

    private int[] areas = new int[0];
    private int count;

    /** Replaces the whole set from the packed {@code x, y, width, height} quads vanilla sends. */
    public void set(int[] packed, int areaCount) {
        int accepted = Math.min(areaCount, MAX_AREAS);
        if (packed == null || accepted <= 0) {
            count = 0;
            return;
        }
        int ints = accepted * INTS_PER_AREA;
        if (areas.length < ints) {
            areas = new int[ints];
        }
        System.arraycopy(packed, 0, areas, 0, Math.min(ints, packed.length));
        count = accepted;
    }

    public void clear() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public int[] packed() {
        return Arrays.copyOf(areas, count * INTS_PER_AREA);
    }

    public boolean containsSquare(int squareX, int squareY) {
        for (int i = 0; i < count; i++) {
            int at = i * INTS_PER_AREA;
            int minX = areas[at] * PopManGeometry.SQUARES_PER_CHUNK;
            int minY = areas[at + 1] * PopManGeometry.SQUARES_PER_CHUNK;
            int maxX = minX + areas[at + 2] * PopManGeometry.SQUARES_PER_CHUNK;
            int maxY = minY + areas[at + 3] * PopManGeometry.SQUARES_PER_CHUNK;
            if (squareX >= minX && squareX < maxX && squareY >= minY && squareY < maxY) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a square lies on the outermost one-square ring of any loaded area. A horde standing
     * on the ring is given another tick as one object; a horde strictly inside is turned into real
     * zombies at once. Without that distinction every group brushing the boundary would realise,
     * and a wall of zombies would appear along the edge of the loaded box.
     *
     * <p>The ring is one <em>square</em> thick even though the rectangles are measured in chunks,
     * which is what makes the cheap eight-square test below exact rather than approximate.
     */
    public boolean isOnPerimeterSquare(int squareX, int squareY) {
        int spacing = PopManGeometry.SQUARES_PER_CHUNK;
        if (squareX % spacing != 0
                && (squareX + 1) % spacing != 0
                && squareY % spacing != 0
                && (squareY + 1) % spacing != 0) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            int at = i * INTS_PER_AREA;
            int minX = areas[at] * spacing;
            int minY = areas[at + 1] * spacing;
            int maxX = minX + areas[at + 2] * spacing;
            int maxY = minY + areas[at + 3] * spacing;
            boolean insideX = squareX >= minX && squareX < maxX;
            boolean insideY = squareY >= minY && squareY < maxY;
            if (insideX && (squareY == minY || squareY == maxY - 1)) {
                return true;
            }
            if (insideY && (squareX == minX || squareX == maxX - 1)) {
                return true;
            }
        }
        return false;
    }
}
