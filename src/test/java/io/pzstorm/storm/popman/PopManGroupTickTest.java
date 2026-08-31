package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopManGroupTickTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManConfig config = new PopManConfig();
    private final PopManCellMap cells = new PopManCellMap(config, world, cell -> false);
    private final List<PopManGroup> groups = new ArrayList<>();
    private final PopManResultFrame results = new PopManResultFrame();
    private final PopManGroupTick tick =
            new PopManGroupTick(config, world, cells, groups, () -> results);

    PopManGroupTickTest() {
        world.setWorldBounds(-4, -4, 8, 8);
        world.blocked = true;
    }

    private PopManGroup groupAt(float x, float y, int members) {
        PopManZombie leader = new PopManZombie();
        leader.x = x;
        leader.y = y;
        PopManGroup group = new PopManGroup(leader);
        for (int i = 1; i < members; i++) {
            PopManZombie follower = new PopManZombie();
            follower.x = x;
            follower.y = y;
            group.members.add(follower);
        }
        groups.add(group);
        return group;
    }

    @Test
    void aGroupJoinsTheCellItsLeaderStandsIn() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.dirty = false;
        PopManGroup group = groupAt(10.5F, 10.5F, 1);

        tick.rehome(group);

        assertSame(cell, group.cell);
        assertTrue(cell.groups.contains(group));
        assertTrue(cell.dirty);
    }

    @Test
    void crossingACellBoundaryMovesTheGroupWithoutTouchingAnyPopulationCount() {
        PopManCell from = cells.load(0, 0, 0, 0);
        PopManCell to = cells.load(1, 0, 0, 0);
        PopManGroup group = groupAt(10.5F, 10.5F, 3);
        tick.rehome(group);
        from.virtualCount = 7;
        to.virtualCount = 4;

        group.leader.x = 300.5F;
        tick.rehome(group);

        assertSame(to, group.cell);
        assertFalse(from.groups.contains(group));
        assertTrue(to.groups.contains(group));
        assertEquals(7, from.virtualCount, "a group in transit was never in the resident count");
        assertEquals(4, to.virtualCount);
    }

    @Test
    void walkingIntoACellNobodyHasLoadedLeavesTheGroupHomeless() {
        PopManCell from = cells.load(0, 0, 0, 0);
        PopManGroup group = groupAt(10.5F, 10.5F, 1);
        tick.rehome(group);

        group.leader.x = 300.5F;
        tick.rehome(group);

        assertNull(group.cell, "the group must not conjure a whole populated cell under itself");
        assertFalse(from.groups.contains(group));
    }

    @Test
    void stayingInTheSameCellIsNotAChange() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManGroup group = groupAt(10.5F, 10.5F, 1);
        tick.rehome(group);
        cell.dirty = false;

        group.leader.x = 12.5F;
        tick.rehome(group);

        assertFalse(cell.dirty);
        assertEquals(1, cell.groups.size(), "and certainly not added twice");
    }

    @Test
    void scatteringPutsEveryMemberOnASquareCentreInsideTheLeadersChunk() {
        PopManGroup group = groupAt(10.5F, 10.5F, 4);

        tick.scatterMembers(group);

        for (PopManZombie member : group.members) {
            assertEquals(0.5F, member.x - (float) Math.floor(member.x), 0.0001F);
            assertEquals(0.5F, member.y - (float) Math.floor(member.y), 0.0001F);
            assertTrue(member.x >= 8 && member.x < 16, "x landed at " + member.x);
            assertTrue(member.y >= 8 && member.y < 16, "y landed at " + member.y);
        }
    }

    @Test
    void membersAreDrawnWithReplacementAndCanLandOnEachOther() {
        PopManGroup group = groupAt(10.5F, 10.5F, 3);
        world.roll(5, 5, 5);

        tick.scatterMembers(group);

        assertEquals(group.members.get(0).x, group.members.get(1).x);
        assertEquals(group.members.get(0).y, group.members.get(2).y);
    }

    @Test
    void scatteringMovesNothingButPosition() {
        PopManGroup group = groupAt(10.5F, 10.5F, 2);
        PopManZombie follower = group.members.get(1);
        follower.dir = 6;
        follower.descriptorID = 33;
        follower.stateFlags = 5;
        follower.pathTargetX = 99;

        tick.scatterMembers(group);

        assertEquals(6, follower.dir);
        assertEquals(33, follower.descriptorID);
        assertEquals(5, follower.stateFlags);
        assertEquals(99, follower.pathTargetX, "the path target outlives the group");
    }

    /**
     * Truncation towards zero puts the window in the wrong chunk west of the origin, and the fill
     * cannot leave a window its seed is not inside — so the whole horde piles onto one square.
     */
    @Test
    void aHordeDissolvingWestOfTheOriginPilesUpOnASingleSquare() {
        PopManGroup group = groupAt(-3.5F, -3.5F, 3);

        tick.scatterMembers(group);

        for (PopManZombie member : group.members) {
            assertEquals(-3.5F, member.x);
            assertEquals(-3.5F, member.y);
        }
    }

    /**
     * At exactly {@code x == -1} the packed offset collides with the fill's empty-stack sentinel,
     * so the traversal ends before recording anything — and the draw is still made, off the square
     * the previous scatter left in the buffer. The horde is teleported to wherever the last horde
     * to scatter happened to start, with its own window's origin added on top.
     */
    @Test
    void aHordeDissolvingOnTheSentinelColumnInheritsThePreviousScattersSquare() {
        tick.scatterMembers(groupAt(10.5F, 10.5F, 2));
        PopManGroup group = groupAt(-0.5F, -0.5F, 2);

        tick.scatterMembers(group);

        assertEquals(
                2.5F, group.members.get(1).x, "local (2,0) of the last fill, re-based on (0,0)");
        assertEquals(0.5F, group.members.get(1).y);
    }

    @Test
    void detachingTakesTheGroupOutOfBothLists() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        PopManGroup group = groupAt(10.5F, 10.5F, 2);
        tick.rehome(group);

        tick.detach(group);

        assertNull(group.cell);
        assertTrue(cell.groups.isEmpty());
        assertTrue(groups.isEmpty());
    }

    private PopManCell streamedCellAround(float x, float y) {
        PopManCell cell = cells.load(0, 0, 0, 0);
        cell.setChunkStreamedIn(
                PopManGeometry.chunkOfSquare((int) x), PopManGeometry.chunkOfSquare((int) y), true);
        return cell;
    }

    @Test
    void aHordeStandingWellInsideALoadedChunkBecomesRealZombies() {
        streamedCellAround(4, 4);
        PopManGroup group = groupAt(4.5F, 4.5F, 3);

        tick.tick(group, 0, List.of(), 0L);

        assertEquals(3, results.spawns.size());
        assertTrue(groups.isEmpty());
        assertNull(group.cell);
    }

    @Test
    void realisedMembersInheritTheLeadersPathTarget() {
        streamedCellAround(4, 4);
        PopManGroup group = groupAt(4.5F, 4.5F, 2);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 41;

        tick.tick(group, 0, List.of(), 0L);

        assertEquals(40, group.members.get(1).pathTargetX);
        assertEquals(41, group.members.get(1).pathTargetY);
    }

    @Test
    void aHordeWalkingAlongTheRimOfALoadedAreaStaysVirtual() {
        streamedCellAround(0, 4);
        world.loadedAreas().set(new int[] {0, 0, 2, 2}, 1);
        PopManGroup group = groupAt(0.5F, 4.5F, 1);
        group.leader.pathTargetX = 5;
        group.leader.pathTargetY = 4;

        tick.tick(group, 0, List.of(), 0L);

        assertTrue(results.spawns.isEmpty(), "the rim would otherwise sprout a wall of zombies");
        assertEquals(1, groups.size());
    }

    @Test
    void aHordeStuckOnTheRimIsRealisedRatherThanLeftThereForever() {
        streamedCellAround(0, 4);
        world.loadedAreas().set(new int[] {0, 0, 2, 2}, 1);
        PopManGroup group = groupAt(0.5F, 4.5F, 1);

        tick.tick(group, 0, List.of(), 0L);

        assertEquals(1, results.spawns.size());
        assertTrue(groups.isEmpty());
    }

    @Test
    void aHordeThatHasWalkedItsSoundBudgetSettlesBackIntoTheChunk() {
        PopManCell cell = cells.load(0, 0, 0, 0);
        config.followSoundDistance = 1;
        tick.setSpeedMultiplier(20.0F);
        PopManGroup group = groupAt(4.5F, 4.5F, 1);
        group.followedSound = new PopManWorldSound(40, 4, 50, 30);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 4;

        tick.tick(group, 0, List.of(), 0L);

        assertTrue(groups.isEmpty());
        assertEquals(1, cell.virtualCount);
        assertTrue(cell.chunkAtSquare(5, 4).zombies.contains(group.leader));
    }

    @Test
    void theBudgetCountsSquaresEnteredSoAHordeGrindingAgainstAWallNeverGivesUp() {
        cells.load(0, 0, 0, 0);
        config.followSoundDistance = 1;
        PopManGroup group = groupAt(4.5F, 4.5F, 1);
        group.followedSound = new PopManWorldSound(40, 4, 50, 30);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 4;

        for (int i = 0; i < 5; i++) {
            tick.tick(group, 0, List.of(), 0L);
        }

        assertEquals(0, group.squaresTravelled);
        assertEquals(1, groups.size());
    }

    @Test
    void anAggroTargetInRangeHoldsTheHordeTogetherPastItsBudget() {
        cells.load(0, 0, 0, 0);
        config.followSoundDistance = 1;
        tick.setSpeedMultiplier(20.0F);
        PopManGroup group = groupAt(4.5F, 4.5F, 1);
        group.followedSound = new PopManWorldSound(40, 4, 50, 30);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 4;
        List<PopManInputFrame.AggroTarget> aggro =
                List.of(new PopManInputFrame.AggroTarget(1, 100, 4));

        tick.tick(group, 0, aggro, 0L);
        assertEquals(1, groups.size());
        assertEquals(1, group.squaresTravelled);

        tick.tick(group, 0, aggro, 0L);

        assertEquals(2, group.squaresTravelled, "nothing resets the counter, so it keeps climbing");
        assertEquals(1, groups.size());
    }

    @Test
    void anAggroTargetTooFarAwayDoesNotSaveTheHorde() {
        cells.load(0, 0, 0, 0);
        config.followSoundDistance = 1;
        tick.setSpeedMultiplier(20.0F);
        PopManGroup group = groupAt(4.5F, 4.5F, 1);
        group.followedSound = new PopManWorldSound(40, 4, 50, 30);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 4;

        tick.tick(group, 0, List.of(new PopManInputFrame.AggroTarget(1, 400, 4)), 0L);

        assertTrue(groups.isEmpty());
    }

    @Test
    void aHordeWalkingThroughUnloadedGroundIsLeftAloneRatherThanDissolved() {
        config.followSoundDistance = 1;
        tick.setSpeedMultiplier(20.0F);
        PopManGroup group = groupAt(4.5F, 4.5F, 2);
        group.followedSound = new PopManWorldSound(40, 4, 50, 30);
        group.leader.pathTargetX = 40;
        group.leader.pathTargetY = 4;

        tick.tick(group, 0, List.of(), 0L);

        assertEquals(1, groups.size(), "there is no cell to hand the members back to");
        assertTrue(results.spawns.isEmpty());
    }

    private PopManGroup pushingGroup(float pushX, float pushY) {
        PopManGroup group = groupAt(4.5F, 4.5F, 1);
        group.tickStartX = 4.5F;
        group.tickStartY = 4.5F;
        group.pushX = pushX;
        group.pushY = pushY;
        return group;
    }

    @Test
    void aHordeStoppedOnOneAxisKeepsTheComponentThatStillWorks() {
        world.blockColumn(5, 0, 10);
        PopManGroup group = pushingGroup(0.7F, 0.7F);

        assertTrue(tick.resolveMove(group));
        assertEquals(4.5F, group.leader.x, "the wall ate the x component");
        assertEquals(5.2F, group.leader.y, 0.0001F);
    }

    @Test
    void anUnobstructedStepIsTakenWhole() {
        PopManGroup group = pushingGroup(0.4F, 0.3F);

        assertFalse(tick.resolveMove(group));
        assertEquals(4.9F, group.leader.x, 0.0001F);
        assertEquals(4.8F, group.leader.y, 0.0001F);
    }

    @Test
    void noHordeCrossesMoreThanOneSquareHoweverFastTheGameIsRunning() {
        PopManGroup group = pushingGroup(30.0F, 40.0F);

        tick.resolveMove(group);

        assertEquals(5.1F, group.leader.x, 0.0001F);
        assertEquals(5.3F, group.leader.y, 0.0001F);
    }

    /** The clamp is silent: the horde is stopped at the edge without being told it was blocked. */
    @Test
    void aHordeAtTheEdgeOfTheWorldIsPushedBackOntoIt() {
        PopManGroup group = groupAt(-1023.5F, 10.5F, 1);
        group.tickStartX = -1023.5F;
        group.tickStartY = 10.5F;
        group.pushX = -0.9F;

        assertFalse(tick.resolveMove(group));
        assertEquals(-1024.0F, group.leader.x);
    }

    @Test
    void theAxisOrderFlipsEveryTickSoACornerCannotBiasEveryHordeTheSameWay() {
        PopManGroup group = pushingGroup(0.4F, 0.3F);
        boolean first = group.resolveYFirst;

        tick.resolveMove(group);
        assertEquals(!first, group.resolveYFirst);

        tick.resolveMove(group);
        assertEquals(first, group.resolveYFirst);
    }

    @Test
    void aHordeBoxedInOnBothAxesReportsItselfStuckAndStaysPut() {
        world.blockColumn(5, 0, 10);
        world.map(0, 5, "#########");
        PopManGroup group = pushingGroup(0.7F, 0.7F);

        assertTrue(tick.resolveMove(group));
        assertTrue(group.blocked);
        assertEquals(4.5F, group.leader.x);
        assertEquals(4.5F, group.leader.y);
    }

    /**
     * Creeping towards a wall never leaves the square, so the move itself is always legal. Without
     * the look ahead the horde would only notice the wall on the tick it was already inside it.
     */
    @Test
    void aStepTooSmallToLeaveItsSquareStillSeesTheWallAhead() {
        world.blockColumn(5, 0, 10);
        PopManGroup group = pushingGroup(0.06F, 0.0F);

        assertTrue(tick.resolveMove(group));
        assertEquals(4.5F, group.leader.x);
    }

    @Test
    void aHordeThatLeavesTheListMidSweepDoesNotMakeTheNextOneSkipATick() {
        streamedCellAround(4, 4);
        groupAt(4.5F, 4.5F, 1);
        groupAt(5.5F, 5.5F, 1);

        tick.tickAll(List.of(), 0L);

        assertEquals(2, results.spawns.size(), "both hordes must be visited");
        assertTrue(groups.isEmpty());
    }
}
