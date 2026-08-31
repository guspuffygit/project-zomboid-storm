package io.pzstorm.storm.popman;

import java.util.function.IntBinaryOperator;

/**
 * The map as the population sees it: collision questions, the areas players are streaming in, the
 * sandbox switches, and the dice.
 *
 * <p>The native answered all of this from one primitive — a square's collision flags — plus a
 * rectangle list and a Mersenne Twister. Deriving the rest here rather than behind {@link
 * PopManWorld} keeps the derivation itself under test, since it is where the population's spawn
 * placement actually comes from.
 */
public class PopManMap implements PopManPathSystem.Terrain {

    public static final int BIT_SOLID = 0x01;
    public static final int BIT_WALL_N = 0x02;
    public static final int BIT_WALL_W = 0x04;
    public static final int BIT_WATER = 0x08;
    public static final int BIT_ROOM = 0x10;

    /** Solid, water or indoors — the one mask every population passability test uses. */
    public static final int BLOCK_MASK = BIT_SOLID | BIT_WATER | BIT_ROOM;

    private final PopManWorld world;
    private final PopManGameState gameState;
    private final PopManRandom random;
    private final PopManLoadedAreas loadedAreas = new PopManLoadedAreas();
    private final PopManLoadedAreas serverCells = new PopManLoadedAreas();
    private boolean server;

    private int minCellX;
    private int minCellY;
    private int widthCells;
    private int heightCells;

    public PopManMap(PopManWorld world) {
        this(world, new PopManGameState(), new PopManRandom());
    }

    public PopManMap(PopManWorld world, PopManGameState gameState, PopManRandom random) {
        this.world = world;
        this.gameState = gameState;
        this.random = random;
    }

    public PopManGameState gameState() {
        return gameState;
    }

    public PopManRandom random() {
        return random;
    }

    public PopManLoadedAreas loadedAreas() {
        return loadedAreas;
    }

    /** The dedicated server's own loaded rectangles, which stand in for the client's areas. */
    public PopManLoadedAreas serverCells() {
        return serverCells;
    }

    /** Comes from {@code n_init}, not from any sandbox option. */
    public void setServer(boolean server) {
        this.server = server;
    }

    public boolean isServer() {
        return server;
    }

    /**
     * @see PopManLoadedAreas#isOnPerimeterSquare
     */
    public boolean isOnLoadedPerimeter(int squareX, int squareY) {
        return (server ? serverCells : loadedAreas).isOnPerimeterSquare(squareX, squareY);
    }

    public void setWorldBounds(int minCellX, int minCellY, int widthCells, int heightCells) {
        this.minCellX = minCellX;
        this.minCellY = minCellY;
        this.widthCells = widthCells;
        this.heightCells = heightCells;
    }

    public int minCellX() {
        return minCellX;
    }

    public int minCellY() {
        return minCellY;
    }

    public int widthCells() {
        return widthCells;
    }

    public int heightCells() {
        return heightCells;
    }

    @Override
    public int squareFlags(int squareX, int squareY) {
        return world.squareFlags(squareX, squareY);
    }

    public int densityByte(int chunkX, int chunkY) {
        return world.densityByte(chunkX, chunkY);
    }

    public boolean isSquareBlocked(int squareX, int squareY) {
        return (squareFlags(squareX, squareY) & BLOCK_MASK) != 0;
    }

    /**
     * Whether a zombie can step from one square to an adjacent one.
     *
     * <p>Only the destination is tested against the block mask — the square being left is never
     * consulted, so a zombie that somehow ends up inside a wall can still walk out of it. Walls
     * belong to the square they are drawn on, so a step north is stopped by the north wall of the
     * square being left while a step south is stopped by the north wall of the square being
     * entered. A diagonal must have both of its corner routes entirely clear, which is what keeps
     * zombies from cutting building corners.
     *
     * <p>A zero-length step degenerates to asking whether the square itself is blocked, and no
     * adjacency is enforced; every caller passes single-square offsets.
     */
    @Override
    public boolean isMoveBlocked(int fromX, int fromY, int toX, int toY) {
        return isMoveBlocked(this::squareFlags, fromX, fromY, toX, toY);
    }

