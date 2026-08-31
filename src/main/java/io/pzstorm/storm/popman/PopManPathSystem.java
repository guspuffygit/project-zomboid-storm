package io.pzstorm.storm.popman;

/**
 * The DLL's wall-following path system, transcribed from {@code PZPopMan64} ({@code begin}
 * 0x180016b80, {@code step} 0x180016d90, {@code straightStep} 0x180016830, {@code wallStep}
 * 0x180016f80, {@code onLine} 0x180016c50). Three instances existed natively: one inside {@code
 * MapCollisionData} serving {@code n_pathTask}, one inside the population manager for Last Stand
 * steering, and one inside the debug renderer for the wall-follower tool.
 *
 * <p>The algorithm is a bug: it walks a Bresenham-like straight line toward the end, and when the
 * next square is blocked remembers the hit point and follows the obstacle's wall until it is back
 * on the original line past the hit point. {@code step} returns 0 while walking, 1 when stuck or
 * over budget, 2 when it gave up (walked back onto its own start, or revisited the hit point after
 * a lap) and 3 on arrival. The {@code stuck} flag exists but nothing native ever sets it, so it is
 * only ever false; it is kept because the renderer colours nodes by it.
 *
 * <p>Squares are asked through {@link Terrain}: the raw flag byte and the DLL's move test with the
 * block mask 0x19 (solid, water, room). Both are exactly {@link PopManMap#squareFlags} and {@link
 * PopManMap#isMoveBlocked}.
 */
public final class PopManPathSystem {

    public interface Terrain {
        int squareFlags(int squareX, int squareY);

        boolean isMoveBlocked(int fromX, int fromY, int toX, int toY);
    }

    public static final int STATUS_WALKING = 0;
    public static final int STATUS_STUCK = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_FOUND = 3;

    /** Node capacity; a path this long is reported as stuck rather than grown. */
    public static final int MAX_NODES = 90000;

    private static final int NONE = Integer.MIN_VALUE;

    private final Terrain terrain;
    private final int[] nodes = new int[MAX_NODES * 2];

    private int startX;
    private int startY;
    private int endX;
    private int endY;
    private int hitX = NONE;
    private int hitY = NONE;
    private int lapCount;
    private int hitRevisits;
    private int nextX;
    private int nextY;
    private int curX;
    private int curY;
    private boolean stuck;
    private boolean failed;
    private int count;

    public PopManPathSystem(Terrain terrain) {
        this.terrain = terrain;
    }

    /** Resets the walk; the lap counters and the wall-follow direction deliberately carry over. */
    public void begin(int startX, int startY, int endX, int endY) {
        this.startX = startX;
        this.startY = startY;
        this.endX = endX;
        this.endY = endY;
        this.hitX = NONE;
        this.hitY = NONE;
        this.curX = startX;
        this.curY = startY;
        this.stuck = false;
        this.failed = false;
        this.count = 0;
    }

    public int step() {
        if (stuck) {
            return STATUS_STUCK;
        }
        if (failed) {
            return STATUS_FAILED;
        }
        if (isBlocked(curX, curY)) {
            return STATUS_STUCK;
        }
        if (curX == endX && curY == endY) {
            return STATUS_FOUND;
        }
        int before = count;
        if (before > MAX_NODES - 1) {
            return STATUS_STUCK;
        }
        if (hitX == NONE) {
            straightStep();
            if (count > 0 && curX == startX && curY == startY) {
                failed = true;
                return STATUS_FAILED;
            }
        } else {
            wallStep();
            if (before < count) {
                lapCount++;
            }
            if ((curX != hitX || curY != hitY) && onLine(curX, curY)) {
                hitX = NONE;
                hitY = NONE;
            }
            if (lapCount > 0 && curX == hitX && curY == hitY) {
                hitRevisits++;
                if (hitRevisits > 1) {
                    failed = true;
                    return STATUS_FAILED;
                }
            }
        }
        if (!isBlocked(curX, curY)
                && (!stepBlocked(curX, curY, curX - 1, curY)
                        || !stepBlocked(curX, curY, curX, curY - 1)
                        || !stepBlocked(curX, curY, curX + 1, curY)
                        || !stepBlocked(curX, curY, curX, curY + 1))) {
            if (curX == endX && curY == endY) {
                return STATUS_FOUND;
            }
            return count > MAX_NODES - 1 ? STATUS_STUCK : STATUS_WALKING;
        }
        return STATUS_STUCK;
    }

    private void straightStep() {
        int x = curX;
        int y = curY;
        int dx = endX - x;
        int dy = endY - y;
        int nx;
        int ny;
        if (Math.abs(dy) < Math.abs(dx)) {
            int sx = dx < 0 ? -1 : 1;
            if (x == endX) {
                return;
            }
            nx = x + sx;
            ny = floor(((float) dy / (float) dx) * (float) sx + (float) y + 0.5F);
        } else {
            int sy = dy < 0 ? -1 : 1;
            if (y == endY) {
                return;
            }
            ny = y + sy;
            nx = floor(((float) dx / (float) dy) * (float) sy + (float) x + 0.5F);
        }
        if (!stepBlocked(x, y, nx, ny)) {
            push(x, y);
            curX = nx;
            curY = ny;
            return;
        }
        lapCount = 0;
        hitRevisits = 0;
        hitX = x;
        hitY = y;
        nextX = nx;
        nextY = ny;
        int ddx = nx - x;
        int ddy = ny - y;
        if (nx == x || ny == y) {
            return;
        }
        if (ddx == 1) {
            if (ddy == -1 && !stepBlocked(x + 1, y, nx, ny)) {
                nextX--;
            } else if (ddy == 1 && !stepBlocked(x, y + 1, nx, ny)) {
                nextY--;
            }
        } else if (ddx == -1) {
            if (ddy == -1 && !stepBlocked(x, y - 1, nx, ny)) {
                nextY++;
            } else if (ddy == 1 && !stepBlocked(x - 1, y, nx, ny)) {
                nextX++;
            }
        }
    }

