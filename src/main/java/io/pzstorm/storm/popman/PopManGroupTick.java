package io.pzstorm.storm.popman;

import java.util.List;
import java.util.function.Supplier;

/**
 * Moves the travelling hordes, one group per tick, and ends them — by handing them to the Java
 * world when they reach loaded ground, or by settling them back into a chunk when they give up.
 *
 * <p>A group is the only thing in the population that owns zombies outside a chunk, so every exit
 * from this class has to put them somewhere. Losing that accounting loses zombies permanently.
 */
public final class PopManGroupTick {

    /** Squares per tick before the time multiplier — a horde crosses a tile in about 17 ticks. */
    public static final float STEP_SQUARES = 0.06F;

    /** Below this a direction vector is treated as no direction at all rather than normalised. */
    private static final float MIN_VECTOR_LENGTH = 0.001F;

    private static final int PRIORITY_Y = 1;
    private static final int PRIORITY_X = 2;

    /** How far ahead the horde checks its line before setting off. */
    public static final int PATH_STEP_BUDGET = 10;

    /** Manhattan squares; an aggro target this close stops a horde settling down. */
    public static final int AGGRO_VETO_RANGE = 300;

    private final PopManConfig config;
    private final PopManMap world;
    private final PopManPathSystem lastStandPath;
    private final PopManCellMap cells;
    private final List<PopManGroup> groups;
    private final Supplier<PopManResultFrame> results;

    /** The game's speed multiplier, which scales every horde's step. */
    private float speedMultiplier = 1.0F;

    private boolean blockedAny;
    private boolean blockedNegX;
    private boolean blockedPosX;
    private boolean blockedNegY;
    private boolean blockedPosY;

    /**
     * Its own fill: the group being ended is not the group being formed, and sharing one instance
     * with {@link PopManGrouping} would corrupt both the day a dissolve happens inside a recruit.
     */
    private final PopManFloodFill scatterFill = new PopManFloodFill(8);

    public PopManGroupTick(
            PopManConfig config,
            PopManMap world,
            PopManCellMap cells,
            List<PopManGroup> groups,
            Supplier<PopManResultFrame> results) {
        this.config = config;
        this.world = world;
        this.lastStandPath = new PopManPathSystem(world);
        this.cells = cells;
        this.groups = groups;
        this.results = results;
    }

    public void setSpeedMultiplier(float speedMultiplier) {
        this.speedMultiplier = speedMultiplier;
    }

    /**
     * Walks every group once. A group that ends removes itself from the list mid-iteration, so the
     * index has to come back from the tick rather than being advanced blindly.
     */
    public void tickAll(List<PopManInputFrame.AggroTarget> aggroTargets, long nowMs) {
        for (int i = 0; i < groups.size(); i = tick(groups.get(i), i, aggroTargets, nowMs) + 1) {
            // the tick returns the index it consumed
        }
    }

    /** Returns the index handled, or one less when the group was removed. */
    int tick(PopManGroup group, int index, List<PopManInputFrame.AggroTarget> aggro, long nowMs) {
        if (group.cell == null) {
            rehome(group);
        }
        if (group.cell != null && isReadyToRealise(group, false)) {
            realise(group, true);
            return index - 1;
        }

        boolean stuck = steer(group);
        if (!stuck) {
            stuck = resolveMove(group);
        }

        rehome(group);
        if (group.cell == null) {
            return index;
        }
        group.cell.lastTouchedMs = nowMs;

        int tileX = group.leaderSquareX();
        int tileY = group.leaderSquareY();

        if (stuck) {
            if (isReadyToRealise(group, true)) {
                realise(group, false);
                return index - 1;
            }
        } else if (!spendTravelBudget(group, tileX, tileY)) {
            return index;
        }

        if (nearestAggroWithin(aggro, tileX, tileY) != null) {
            return index;
        }
        dissolve(group, tileX, tileY, nowMs);
        return index - 1;
    }