    /** The same test over any flag source; the collision map's pathfinder asks it directly. */
    public static boolean isMoveBlocked(
            IntBinaryOperator flags, int fromX, int fromY, int toX, int toY) {
        int fromFlags = flags.applyAsInt(fromX, fromY);
        int toFlags = flags.applyAsInt(toX, toY);

        if ((toFlags & BLOCK_MASK) != 0) {
            return true;
        }
        if (toY < fromY && (fromFlags & BIT_WALL_N) != 0) {
            return true;
        }
        if (toX < fromX && (fromFlags & BIT_WALL_W) != 0) {
            return true;
        }
        if (toY > fromY && (toFlags & BIT_WALL_N) != 0) {
            return true;
        }
        if (toX > fromX && (toFlags & BIT_WALL_W) != 0) {
            return true;
        }
        if (fromX == toX || fromY == toY) {
            return false;
        }
        return isMoveBlocked(flags, fromX, fromY, fromX, toY)
                || isMoveBlocked(flags, fromX, fromY, toX, fromY)
                || isMoveBlocked(flags, toX, toY, fromX, toY)
                || isMoveBlocked(flags, toX, toY, toX, fromY);
    }

    /** True when every one of the chunk's 64 squares is blocked, so no dice need be rolled. */
    public boolean isChunkBlocked(int chunkX, int chunkY) {
        int minX = chunkX * PopManGeometry.SQUARES_PER_CHUNK;
        int minY = chunkY * PopManGeometry.SQUARES_PER_CHUNK;
        for (int y = 0; y < PopManGeometry.SQUARES_PER_CHUNK; y++) {
            for (int x = 0; x < PopManGeometry.SQUARES_PER_CHUNK; x++) {
                if (!isSquareBlocked(minX + x, minY + y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean blockedFromNorth(int x, int y) {
        return isMoveBlocked(x, y, x, y - 1);
    }

    private boolean blockedFromSouth(int x, int y) {
        return isMoveBlocked(x, y, x, y + 1);
    }

    /**
     * The west and east probes step <em>inwards</em>, from the neighbour into the square. Because
     * wall bits belong to one side of the boundary, asking in the other direction is a different
     * question and gives a different answer.
     */
    private boolean blockedFromWest(int x, int y) {
        return isMoveBlocked(x - 1, y, x, y);
    }

    private boolean blockedFromEast(int x, int y) {
        return isMoveBlocked(x + 1, y, x, y);
    }

    /** True when all four neighbours are cut off from this square. */
    public boolean isSealed(int x, int y) {
        return blockedFromNorth(x, y)
                && blockedFromSouth(x, y)
                && blockedFromWest(x, y)
                && blockedFromEast(x, y);
    }

    private boolean isOpenEastOnly(int x, int y) {
        return !isSquareBlocked(x, y)
                && blockedFromNorth(x, y)
                && blockedFromSouth(x, y)
                && blockedFromWest(x, y)
                && !blockedFromEast(x, y);
    }

    private boolean isOpenWestOnly(int x, int y) {
        return !isSquareBlocked(x, y)
                && blockedFromNorth(x, y)
                && blockedFromSouth(x, y)
                && !blockedFromWest(x, y)
                && blockedFromEast(x, y);
    }

    private boolean isOpenSouthOnly(int x, int y) {
        return !isSquareBlocked(x, y)
                && blockedFromNorth(x, y)
                && !blockedFromSouth(x, y)
                && blockedFromWest(x, y)
                && blockedFromEast(x, y);
    }

    private boolean isOpenNorthOnly(int x, int y) {
        return !isSquareBlocked(x, y)
                && !blockedFromNorth(x, y)
                && blockedFromSouth(x, y)
                && blockedFromWest(x, y)
                && blockedFromEast(x, y);
    }

    /**
     * Whether a zombie may be placed here: not blocked, not walled in, and not one half of a
     * two-tile pocket whose only exits lead to each other.
     *
     * <p>Longer dead ends are accepted — a three-tile corridor is a perfectly good spawn — so this
     * rejects places a zombie could never leave, not places it would struggle to leave.
     *
     * <p>The {@code z} the native took was never read by any of the tests below; it is kept on the
     * signature because every call site passes a literal zero and dropping it would hide that.
     */
    public boolean isValidSpawnSquare(int x, int y, int z) {
        if (isSquareBlocked(x, y) || isSealed(x, y)) {
            return false;
        }
        if (isOpenEastOnly(x, y) && isOpenWestOnly(x + 1, y)) {
            return false;
        }
        if (isOpenEastOnly(x - 1, y) && isOpenWestOnly(x, y)) {
            return false;
        }
        if (isOpenSouthOnly(x, y) && isOpenNorthOnly(x, y + 1)) {
            return false;
        }
        return !(isOpenSouthOnly(x, y - 1) && isOpenNorthOnly(x, y));
    }

    /**
     * Whether a square lies outside the metagrid entirely. World bounds are held in cells and
     * scaled by 256 here; the lower edge is inclusive and the upper edge exclusive.
     *
     * <p>Separate from {@link #squareFlags}, which reports out-of-world squares as solid: movement
     * asks this instead, so a horde that reaches the edge of the map stops rather than grinding
     * against a wall of implied collision.
     */
    public boolean isOutsideWorld(int squareX, int squareY) {
        int localX = squareX - minCellX * PopManGeometry.SQUARES_PER_CELL;
        int localY = squareY - minCellY * PopManGeometry.SQUARES_PER_CELL;
        return localX < 0
                || localY < 0
                || localX >= widthCells * PopManGeometry.SQUARES_PER_CELL
                || localY >= heightCells * PopManGeometry.SQUARES_PER_CELL;
    }

    /**
     * Walks a straight line between two squares and reports whether nothing blocked it, stepping
     * one square at a time along whichever axis the line travels furthest — ties go to the vertical
     * axis. The minor axis rides a float accumulator started half a square in, so the walk follows
     * the line a zombie would actually take rather than a staircase through the corners.
     *
     * <p>Running out of steps counts as clear, not as blocked. The budget is a cost ceiling: past
     * ten squares the population stops paying to find out and lets the horde set off, trusting the
     * next tick's walk to notice a wall once the horde is closer to it.
     */
    public boolean isLineClear(int fromX, int fromY, int toX, int toY, int stepLimit) {
        int dx = toX - fromX;
        int dy = toY - fromY;
        int x = fromX;
        int y = fromY;
        int budget = stepLimit;

        if (Math.abs(dy) < Math.abs(dx)) {
            int step = dx < 0 ? -1 : 1;
            float minor = y + 0.5F;
            float slope = (float) dy / (float) dx * step;
            while (true) {
                if (x == toX || budget < 1) {
                    return true;
                }
                int stepFromY = (int) minor;
                minor += slope;
                int stepToY = (int) minor;
                boolean blocked = isMoveBlocked(x, stepFromY, x + step, stepToY);
                x += step;
                budget--;
                if (blocked) {
                    return false;
                }
            }
        }

        int step = dy < 0 ? -1 : 1;
        float minor = x + 0.5F;
        float slope = (float) dx / (float) dy * step;
        while (true) {
            if (y == toY || budget < 1) {
                return true;
            }
            int stepFromX = (int) minor;
            minor += slope;
            int stepToX = (int) minor;
            boolean blocked = isMoveBlocked(stepFromX, y, stepToX, y + step);
            y += step;
            budget--;
            if (blocked) {
                return false;
            }
        }
    }

    /**
     * True when a player is streaming this square in. Repopulation excludes loaded areas outright;
     * that exclusion, not any distance check, is what stops zombies appearing in front of a player.
     */
    public boolean isInsideLoadedArea(int squareX, int squareY) {
        return loadedAreas.containsSquare(squareX, squareY);
    }

    /** Uniform-density sandbox mode, where the map's density image is ignored. */
    public boolean isUniformDensityMode() {
        return gameState.isUniformDistribution();
    }

    public boolean areZombiesDisabled() {
        return gameState.zombiesDisabled;
    }

    public int random(int bound) {
        return random.nextInt(bound);
    }

    public int randomRange(int min, int max) {
        return random.nextRange(min, max);
    }

    public float randomUnit() {
        return random.nextUnitFloat();
    }

    public float randomFloat(float min, float max) {
        return random.nextFloat(min, max);
    }
}
