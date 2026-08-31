package io.pzstorm.storm.popman;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * A map whose every answer is dictated by the test, including the dice. Rolls come from a scripted
 * queue so that a test can assert not just the outcome but which draws were consumed — the native's
 * draw order is part of the contract being reproduced.
 */
class ScriptedWorld extends PopManMap {

    private static final PopManWorld UNUSED =
            new PopManWorld() {
                @Override
                public int squareFlags(int squareX, int squareY) {
                    throw new AssertionError("overridden");
                }

                @Override
                public int densityByte(int chunkX, int chunkY) {
                    throw new AssertionError("overridden");
                }
            };

    final Deque<Integer> rolls = new ArrayDeque<>();
    int rollsTaken;

    final Set<Long> blockedSquares = new HashSet<>();
    final Set<Long> loadedSquares = new HashSet<>();

    int densityByte = 128;
    boolean blocked;
    boolean everySquareSpawnable = true;
    boolean insideLoadedArea;
    boolean uniformMode;
    boolean zombiesDisabled;
    float unitRoll;

    ScriptedWorld() {
        super(UNUSED, new PopManGameState(), new PopManRandom(1));
        setWorldBounds(-64, -64, 128, 128);
    }

    ScriptedWorld roll(int... values) {
        for (int value : values) {
            rolls.addLast(value);
        }
        return this;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * Paints a rectangle of the map from text, one character per square: {@code #} impassable,
     * {@code L} inside a player's loaded area, anything else open.
     */
    ScriptedWorld map(int originX, int originY, String... rows) {
        for (int y = 0; y < rows.length; y++) {
            for (int x = 0; x < rows[y].length(); x++) {
                char c = rows[y].charAt(x);
                if (c == '#') {
                    blockedSquares.add(key(originX + x, originY + y));
                } else if (c == 'L') {
                    loadedSquares.add(key(originX + x, originY + y));
                }
            }
        }
        return this;
    }

    ScriptedWorld blockColumn(int x, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            blockedSquares.add(key(x, y));
        }
        return this;
    }

    ScriptedWorld loadRect(int x, int y, int width, int height) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                loadedSquares.add(key(x + dx, y + dy));
            }
        }
        return this;
    }

    @Override
    public int squareFlags(int squareX, int squareY) {
        return blockedSquares.contains(key(squareX, squareY)) ? BIT_SOLID : 0;
    }

    @Override
    public int densityByte(int chunkX, int chunkY) {
        return densityByte;
    }

    @Override
    public boolean isChunkBlocked(int chunkX, int chunkY) {
        return blocked || super.isChunkBlocked(chunkX, chunkY);
    }

    @Override
    public boolean isValidSpawnSquare(int squareX, int squareY, int z) {
        return everySquareSpawnable && !blockedSquares.contains(key(squareX, squareY));
    }

    @Override
    public boolean isInsideLoadedArea(int squareX, int squareY) {
        return insideLoadedArea || loadedSquares.contains(key(squareX, squareY));
    }

    @Override
    public boolean isUniformDensityMode() {
        return uniformMode;
    }

    @Override
    public boolean areZombiesDisabled() {
        return zombiesDisabled;
    }

    @Override
    public float randomUnit() {
        rollsTaken++;
        return unitRoll;
    }

    @Override
    public float randomFloat(float min, float max) {
        rollsTaken++;
        return min + (max - min) * unitRoll;
    }

    @Override
    public int random(int bound) {
        rollsTaken++;
        Integer scripted = rolls.pollFirst();
        if (scripted == null) {
            return 0;
        }
        if (scripted >= bound) {
            throw new IllegalStateException(
                    "scripted roll " + scripted + " does not fit rand(" + bound + ")");
        }
        return scripted;
    }

    @Override
    public int randomRange(int min, int max) {
        return min + random(max - min);
    }
}