    /**
     * A horde strictly inside the loaded region becomes real zombies at once, because a player can
     * see the ground it is standing on. A horde on the outermost ring is left virtual for another
     * tick — that ring is where groups walking towards or away from the player pile up, and
     * realising all of them would line the edge of the loaded box with zombies.
     *
     * <p>The exception is a horde that is stuck there. It would otherwise stand on the ring
     * forever, so being stuck flips the test: on the ring it realises, and anywhere else it settles
     * back into a chunk.
     */
    private boolean isReadyToRealise(PopManGroup group, boolean stuck) {
        int tileX = group.leaderSquareX();
        int tileY = group.leaderSquareY();
        if (!group.cell.isChunkStreamedIn(
                PopManGeometry.chunkOfSquare(tileX), PopManGeometry.chunkOfSquare(tileY))) {
            return false;
        }
        return world.isOnLoadedPerimeter(tileX, tileY) == stuck;
    }

    /**
     * Points the leader at its path target and records the displacement it wants. Returns true when
     * no step was even asked for — either the horde has arrived, or it cannot see a way through —
     * which the caller treats the same as having been stopped by a wall.
     *
     * <p>Last Stand ({@code Core.bLastStand}) swaps the beeline for the wall-following path system
     * (native 0x180014f90): the whole path is walked at once, the leader's own square is located in
     * the node list and the group is pushed toward the node after it. Anything that stops that —
     * the leader standing on a blocked square, no path, or fewer than two nodes past the leader —
     * falls back to the beeline, which is the only mode that runs the line-of-sight check.
     */
    boolean steer(PopManGroup group) {
        PopManZombie leader = group.leader;
        group.workX = leader.x;
        group.workY = leader.y;
        group.tickStartX = leader.x;
        group.tickStartY = leader.y;
        group.pushX = 0.0F;
        group.pushY = 0.0F;
        clearBlockFlags();

        int tileX = group.leaderSquareX();
        int tileY = group.leaderSquareY();
        if (!leader.hasPathTarget()
                || (tileX == leader.pathTargetX && tileY == leader.pathTargetY)) {
            return true;
        }
        if (world.gameState().lastStand && !world.isSquareBlocked(tileX, tileY)) {
            lastStandPath.begin(tileX, tileY, leader.pathTargetX, leader.pathTargetY);
            int status;
            do {
                status = lastStandPath.step();
            } while (status == PopManPathSystem.STATUS_WALKING);
            if (status == PopManPathSystem.STATUS_FOUND) {
                int count = lastStandPath.nodeCount();
                int found = 0;
                for (int i = 1; i < count; i++) {
                    if (lastStandPath.nodeX(i) == tileX && lastStandPath.nodeY(i) == tileY) {
                        found = i;
                        break;
                    }
                }
                if (count - found >= 2) {
                    return push(
                            group,
                            lastStandPath.nodeX(found + 1) + 0.5F - leader.x,
                            lastStandPath.nodeY(found + 1) + 0.5F - leader.y);
                }
            }
        }
        if (!world.isLineClear(
                tileX, tileY, leader.pathTargetX, leader.pathTargetY, PATH_STEP_BUDGET)) {
            return true;
        }
        return push(
                group, leader.pathTargetX + 0.5F - leader.x, leader.pathTargetY + 0.5F - leader.y);
    }

