package io.pzstorm.storm.popman;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.This;
import zombie.popman.ZombiePopulationRenderer;

/**
 * Java replacement for the five instance natives of {@code zombie.popman.ZombiePopulationRenderer}
 * — the population debug overlay. The draw list is the DLL's (0x180025f80), in its order:
 *
 * <ol>
 *   <li>loaded areas, grey;
 *   <li>active cells, white, plus a green fill that brightens as repopulation comes due and, with
 *       {@code Zombies.Standing}, every virtual zombie in red;
 *   <li>the last repopulation flood fill, blue;
 *   <li>with {@code Zombies.Moving}, every group leader in white;
 *   <li>above 0.25 zoom, the per-cell population readout;
 *   <li>the collision map's resident cells, light blue;
 *   <li>at 1.5 zoom and above, the {@code MapCollisionData.*} flag overlays over the visible
 *       squares;
 *   <li>the collision map's current path as a marching animation;
 *   <li>the wall-follower tool's own path.
 * </ol>
 *
 * <p>Vanilla calls {@code n_render} under {@code MapCollisionData.renderLock}, which is what makes
 * reading the worker's cells and touching collision cells from the render thread safe, as it was
 * natively.
 */
public final class StormZombiePopulationRenderer {

    private static final Map<String, String> DEBUG_OPTIONS = new ConcurrentHashMap<>();

    private static final int PATH_DRAW_LIMIT = 500;
    private static final int PATH_MARCH_STEP = 50;

    private static int marchOffset;

    private static final PopManPathSystem WALL_FOLLOWER =
            new PopManPathSystem(StormMapCollisionData.grid());
    private static boolean wallFollowerDirty;
    private static boolean wallFollowerMoved;
    private static int wallFollowerStartX;
    private static int wallFollowerStartY;
    private static int wallFollowerEndX;
    private static int wallFollowerEndY;

    private StormZombiePopulationRenderer() {}

    public static Map<String, String> debugOptions() {
        return DEBUG_OPTIONS;
    }

    static boolean option(String name) {
        return "true".equals(DEBUG_OPTIONS.get(name));
    }

