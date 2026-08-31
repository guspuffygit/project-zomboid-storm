package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class PopManTargetingTest implements UnitTest {

    private final ScriptedWorld world = new ScriptedWorld();
    private final PopManTargeting targeting = new PopManTargeting(world);

    private static PopManGroup groupAt(int x, int y) {
        PopManZombie leader = new PopManZombie();
        leader.x = x + 0.5F;
        leader.y = y + 0.5F;
        return new PopManGroup(leader);
    }

    @Test
    void aggroWithinRangeBecomesThePathTarget() {
        PopManGroup group = groupAt(0, 0);

        targeting.assignTargets(
                List.of(group), List.of(new PopManInputFrame.AggroTarget(1, 100, 50)), List.of());

        assertEquals(100, group.leader.pathTargetX);
        assertEquals(50, group.leader.pathTargetY);
    }

    @Test
    void theAggroRangeIsManhattanAndStrict() {
        PopManGroup group = groupAt(0, 0);

        targeting.assignTargets(
                List.of(group), List.of(new PopManInputFrame.AggroTarget(1, 150, 150)), List.of());

        assertEquals(
                PopManZombie.INVALID_PATH_XY,
                group.leader.pathTargetX,
                "150+150 is exactly 300, and the test is strictly less than");
    }

    @Test
    void theNearestAggroTargetWins() {
        PopManGroup group = groupAt(0, 0);

        targeting.assignTargets(
                List.of(group),
                List.of(
                        new PopManInputFrame.AggroTarget(1, 200, 0),
                        new PopManInputFrame.AggroTarget(2, 30, 30),
                        new PopManInputFrame.AggroTarget(3, 100, 100)),
                List.of());

        assertEquals(30, group.leader.pathTargetX);
    }

    /**
     * The costly half of the native's design: aggro is a global switch, not a per-group one, so a
     * target nobody can reach still silences every sound on the map.
     */
    @Test
    void oneDistantAggroTargetSuppressesSoundsForEveryGroup() {
        PopManGroup near = groupAt(100, 100);
        PopManWorldSound sound = new PopManWorldSound(105, 100, 50, 30);

        targeting.assignTargets(
                List.of(near),
                List.of(new PopManInputFrame.AggroTarget(1, 10_000, 10_000)),
                List.of(sound));

        assertNull(near.followedSound, "the sound was in earshot and still not adopted");
        assertEquals(PopManZombie.INVALID_PATH_XY, near.leader.pathTargetX);
    }

    @Test
    void anEmptyAggroListLetsSoundsThrough() {
        PopManGroup group = groupAt(100, 100);
        PopManWorldSound sound = new PopManWorldSound(105, 100, 50, 30);

        targeting.assignTargets(List.of(group), List.of(), List.of(sound));

        assertSame(sound, group.followedSound);
        assertEquals(103, group.leader.pathTargetX);
        assertEquals(98, group.leader.pathTargetY);
    }

    @Test
    void soundRangeIsACircleNotTheManhattanBoxAggroUses() {
        PopManGroup group = groupAt(0, 0);
        PopManWorldSound sound = new PopManWorldSound(8, 8, 10, 30);

        targeting.assignTargets(List.of(group), List.of(), List.of(sound));

        assertNull(group.followedSound, "8^2+8^2 is 128, outside a radius of 10");
    }

    @Test
    void theNearestSoundWins() {
        PopManGroup group = groupAt(0, 0);
        PopManWorldSound far = new PopManWorldSound(40, 0, 100, 30);
        PopManWorldSound near = new PopManWorldSound(10, 0, 100, 30);

        targeting.assignTargets(List.of(group), List.of(), List.of(far, near));

        assertSame(near, group.followedSound);
    }

    @Test
    void adoptingANewSoundResetsTheTravelBudget() {
        PopManGroup group = groupAt(100, 100);
        group.squaresTravelled = 40;

        targeting.assignTargets(
                List.of(group), List.of(), List.of(new PopManWorldSound(105, 100, 50, 30)));

        assertEquals(0, group.squaresTravelled);
    }

    @Test
    void hearingTheSameSoundAgainDoesNotRefundTheBudget() {
        PopManGroup group = groupAt(100, 100);
        PopManWorldSound sound = new PopManWorldSound(105, 100, 50, 30);
        group.followedSound = sound;
        group.squaresTravelled = 40;

        targeting.assignTargets(List.of(group), List.of(), List.of(sound));

        assertEquals(40, group.squaresTravelled, "a group must not walk towards one sound forever");
    }

    /**
     * The adopted target is jittered by a quarter of a draw from {@code [-d, d)}, where {@code d}
     * is the Manhattan distance the group has to cover — so hordes converging on one noise from far
     * away fan out around it instead of stacking on a single square. Both axes share one spread but
     * take separate draws.
     */
    @Test
    void theTargetJitterWidensWithTheDistanceToTheNoise() {
        PopManGroup group = groupAt(100, 100);
        world.unitRoll = 0.99F;

        targeting.assignTargets(
                List.of(group), List.of(), List.of(new PopManWorldSound(105, 102, 50, 30)));

        assertEquals(106, group.leader.pathTargetX);
        assertEquals(103, group.leader.pathTargetY);
        assertEquals(2, world.rollsTaken);
    }
}