    private boolean push(PopManGroup group, float dx, float dy) {
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length < MIN_VECTOR_LENGTH) {
            return false;
        }
        group.pushX = dx / length * STEP_SQUARES * speedMultiplier;
        group.pushY = dy / length * STEP_SQUARES * speedMultiplier;
        return false;
    }

    /**
     * Carries out the step steering asked for and reports whether anything got in the way.
     *
     * <p>The move is tried twice from the same starting state, once with each axis given priority,
     * and the attempt that ends up further from where the horde started is the one that is kept. A
     * single ordering makes a horde stick on inside corners: the axis that is tested first is
     * reverted first, and against the wrong wall that throws away the component that would have
     * freed it. The second attempt only runs when the first was actually obstructed, and it wins
     * ties, so an unobstructed step costs one pass.
     */
    boolean resolveMove(PopManGroup group) {
        group.workY += group.pushY;
        group.workX += group.pushX;

        float minX = (float) (world.minCellX() * PopManGeometry.SQUARES_PER_CELL);
        if (minX > group.workX) {
            group.workX = minX;
        }
        float minY = (float) (world.minCellY() * PopManGeometry.SQUARES_PER_CELL);
        if (minY > group.workY) {
            group.workY = minY;
        }
        capToOneSquare(group);

        float candidateX = group.workX;
        float candidateY = group.workY;
        group.pushX = 0.0F;
        group.pushY = 0.0F;
        clearBlockFlags();

        boolean yFirst = group.resolveYFirst;
        int primary = yFirst ? PRIORITY_Y : PRIORITY_X;
        int secondary = yFirst ? PRIORITY_X : PRIORITY_Y;

        collide(group, primary);
        revertBlockedAxes(group);
        collide(group, secondary);
        group.resolveYFirst = !yFirst;
        boolean obstructed = revertBlockedAxes(group);

        float firstX = group.workX;
        float firstY = group.workY;
        float firstReach = reach(group, firstX, firstY);
        group.workX = candidateX;
        group.workY = candidateY;

        if (obstructed) {
            collide(group, secondary);
            revertBlockedAxes(group);
            collide(group, primary);
            revertBlockedAxes(group);
            if (firstReach > reach(group, group.workX, group.workY)) {
                group.workX = firstX;
                group.workY = firstY;
            }
        }

        group.leader.x = group.workX;
        group.leader.y = group.workY;
        group.blocked = blockedAny;
        return blockedAny;
    }

    /**
     * No horde may cross more than one square in a tick, however fast the game is running — the
     * collision test only ever looks at the square being entered, so a longer step would jump
     * walls.
     */
    private void capToOneSquare(PopManGroup group) {
        float leaderX = group.leader.x;
        float leaderY = group.leader.y;
        float dx = group.workX - leaderX;
        float dy = group.workY - leaderY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        if (length <= 1.0F) {
            return;
        }
        float unitX = 0.0F;
        float unitY = 0.0F;
        if (length >= MIN_VECTOR_LENGTH) {
            unitX = dx / length;
            unitY = dy / length;
        }
        group.workX = leaderX + unitX;
        group.workY = leaderY + unitY;
    }

    private static float reach(PopManGroup group, float x, float y) {
        return Math.abs(x - group.tickStartX) + Math.abs(y - group.tickStartY);
    }

    private boolean revertBlockedAxes(PopManGroup group) {
        boolean reverted = false;
        if (blockedNegY || blockedPosY) {
            group.workY = group.tickStartY;
            reverted = true;
        }
        if (blockedNegX || blockedPosX) {
            group.workX = group.tickStartX;
            reverted = true;
        }
        return reverted;
    }

    private void clearBlockFlags() {
        blockedAny = false;
        blockedNegX = false;
        blockedPosX = false;
        blockedNegY = false;
        blockedPosY = false;
    }

    /**
     * Tests the whole two-dimensional step and records which directions were refused. Both passes
     * test the same move; {@code priority} decides only which axis's refusal is reported when both
     * are refused, and so which one the caller reverts.
     *
     * <p>The direction flags are deliberately not cleared between calls. A refusal found by the
     * first test still counts against the second, which is what stops a horde alternating between
     * two impossible steps.
     */
    boolean collide(PopManGroup group, int priority) {
        float startX = group.tickStartX;
        float startY = group.tickStartY;
        float workX = group.workX;
        float workY = group.workY;
        int startTileX = (int) Math.floor(startX);
        int startTileY = (int) Math.floor(startY);
        int workTileX = (int) Math.floor(workX);
        int workTileY = (int) Math.floor(workY);

        boolean outside = world.isOutsideWorld(workTileX, workTileY);
        int fromX;
        int fromY;
        int toX;
        int toY;

        if (outside || (workTileX == startTileX && workTileY == startTileY)) {
            if (workX == startX && workY == startY) {
                return false;
            }
            if (outside) {
                blockedNegX |= startX > workX;
                blockedPosX |= workX > startX;
                blockedNegY |= startY > workY;
                blockedPosY |= workY > startY;
                group.workX = startX;
                group.workY = startY;
                blockedAny = true;
                return true;
            }
            int[] probe = halfSquareProbe(group, workX - startX, workY - startY);
            if (world.isOutsideWorld(probe[0], probe[1])
                    || (probe[0] == workTileX && probe[1] == workTileY)
                    || !world.isMoveBlocked(workTileX, workTileY, probe[0], probe[1])) {
                return false;
            }
            fromX = workTileX;
            fromY = workTileY;
            toX = probe[0];
            toY = probe[1];
        } else {
            if (!world.isMoveBlocked(startTileX, startTileY, workTileX, workTileY)) {
                return false;
            }
            fromX = startTileX;
            fromY = startTileY;
            toX = workTileX;
            toY = workTileY;
        }

        if (fromX < toX) {
            blockedPosX = true;
        } else if (fromX > toX) {
            blockedNegX = true;
        }
        if (fromY < toY) {
            blockedPosY = true;
        } else if (fromY > toY) {
            blockedNegY = true;
        }

        if (priority == PRIORITY_X) {
            if (blockedNegX || blockedPosX) {
                blockedNegY = false;
                blockedPosY = false;
            }
        } else if (blockedNegY || blockedPosY) {
            blockedNegX = false;
            blockedPosX = false;
        }
        blockedAny = true;
        return true;
    }

    /**
     * Where the horde would stand half a square further on. A step too small to leave its square
     * cannot be tested as a move, so it is tested as a look ahead instead — otherwise a horde
     * creeping towards a wall would only discover it on the tick it was already inside.
     */
    private int[] halfSquareProbe(PopManGroup group, float dx, float dy) {
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float unitX = 0.0F;
        float unitY = 0.0F;
        if (length >= MIN_VECTOR_LENGTH) {
            unitX = dx / length;
            unitY = dy / length;
        }
        return new int[] {
            (int) Math.floor(unitX * 0.5F + group.leader.x),
            (int) Math.floor(unitY * 0.5F + group.leader.y)
        };
    }

    /**
     * Counts a square of progress towards the followed sound and reports whether the horde has
     * spent its whole budget. {@code FollowSoundDistance} is measured in squares actually entered,
     * not in distance from the noise, so a horde grinding against a wall never gives up.
     *
     * <p>Nothing resets the counter here: a horde over budget but held together by nearby aggro
     * re-tests on every square it walks after that.
     */
    private boolean spendTravelBudget(PopManGroup group, int tileX, int tileY) {
        if (group.followedSound == null) {
            return false;
        }
        if ((int) Math.floor(group.tickStartX) == tileX
                && (int) Math.floor(group.tickStartY) == tileY) {
            return false;
        }
        group.squaresTravelled++;
        return group.squaresTravelled >= config.followSoundDistance;
    }

    /**
     * An empty aggro list permits the horde to settle; only a target actually within range holds it
     * together.
     */
    PopManInputFrame.AggroTarget nearestAggroWithin(
            List<PopManInputFrame.AggroTarget> aggroTargets, int tileX, int tileY) {
        PopManInputFrame.AggroTarget nearest = null;
        int best = AGGRO_VETO_RANGE;
        for (PopManInputFrame.AggroTarget target : aggroTargets) {
            int distance = Math.abs(target.x() - tileX) + Math.abs(target.y() - tileY);
            if (distance < best) {
                best = distance;
                nearest = target;
            }
        }
        return nearest;
    }

    /**
     * Hands the horde to the Java world, which will spawn and dress them as real zombies.
     *
     * <p>Only a horde that arrived under its own steam passes its leader's destination down to the
     * members, so they keep walking the same way after the handover. One realised because it was
     * stuck on the loaded edge is going nowhere, and its members are released still carrying
     * whatever target they were recruited with.
     */
    void realise(PopManGroup group, boolean inheritLeaderTarget) {
        if (group.members.size() < 2) {
            results.get().spawns.add(group.leader);
        } else {
            scatterMembers(group);
            if (inheritLeaderTarget) {
                for (PopManZombie member : group.members) {
                    member.pathTargetX = group.leader.pathTargetX;
                    member.pathTargetY = group.leader.pathTargetY;
                }
            }
            results.get().spawns.addAll(group.members);
        }
        detach(group);
    }

    /**
     * Turns the horde back into ordinary chunk residents. Every member joins the chunk the
     * <em>leader</em> stands in, whatever square the scatter gave it, so the chunk's resident list
     * and the zombies' own coordinates can disagree by up to a chunk.
     */
    void dissolve(PopManGroup group, int tileX, int tileY, long nowMs) {
        PopManCell cell = cells.residentForSquare(tileX, tileY);
        if (cell != null) {
            cell.lastTouchedMs = nowMs;
            PopManChunk chunk = cell.chunkAtSquare(tileX, tileY);
            if (group.members.size() < 2) {
                chunk.zombies.add(group.leader);
                cell.virtualCount++;
            } else {
                scatterMembers(group);
                chunk.zombies.addAll(group.members);
                cell.virtualCount += (short) group.members.size();
            }
            cell.dirty = true;
        }
        detach(group);
    }

    /**
     * Puts the group in the cell its leader currently stands in. A group crossing a cell boundary
     * moves between the two cells' lists and nothing else — the population counters are untouched,
     * because a cell counts the groups passing through it separately from its resident zombies.
     *
     * <p>Leaving the map, or walking into a cell nobody has loaded, leaves the group cell-less
     * rather than loading one.
     */
    public void rehome(PopManGroup group) {
        rehome(cells, group);
    }

    /**
     * @see #rehome(PopManGroup)
     */
    static void rehome(PopManCellMap cells, PopManGroup group) {
        PopManCell home = cells.residentForSquare(group.leaderSquareX(), group.leaderSquareY());
        if (home == group.cell) {
            return;
        }
        if (group.cell != null) {
            group.cell.groups.remove(group);
            group.cell.dirty = true;
        }
        group.cell = home;
        if (home != null) {
            home.groups.add(group);
            home.dirty = true;
        }
    }

    /**
     * Spreads the members over whatever is reachable inside the leader's own chunk, which is what
     * turns one walking marker back into a crowd standing in a room.
     *
     * <p>Squares are drawn with replacement, so members can and do land on top of each other, and
     * only x and y are written — facing, outfit and state all survive the journey.
     *
     * <p>The chunk window is found by truncating towards zero rather than flooring, so west or
     * north of the origin the window is the neighbouring chunk rather than the leader's own — the
     * group scatters over ground it is not standing on. When that misalignment puts the leader
     * exactly one square west of the window, the fill mistakes the start square for its own
     * end-of-stack marker and finds nothing at all, and every member is placed on whichever square
     * the previous scatter happened to record first. Both are faults of the original; they are kept
     * because the population that grew up around them is the one players know.
     */
    public void scatterMembers(PopManGroup group) {
        int seedX = group.leaderSquareX();
        int seedY = group.leaderSquareY();
        int originX = seedX / PopManGeometry.SQUARES_PER_CHUNK * PopManGeometry.SQUARES_PER_CHUNK;
        int originY = seedY / PopManGeometry.SQUARES_PER_CHUNK * PopManGeometry.SQUARES_PER_CHUNK;

        scatterFill.run(seedX, seedY, originX, originY, world);
        for (PopManZombie member : group.members) {
            int drawn = world.random(scatterFill.resultCount());
            member.x = scatterFill.resultX(drawn) + 0.5F;
            member.y = scatterFill.resultY(drawn) + 0.5F;
        }
    }

    /** Takes the group out of the world's two lists. Callers place the members first. */
    void detach(PopManGroup group) {
        if (group.cell != null) {
            group.cell.groups.remove(group);
            group.cell.dirty = true;
        }
        group.cell = null;
        groups.remove(group);
    }
}