    public static void n_render(
            @This Object self,
            @Argument(0) float zoom,
            @Argument(1) int offX,
            @Argument(2) int offY,
            @Argument(3) float xPos,
            @Argument(4) float yPos,
            @Argument(5) int drawW,
            @Argument(6) int drawH) {
        ZombiePopulationRenderer r = (ZombiePopulationRenderer) self;
        PopManCore core = StormPopMan.core();
        PopManMap map = core.map();
        PopManCellMap cells = core.cells();
        PopManGameState state = StormMapCollisionData.gameState();
        PopManCollisionGrid grid = StormMapCollisionData.grid();
        int cellSquares = PopManGeometry.SQUARES_PER_CELL;
        int chunkSquares = PopManGeometry.SQUARES_PER_CHUNK;

        if (map != null && cells != null) {
            int[] areas = map.loadedAreas().packed();
            for (int i = 0; i + 3 < areas.length; i += 4) {
                r.outlineRect(
                        areas[i] * chunkSquares,
                        areas[i + 1] * chunkSquares,
                        areas[i + 2] * chunkSquares,
                        areas[i + 3] * chunkSquares,
                        0.7F,
                        0.7F,
                        0.7F,
                        1.0F);
            }

            float age = (float) core.worldAgeHours();
            float respawnHours = core.config().respawnHours;
            boolean repopEnabled = !state.zombiesDisabled && respawnHours > 0.0F;
            boolean standing = option("Zombies.Standing");
            List<PopManCell> active = cells.active();
            for (PopManCell cell : active) {
                r.outlineRect(
                        cell.cellX * cellSquares,
                        cell.cellY * cellSquares,
                        cellSquares,
                        cellSquares,
                        1.0F,
                        1.0F,
                        1.0F,
                        0.25F);
                if (!repopEnabled) {
                    continue;
                }
                float t =
                        age < cell.lastRepopTime
                                ? 0.0F
                                : Math.min(age - cell.lastRepopTime, respawnHours) / respawnHours;
                r.renderRect(
                        cell.cellX * cellSquares + 1,
                        cell.cellY * cellSquares + 1,
                        cellSquares - 2,
                        cellSquares - 2,
                        0.0F,
                        1.0F,
                        0.0F,
                        t * t);
                if (standing) {
                    for (PopManChunk chunk : cell.chunks) {
                        for (PopManZombie zombie : chunk.zombies) {
                            r.renderZombie(zombie.x, zombie.y, 1.0F, 0.0F, 0.0F);
                        }
                    }
                }
            }

            PopManRepopulation repopulation = core.repopulation();
            if (repopulation != null) {
                PopManFloodFill fill = repopulation.window();
                for (int i = 0; i < fill.resultCount(); i++) {
                    r.renderRect(fill.resultX(i), fill.resultY(i), 1, 1, 0.0F, 0.0F, 1.0F, 0.5F);
                }
            }

            if (option("Zombies.Moving")) {
                for (PopManGroup group : core.groups()) {
                    PopManZombie leader = group.leader;
                    if (leader != null) {
                        r.renderZombie(leader.x, leader.y, 1.0F, 1.0F, 1.0F);
                    }
                }
            }

            if (zoom > 0.25F) {
                for (PopManCell cell : active) {
                    r.renderCellInfo(
                            cell.cellX,
                            cell.cellY,
                            cell.currentPopulation(),
                            PopManPopulation.desiredCellPopulation(
                                    core.config(), cell.basePopSum, age),
                            repopEnabled ? cell.lastRepopTime + respawnHours - age : -1.0F);
                }
            }
        }

        for (PopManCollisionCell cell : grid.resident()) {
            r.outlineRect(
                    cell.cellX * cellSquares,
                    cell.cellY * cellSquares,
                    cellSquares,
                    cellSquares,
                    0.0F,
                    0.5F,
                    1.0F,
                    0.25F);
        }

        if (zoom >= 1.5F && map != null) {
            int halfW = drawW / 2;
            int halfH = drawH / 2;
            int x0 = floor((float) (-halfW) / zoom + xPos);
            int x1 = floor((float) (drawW - 1 - halfW) / zoom + xPos);
            int y0 = floor((float) (-halfH) / zoom + yPos);
            int y1 = floor((float) (drawH - 1 - halfH) / zoom + yPos);
            x0 = Math.max(x0, map.minCellX() * cellSquares);
            y0 = Math.max(y0, map.minCellY() * cellSquares);
            x1 = Math.min(x1, (map.minCellX() + map.widthCells()) * cellSquares);
            y1 = Math.min(y1, (map.minCellY() + map.heightCells()) * cellSquares);

            if (option("MapCollisionData.RegularChunkOutlines")) {
                int cx0 = Math.floorDiv(x0, chunkSquares);
                int cx1 = Math.floorDiv(x1, chunkSquares);
                int cy0 = Math.floorDiv(y0, chunkSquares);
                int cy1 = Math.floorDiv(y1, chunkSquares);
                for (int cy = cy0; cy <= cy1; cy++) {
                    for (int cx = cx0; cx < cx1; cx++) {
                        if (grid.chunkState(cx, cy) == PopManCollisionCell.STATE_EXPLICIT) {
                            r.outlineRect(
                                    cx * chunkSquares,
                                    cy * chunkSquares,
                                    chunkSquares,
                                    chunkSquares,
                                    0.5F,
                                    0.0F,
                                    0.0F,
                                    1.0F);
                        }
                    }
                }
            }

            if (option("MapCollisionData.Rooms")) {
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        if ((grid.squareFlags(x, y) & PopManMap.BIT_ROOM) == 0) {
                            continue;
                        }
                        int end = x + 1;
                        while (end < x1 && (grid.squareFlags(end, y) & PopManMap.BIT_ROOM) != 0) {
                            end++;
                        }
                        r.renderRect(
                                x + 0.1F,
                                y + 0.1F,
                                (end - x) - 0.2F,
                                0.8F,
                                0.5F,
                                0.5F,
                                0.5F,
                                0.25F);
                        x = end - 1;
                    }
                }
            }

