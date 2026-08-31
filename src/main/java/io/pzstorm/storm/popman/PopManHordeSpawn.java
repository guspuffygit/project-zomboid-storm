package io.pzstorm.storm.popman;

import java.util.function.Consumer;

/**
 * Turns one {@code n_spawnHorde} request into zombies — native {@code FUN_18000d0e0} and the three
 * functions under it. See {@code docs/re-popman/07-horde-creation.md}.
 *
 * <p>The name is vanilla's, not a description: a request for {@code count} zombies produces {@code
 * count} <em>single-member</em> groups, so nothing here ever builds a horde. Each one paths to the
 * same target and they arrive together, which is what makes it look like one.
 *
 * <p>Which of the two placement strategies runs is decided by {@code spawnW}/{@code spawnH} being
 * non-zero, but the area path then demands they be at least 1. A request with a negative or
 * one-sided extent therefore spawns nothing at all, silently.
 */
final class PopManHordeSpawn {

    /** {@code DAT_1801251a0} — the search box edge, and the ring limit, in squares. */
    static final int REGION = 40;

    static final int AREA_TRIES = 100;

    private final PopManMap map;
    private final Consumer<PopManGroup> sink;

    PopManHordeSpawn(PopManMap map, Consumer<PopManGroup> sink) {
        this.map = map;
        this.sink = sink;
    }

    void spawn(PopManInputFrame.HordeRequest request) {
        int[] remaining = {request.count()};
        if (request.spawnW() != 0 || request.spawnH() != 0) {
            spawnInArea(request, remaining);
        } else {
            spawnAtPoint(request, remaining);
        }
    }

    /**
     * The whole request rides on one collision test. If the requested tile is unspawnable the
     * request is dropped; if it passes, every other tile in the box is accepted on geometry alone,
     * so the zombies that do not fit on the origin land in walls, water and rooms up to 39 squares
     * away. Native runs a flood fill here whose predicate never consults the map, leaving the mask
     * covering the whole box — the test below is what that fill reduces to.
     */
    private void spawnAtPoint(PopManInputFrame.HordeRequest request, int[] remaining) {
        int originX = request.spawnX();
        int originY = request.spawnY();
        if (!map.isValidSpawnSquare(originX, originY, 0)) {
            return;
        }

        int halfChunks = REGION / PopManGeometry.SQUARES_PER_CHUNK / 2;
        int minX =
                clampBoxOrigin(
                        (PopManGeometry.chunkOfSquare(originX) - halfChunks)
                                * PopManGeometry.SQUARES_PER_CHUNK,
                        map.minCellX(),
                        map.widthCells());
        int minY =
                clampBoxOrigin(
                        (PopManGeometry.chunkOfSquare(originY) - halfChunks)
                                * PopManGeometry.SQUARES_PER_CHUNK,
                        map.minCellY(),
                        map.heightCells());
        int maxX = minX + REGION;
        int maxY = minY + REGION;

        if (trySpawnAtTile(request, remaining, originX, originY, minX, minY, maxX, maxY)) {
            return;
        }
        for (int ring = 1; ring < REGION; ring++) {
            int top = originY - ring;
            int bottom = originY + ring;
            int left = originX - ring;
            int right = originX + ring;

            if (minY <= top) {
                for (int x = Math.max(left, minX); x < Math.min(right + 1, maxX); x++) {
                    if (trySpawnAtTile(request, remaining, x, top, minX, minY, maxX, maxY)) {
                        return;
                    }
                }
            }
            if (bottom < maxY) {
                for (int x = Math.max(left, minX); x < Math.min(right + 1, maxX); x++) {
                    if (trySpawnAtTile(request, remaining, x, bottom, minX, minY, maxX, maxY)) {
                        return;
                    }
                }
            }
            if (minX <= left) {
                for (int y = Math.max(top + 1, minY); y < Math.min(bottom, maxY); y++) {
                    if (trySpawnAtTile(request, remaining, left, y, minX, minY, maxX, maxY)) {
                        return;
                    }
                }
            }
            if (right < maxX) {
                for (int y = Math.max(top + 1, minY); y < Math.min(bottom, maxY); y++) {
                    if (trySpawnAtTile(request, remaining, right, y, minX, minY, maxX, maxY)) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Raise to the world minimum, then lower so the far edge fits — {@code min} only, in squares.
     */
    private static int clampBoxOrigin(int origin, int minCell, int cells) {
        int worldMin = minCell * PopManGeometry.SQUARES_PER_CELL;
        int worldMax = (cells + minCell) * PopManGeometry.SQUARES_PER_CELL;
        return Math.min(Math.max(origin, worldMin), worldMax - REGION);
    }

    /**
     * A tile that is inside the box takes up to four zombies, one per quarter, before the search
     * moves on. Returns whether the request is now exhausted — not whether anybody was placed,
     * which is why an accepted tile fills all four corners rather than stopping at the first.
     *
     * <p>The origin tile is offered here without a bounds check of its own, exactly as native does;
     * the clamp above is what guarantees it lands inside.
     */
    private boolean trySpawnAtTile(
            PopManInputFrame.HordeRequest request,
            int[] remaining,
            int tileX,
            int tileY,
            int minX,
            int minY,
            int maxX,
            int maxY) {
        if (tileX < minX || tileX >= maxX || tileY < minY || tileY >= maxY) {
            return false;
        }
        return spawnOneAt(request, remaining, tileX + 0.25F, tileY + 0.25F)
                || spawnOneAt(request, remaining, tileX + 0.75F, tileY + 0.25F)
                || spawnOneAt(request, remaining, tileX + 0.25F, tileY + 0.75F)
                || spawnOneAt(request, remaining, tileX + 0.75F, tileY + 0.75F);
    }

    /**
     * Unlike the point path this one collision-tests every placement, centres the zombie on its
     * tile, and gives up on a member after {@value #AREA_TRIES} draws rather than searching
     * outward. A member that never finds a square is lost; the request does not retry it.
     */
    private void spawnInArea(PopManInputFrame.HordeRequest request, int[] remaining) {
        if (request.spawnW() < 1 || request.spawnH() < 1 || remaining[0] < 1) {
            return;
        }
        int wanted = remaining[0];
        for (int i = 0; i < wanted; i++) {
            int x = 0;
            int y = 0;
            boolean placed = false;
            for (int attempt = 0; attempt < AREA_TRIES; attempt++) {
                x = request.spawnX() + map.random(request.spawnW());
                y = request.spawnY() + map.random(request.spawnH());
                if (map.isValidSpawnSquare(x, y, 0)) {
                    placed = true;
                    break;
                }
            }
            if (placed) {
                emit(request, x + 0.5F, y + 0.5F);
            }
        }
    }

    /** Always spawns; the return value reports that the request has run out, not that it fitted. */
    private boolean spawnOneAt(
            PopManInputFrame.HordeRequest request, int[] remaining, float x, float y) {
        emit(request, x, y);
        return --remaining[0] < 1;
    }

    /**
     * Native recycles groups through a pool that never clears the travel counter, so a group reused
     * after a travel-budget dissolution starts life one square from dissolving again. A fresh group
     * per spawn is the deliberate divergence.
     */
    private void emit(PopManInputFrame.HordeRequest request, float x, float y) {
        PopManZombie zombie =
                PopManZombie.spawnedAt(x, y, () -> map.random(PopManZombie.DIRECTION_COUNT));
        zombie.pathTargetX = (int) Math.floor(request.targetX());
        zombie.pathTargetY = (int) Math.floor(request.targetY());
        sink.accept(new PopManGroup(zombie));
    }
}
