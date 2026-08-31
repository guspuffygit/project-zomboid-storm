package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * A travelling horde — native {@code popman::VirtualZombie}, 80 bytes. The leader carries the
 * group's position and path target; the rest follow it as one object until the group is realised or
 * dissolved.
 *
 * <p>Only the leader has a position. Members keep whatever coordinates they had when the group
 * formed and are scattered around the leader at the moment the group ends, so reading a member's
 * position while the group is walking gives a stale answer by design.
 *
 * <p>Groups live in the cell they are currently passing through, not in a chunk. A zombie is in
 * exactly one of the two places: a chunk's resident list, or a group's member list.
 */
public final class PopManGroup {

    /** Redistribution never moves more than this many zombies as one group. */
    public static final int MAX_REDISTRIBUTE_MEMBERS = 5;

    /** Sound-following gathers a larger horde than redistribution does. */
    public static final int MAX_SOUND_MEMBERS = 26;

    public PopManZombie leader;

    /** Includes the leader at index zero. */
    public final List<PopManZombie> members = new ArrayList<>();

    /** Where the leader stood when this tick began, re-snapshotted every tick. */
    public float tickStartX;

    public float tickStartY;

    /**
     * Where the move is being worked out. It starts each tick at the leader's position, absorbs the
     * push, and is reverted axis by axis as the resolver finds walls; whatever survives is written
     * back to the leader.
     */
    public float workX;

    public float workY;

    /** The displacement steering asked for this tick, folded into the move and then zeroed. */
    public float pushX;

    public float pushY;

    /**
     * Which axis wins when both are blocked, flipped once per tick so a corner cannot push every
     * group in the world the same way. Native {@code axisOrder == 0} means the Y axis wins.
     */
    public boolean resolveYFirst = true;

    /** Set when the move hit something; cleared at the top of every tick. */
    public boolean blocked;

    public PopManCell cell;

    /** The world sound this group is walking towards, or null. */
    public PopManWorldSound followedSound;

    /**
     * Counts against {@code FollowSoundDistance}, which is a travel budget rather than a radius: it
     * rises by one each time the leader's integer square changes, so a group that walks into a wall
     * never exhausts it.
     */
    public short squaresTravelled;

    public PopManGroup(PopManZombie leader) {
        this.leader = leader;
        this.members.add(leader);
        this.tickStartX = leader.x;
        this.tickStartY = leader.y;
        this.workX = leader.x;
        this.workY = leader.y;
    }

    /** An empty member list still means one zombie: the leader. */
    public int population() {
        return members.isEmpty() ? 1 : members.size();
    }

    public int leaderSquareX() {
        return (int) Math.floor(leader.x);
    }

    public int leaderSquareY() {
        return (int) Math.floor(leader.y);
    }
}