            if (option("MapCollisionData.Obstacles")) {
                float thick = zoom * 0.25F < 1.0F ? 1.0F : 0.25F;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int flags = grid.squareFlags(x, y);
                        if (flags == 0) {
                            continue;
                        }
                        int runBit;
                        float red;
                        float green;
                        float blue;
                        if ((flags & PopManMap.BIT_SOLID) != 0) {
                            runBit = PopManMap.BIT_SOLID;
                            red = 0.0F;
                            green = 0.0F;
                            blue = 1.0F;
                        } else if ((flags & PopManMap.BIT_WATER) != 0) {
                            runBit = PopManMap.BIT_WATER;
                            red = 0.0F;
                            green = 0.0F;
                            blue = 1.0F;
                        } else if ((flags & 0x20) != 0) {
                            runBit = 0x20;
                            red = 1.0F;
                            green = 1.0F;
                            blue = 0.0F;
                        } else {
                            if ((flags & PopManMap.BIT_WALL_N) != 0) {
                                r.renderRect(x + 0.1F, y, 0.8F, thick, 0.0F, 0.0F, 1.0F, 1.0F);
                            }
                            if ((flags & PopManMap.BIT_WALL_W) != 0) {
                                r.renderRect(x, y + 0.1F, thick, 0.8F, 0.0F, 0.0F, 1.0F, 1.0F);
                            }
                            continue;
                        }
                        int end = x + 1;
                        while (end < x1 && (grid.squareFlags(end, y) & runBit) != 0) {
                            end++;
                        }
                        r.renderRect(
                                x + 0.1F, y + 0.1F, (end - x) - 0.2F, 0.8F, red, green, blue, 1.0F);
                        x = end - 1;
                    }
                }
            }
        }

        float nodeSize = zoom < 1.0F ? 1.0F / zoom + 0.5F : 1.0F;
        PopManPathSystem path = StormMapCollisionData.collision().pathSystem();
        int count = path.nodeCount();
        if (count < marchOffset) {
            marchOffset = 0;
        }
        for (int i = 0; i < Math.min(PATH_DRAW_LIMIT, count); i++) {
            int index = (i + marchOffset) % count;
            if (index <= count - PATH_DRAW_LIMIT) {
                drawNode(r, path, index, nodeSize, Math.max(0.5F, (index + 1) / (float) count));
            }
        }
        marchOffset += PATH_MARCH_STEP;
        if (count < marchOffset) {
            marchOffset = 0;
        }
        for (int i = count - 1; i >= Math.max(0, count - PATH_DRAW_LIMIT); i--) {
            drawNode(r, path, i, nodeSize, Math.max(0.5F, (i + 1) / (float) count));
        }

        if (wallFollowerDirty) {
            if (!isBlocked(grid, wallFollowerStartX, wallFollowerStartY)) {
                WALL_FOLLOWER.begin(
                        wallFollowerStartX, wallFollowerStartY, wallFollowerEndX, wallFollowerEndY);
            }
            wallFollowerDirty = false;
        }
        if (wallFollowerMoved) {
            if ((WALL_FOLLOWER.currentX() == WALL_FOLLOWER.endX()
                            && WALL_FOLLOWER.currentY() == WALL_FOLLOWER.endY())
                    || WALL_FOLLOWER.isStuck()
                    || WALL_FOLLOWER.isFailed()) {
                WALL_FOLLOWER.begin(
                        wallFollowerStartX, wallFollowerStartY, wallFollowerEndX, wallFollowerEndY);
            }
            wallFollowerMoved = false;
        }
        if ((wallFollowerStartX != wallFollowerEndX || wallFollowerStartY != wallFollowerEndY)
                && !isBlocked(grid, wallFollowerStartX, wallFollowerStartY)) {
            for (int i = 0; i < 100; i++) {
                if (WALL_FOLLOWER.step() != PopManPathSystem.STATUS_WALKING) {
                    break;
                }
            }
            int nodes = WALL_FOLLOWER.nodeCount();
            for (int i = 0; i < nodes; i++) {
                drawNode(r, WALL_FOLLOWER, i, nodeSize, Math.max(0.5F, (i + 1) / (float) nodes));
            }
        }
    }

    private static void drawNode(
            Object self, PopManPathSystem path, int index, float size, float alpha) {
        ZombiePopulationRenderer r = (ZombiePopulationRenderer) self;
        float red = path.isStuck() || path.isFailed() ? 1.0F : 0.0F;
        float green = path.isStuck() ? 0.0F : 1.0F;
        r.renderRect(path.nodeX(index), path.nodeY(index), size, size, red, green, 0.0F, alpha);
    }

    private static boolean isBlocked(PopManCollisionGrid grid, int x, int y) {
        return (grid.squareFlags(x, y) & PopManMap.BLOCK_MASK) != 0;
    }

    private static int floor(float f) {
        int i = (int) f;
        return (float) i <= f ? i : i - 1;
    }

    public static void n_setWallFollowerStart(
            @This Object self, @Argument(0) int x, @Argument(1) int y) {
        wallFollowerDirty = true;
        wallFollowerStartX = x;
        wallFollowerStartY = y;
    }

    public static void n_setWallFollowerEnd(
            @This Object self, @Argument(0) int x, @Argument(1) int y) {
        wallFollowerDirty = true;
        wallFollowerEndX = x;
        wallFollowerEndY = y;
    }

    public static void n_wallFollowerMouseMove(
            @This Object self, @Argument(0) int x, @Argument(1) int y) {
        wallFollowerMoved = true;
        wallFollowerEndX = x;
        wallFollowerEndY = y;
    }

    public static void n_setDebugOption(
            @This Object self, @Argument(0) String name, @Argument(1) String value) {
        if (name != null) {
            DEBUG_OPTIONS.put(name, value == null ? "" : value);
        }
    }
}
