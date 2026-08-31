package io.pzstorm.storm.popman;

/**
 * A scanline flood fill over a fixed {@code side x side} window of world squares, collecting every
 * square reachable from a start point. The population uses two sizes: 40 squares for repopulation,
 * 8 squares (one chunk) for gathering a redistribution group.
 *
 * <p>The window is a hard clip — nothing outside it is ever visited, so the fill costs the same
 * whatever the map looks like. Results are collected in traversal order and later drawn from
 * uniformly, so that order is part of the behaviour being reproduced.
 *
 * <p>Instances are stateful and are reused between fills; the native kept one static instance per
 * size, and neither is safe to use from two threads.
 */
public class PopManFloodFill {

    /** Empties as {@code -1}; the native tests the low half-word for the sentinel. */
    private static final int EMPTY = -1;

    private final int side;
    private final int capacity;
    private final long[] visited;
    private final int[] stack;
    private final int[] results;

    private int originX;
    private int originY;
    private int stackCount;
    private int resultCount;

    public PopManFloodFill(int side) {
        this.side = side;
        this.capacity = side * side;
        this.visited = new long[(capacity + 63) / 64];
        this.stack = new int[capacity];
        this.results = new int[capacity];
    }

    public int side() {
        return side;
    }

    public int originX() {
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int resultCount() {
        return resultCount;
    }

    public int resultX(int index) {
        return (short) results[index] + originX;
    }

    public int resultY(int index) {
        return (short) (results[index] >> 16) + originY;
    }

    public boolean isVisited(int squareX, int squareY) {
        int localX = squareX - originX;
        int localY = squareY - originY;
        if (localX < 0 || localX >= side || localY < 0 || localY >= side) {
            return false;
        }
        int bit = localY * side + localX;
        return (visited[bit >> 6] & (1L << bit)) != 0;
    }

    /**
     * Marks a square as already handled so the fill will not cross it. Called from {@link
     * #prepare()} to fence areas off before the traversal starts.
     */
    public void markVisited(int squareX, int squareY) {
        int localX = squareX - originX;
        int localY = squareY - originY;
        if (localX < 0 || localX >= side || localY < 0 || localY >= side) {
            return;
        }
        int bit = localY * side + localX;
        visited[bit >> 6] |= 1L << bit;
    }

    /** Runs after the window is cleared and before the start square is pushed. */
    protected void prepare(PopManMap world) {}

    public void run(int startX, int startY, int originX, int originY, PopManMap world) {
        this.originX = originX;
        this.originY = originY;
        java.util.Arrays.fill(visited, 0L);
        stackCount = 0;
        resultCount = 0;

        prepare(world);
        if (stackCount >= capacity) {
            return;
        }
        push(startX, startY);

        while (true) {
            int packed = pop();
            if ((short) packed == EMPTY) {
                return;
            }
            int x = (short) packed + originX;
            int y = (short) (packed >> 16) + originY;

            while (canPass(world, x, y, x, y - 1)) {
                y--;
            }

            boolean spanLeft = false;
            boolean spanRight = false;
            boolean more;
            do {
                if (resultCount >= capacity) {
                    return;
                }
                results[resultCount++] = pack(x, y);
                markVisited(x, y);

                boolean left = canPass(world, x, y, x - 1, y);
                if (!spanLeft && left) {
                    if (stackCount >= capacity) {
                        return;
                    }
                    spanLeft = true;
                    push(x - 1, y);
                } else if (spanLeft && !left) {
                    spanLeft = false;
                } else if (spanLeft && !canPass(world, x - 1, y, x - 1, y - 1)) {
                    if (stackCount >= capacity) {
                        return;
                    }
                    push(x - 1, y);
                }

                boolean right = canPass(world, x, y, x + 1, y);
                if (!spanRight && right) {
                    if (stackCount >= capacity) {
                        return;
                    }
                    spanRight = true;
                    push(x + 1, y);
                } else if (spanRight && !right) {
                    spanRight = false;
                } else if (spanRight && !canPass(world, x + 1, y, x + 1, y - 1)) {
                    if (stackCount >= capacity) {
                        return;
                    }
                    push(x + 1, y);
                }

                more = canPass(world, x, y, x, y + 1);
                y++;
            } while (more);
        }
    }

    private boolean canPass(PopManMap world, int fromX, int fromY, int toX, int toY) {
        return toX >= originX
                && toX < originX + side
                && toY >= originY
                && toY < originY + side
                && !isVisited(toX, toY)
                && !world.isMoveBlocked(fromX, fromY, toX, toY);
    }

    private int pack(int squareX, int squareY) {
        return ((squareY - originY) << 16) | ((squareX - originX) & 0xFFFF);
    }

    private void push(int squareX, int squareY) {
        stack[stackCount++] = pack(squareX, squareY);
    }

    private int pop() {
        return stackCount == 0 ? EMPTY : stack[--stackCount];
    }
}