    private void wallStep() {
        int ddx = nextX - curX;
        int ddy = nextY - curY;
        int x = curX;
        int y = curY;
        if (ddx == 1) {
            if (ddy == -1) {
                if (stepBlocked(x, y, x + 1, y)) {
                    nextX = x + 1;
                    nextY = y;
                    return;
                }
                push(x, y);
                curX = x + 1;
                curY = y;
            } else if (ddy == 1) {
                if (stepBlocked(x, y, x, y + 1)) {
                    nextX = x;
                    nextY = y + 1;
                    return;
                }
                push(x, y);
                curX = x;
                curY = y + 1;
            } else if (ddy == 0) {
                if (stepBlocked(x, y, x, y + 1)) {
                    nextX = x;
                    nextY = y + 1;
                    return;
                }
                if (isWallW(x + 1, y) && !stepBlocked(x, y + 1, x + 1, y + 1)) {
                    nextX = x;
                    nextY = y;
                }
                push(x, y);
                curX = x;
                curY = y + 1;
            }
        } else if (ddx == 0) {
            if (ddy == 1) {
                if (stepBlocked(x, y, x - 1, y)) {
                    nextX = x - 1;
                    nextY = y;
                    return;
                }
                if (isWallN(x, y + 1) && !stepBlocked(x - 1, y, x - 1, y + 1)) {
                    nextX = x;
                    nextY = y;
                }
                push(x, y);
                curX = x - 1;
                curY = y;
            } else if (ddy == -1) {
                if (stepBlocked(x, y, x + 1, y)) {
                    nextX = x + 1;
                    nextY = y;
                    return;
                }
                if (isWallN(x, y) && !stepBlocked(x + 1, y, x + 1, y - 1)) {
                    nextX = x;
                    nextY = y;
                }
                push(x, y);
                curX = x + 1;
                curY = y;
            }
        } else if (ddx == -1) {
            if (ddy == 1) {
                if (stepBlocked(x, y, x - 1, y)) {
                    nextX = x - 1;
                    nextY = y;
                    return;
                }
                push(x, y);
                curX = x - 1;
                curY = y;
            } else if (ddy == -1) {
                if (stepBlocked(x, y, x, y - 1)) {
                    nextX = x;
                    nextY = y - 1;
                    return;
                }
                push(x, y);
                curX = x;
                curY = y - 1;
            } else if (ddy == 0) {
                if (stepBlocked(x, y, x, y - 1)) {
                    nextX = x;
                    nextY = y - 1;
                    return;
                }
                if (isWallW(x, y) && !stepBlocked(x, y - 1, x - 1, y - 1)) {
                    nextX = x;
                    nextY = y;
                }
                push(x, y);
                curX = x;
                curY = y - 1;
            }
        }
    }

    private boolean onLine(int cx, int cy) {
        int dx = endX - hitX;
        int dy = endY - hitY;
        if (Math.abs(dy) < Math.abs(dx)) {
            int sx = dx < 0 ? -1 : 1;
            float fy = (float) hitY + 0.5F;
            int x = hitX;
            while (true) {
                if (x == endX) {
                    return false;
                }
                x += sx;
                fy += ((float) dy / (float) dx) * (float) sx;
                if (x == cx && floor(fy) == cy) {
                    return true;
                }
            }
        }
        int sy = dy < 0 ? -1 : 1;
        float fx = (float) hitX + 0.5F;
        int y = hitY;
        if (y == endY) {
            return false;
        }
        while (true) {
            fx += ((float) dx / (float) dy) * (float) sy;
            y += sy;
            if (floor(fx) == cx && y == cy) {
                return true;
            }
            if (y == endY) {
                return false;
            }
        }
    }

    private static int floor(float f) {
        int i = (int) f;
        return (float) i <= f ? i : i - 1;
    }

    private boolean isBlocked(int x, int y) {
        return (terrain.squareFlags(x, y) & PopManMap.BLOCK_MASK) != 0;
    }

    private boolean stepBlocked(int fromX, int fromY, int toX, int toY) {
        return terrain.isMoveBlocked(fromX, fromY, toX, toY);
    }

    private boolean isWallN(int x, int y) {
        return (terrain.squareFlags(x, y) & PopManMap.BIT_WALL_N) != 0;
    }

    private boolean isWallW(int x, int y) {
        return (terrain.squareFlags(x, y) & PopManMap.BIT_WALL_W) != 0;
    }

    private void push(int x, int y) {
        if (count < MAX_NODES) {
            nodes[count * 2] = x;
            nodes[count * 2 + 1] = y;
            count++;
        }
    }

    public int startX() {
        return startX;
    }

    public int startY() {
        return startY;
    }

    public int endX() {
        return endX;
    }

    public int endY() {
        return endY;
    }

    public int currentX() {
        return curX;
    }

    public int currentY() {
        return curY;
    }

    public boolean isStuck() {
        return stuck;
    }

    public boolean isFailed() {
        return failed;
    }

    public int nodeCount() {
        return count;
    }

    public int nodeX(int index) {
        return nodes[index * 2];
    }

    public int nodeY(int index) {
        return nodes[index * 2 + 1];
    }
}
