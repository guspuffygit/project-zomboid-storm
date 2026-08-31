package io.pzstorm.storm.popman;

import java.util.List;

/**
 * Decides where each travelling horde is headed, once per tick and before any of them move.
 *
 * <p>Aggro and sound are not weighed against each other: a single aggro target anywhere on the map
 * suppresses sound retargeting for every group in the world, however far away it is. That is a
 * global switch rather than a per-group one, so one player being chased quietly freezes every
 * horde's interest in gunshots and alarms elsewhere.
 */
public final class PopManTargeting {

    /** Manhattan squares. Beyond this an aggro target is not adopted — but still suppresses. */
    public static final int AGGRO_RANGE = 300;

    private final PopManMap world;

    public PopManTargeting(PopManMap world) {
        this.world = world;
    }

    public void assignTargets(
            List<PopManGroup> groups,
            List<PopManInputFrame.AggroTarget> aggroTargets,
            List<PopManWorldSound> sounds) {

        if (!aggroTargets.isEmpty()) {
            for (PopManGroup group : groups) {
                aimAtAggro(group, aggroTargets);
            }
            return;
        }
        for (PopManGroup group : groups) {
            aimAtSound(group, sounds);
        }
    }

    private void aimAtAggro(PopManGroup group, List<PopManInputFrame.AggroTarget> aggroTargets) {
        PopManInputFrame.AggroTarget nearest = nearestAggro(group, aggroTargets);
        if (nearest == null) {
            return;
        }
        group.leader.pathTargetX = nearest.x();
        group.leader.pathTargetY = nearest.y();
    }

    /** Null when every target is out of range; distance is Manhattan, strictly less than. */
    public PopManInputFrame.AggroTarget nearestAggro(
            PopManGroup group, List<PopManInputFrame.AggroTarget> aggroTargets) {
        PopManInputFrame.AggroTarget nearest = null;
        int best = AGGRO_RANGE;
        int leaderX = group.leaderSquareX();
        int leaderY = group.leaderSquareY();
        for (PopManInputFrame.AggroTarget target : aggroTargets) {
            int distance = Math.abs(target.x() - leaderX) + Math.abs(target.y() - leaderY);
            if (distance < best) {
                best = distance;
                nearest = target;
            }
        }
        return nearest;
    }

    /**
     * A sound is heard when the leader is inside its radius, measured as a true circle rather than
     * the Manhattan box aggro uses.
     */
    private void aimAtSound(PopManGroup group, List<PopManWorldSound> sounds) {
        PopManWorldSound nearest = null;
        long best = Long.MAX_VALUE;
        int leaderX = group.leaderSquareX();
        int leaderY = group.leaderSquareY();
        for (PopManWorldSound sound : sounds) {
            long dx = sound.x - leaderX;
            long dy = sound.y - leaderY;
            long distance = dx * dx + dy * dy;
            long radius = (long) sound.radius * sound.radius;
            if (distance < radius && distance < best) {
                best = distance;
                nearest = sound;
            }
        }
        if (nearest == null || nearest == group.followedSound) {
            return;
        }
        group.followedSound = nearest;
        group.squaresTravelled = 0;

        float spread =
                Math.abs(nearest.y + 0.5F - group.leader.y)
                        + Math.abs(nearest.x + 0.5F - group.leader.x);
        group.leader.pathTargetX =
                (int) Math.floor(nearest.x + world.randomFloat(-spread, spread) * 0.25F);
        group.leader.pathTargetY =
                (int) Math.floor(nearest.y + world.randomFloat(-spread, spread) * 0.25F);
    }
}
