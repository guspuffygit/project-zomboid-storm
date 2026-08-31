package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.List;

/**
 * The two ways a horde forms: a chunk sheds its overcrowding into empty neighbours, or a world
 * sound pulls everyone in earshot towards it.
 *
 * <p>Both work the same way — lift a leader and whatever is flood-reachable within its own 8x8
 * chunk out of the chunk's resident list, and hand the lot to a {@link PopManGroup} that walks. The
 * differences are the trigger, the cap on group size, and where the group is aimed.
 */
public final class PopManGrouping {

    /** A chunk holding more than this sheds the excess. */
    public static final int CROWDING_THRESHOLD = 5;

    /** Zombies playing dead do not join a horde. */
    private static final int FAKE_DEAD = 8;

    private final PopManConfig config;
    private final PopManMap world;
    private final PopManCellMap cells;
    private final List<PopManGroup> groups;

    /**
     * One fill per caller, matching the native's two static instances. Sharing a single one would
     * be a bug the day redistribution and sound recruitment interleave.
     */
    private final PopManFloodFill redistributeFill = new PopManFloodFill(8);

    private final PopManFloodFill soundFill = new PopManFloodFill(8);

    public PopManGrouping(
            PopManConfig config, PopManMap world, PopManCellMap cells, List<PopManGroup> groups) {
        this.config = config;
        this.world = world;
        this.cells = cells;
        this.groups = groups;
    }

    public void redistributeAll(double worldAgeHours) {
        if (config.redistributeHours <= 0.0F) {
            return;
        }
        float age = (float) worldAgeHours;
        for (PopManCell cell : cells.active()) {
            cell.lastRedistributeTime = Math.min(cell.lastRedistributeTime, age);
            if (cell.lastRedistributeTime + config.redistributeHours < age) {
                redistributeCell(cell);
                cell.lastRedistributeTime = age;
            }
        }
    }

    /** Spreads every overcrowded chunk of a cell into that cell's empty ones. */
    public void redistributeCell(PopManCell cell) {
        List<PopManChunk> empty = new ArrayList<>();
        for (PopManChunk chunk : cell.chunks) {
            if (chunk.zombies.isEmpty() && !world.isChunkBlocked(chunk.chunkX, chunk.chunkY)) {
                empty.add(chunk);
            }
        }
        if (empty.isEmpty()) {
            return;
        }
        for (PopManChunk chunk : cell.chunks) {
            shedChunk(cell, chunk, empty);
        }
    }

    private void shedChunk(PopManCell cell, PopManChunk chunk, List<PopManChunk> empty) {
        if (chunk.zombies.size() <= CROWDING_THRESHOLD) {
            return;
        }
        List<PopManZombie> movers = eligible(chunk);
        while (movers.size() > CROWDING_THRESHOLD) {
            PopManChunk destination = empty.get(world.random(empty.size()));
            formGroup(
                    cell,
                    chunk,
                    movers,
                    destination.minSquareX() + PopManGeometry.SQUARES_PER_CHUNK / 2,
                    destination.minSquareY() + PopManGeometry.SQUARES_PER_CHUNK / 2,
                    PopManGroup.MAX_REDISTRIBUTE_MEMBERS,
                    redistributeFill,
                    null);
        }
    }

    /**
     * Pulls every eligible zombie in earshot into a horde walking towards the noise. Chunks nearer
     * than ten squares are left alone — those zombies are close enough for the game itself to
     * handle.
     */
    public void recruitForSound(PopManWorldSound sound, double worldAgeHours, long nowMs) {
        int minChunkX = WorldSoundList.minChunk(sound.x, sound.radius);
        int maxChunkX = WorldSoundList.maxChunk(sound.x, sound.radius);
        int minChunkY = WorldSoundList.minChunk(sound.y, sound.radius);
        int maxChunkY = WorldSoundList.maxChunk(sound.y, sound.radius);

        for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!WorldSoundList.chunkRecruits(sound, chunkX, chunkY)) {
                    continue;
                }
                PopManCell cell =
                        cells.load(
                                PopManGeometry.cellOfChunk(chunkX),
                                PopManGeometry.cellOfChunk(chunkY),
                                worldAgeHours,
                                nowMs);
                PopManChunk chunk = cell.chunkAt(chunkX, chunkY);
                float spread = soundSpread(sound, chunkX, chunkY);
                List<PopManZombie> movers = eligible(chunk);
                while (!movers.isEmpty()) {
                    formGroup(
                            cell,
                            chunk,
                            movers,
                            (int) Math.floor(sound.x + world.randomFloat(-spread, spread) * 0.25F),
                            (int) Math.floor(sound.y + world.randomFloat(-spread, spread) * 0.25F),
                            PopManGroup.MAX_SOUND_MEMBERS,
                            soundFill,
                            sound);
                }
            }
        }
    }

    /**
     * How far off the noise a recruited horde may aim. Groups pulled from farther chunks scatter
     * proportionally wider, so a shout does not stack every horde in earshot on one square.
     */
    private static float soundSpread(PopManWorldSound sound, int chunkX, int chunkY) {
        float half = PopManGeometry.SQUARES_PER_CHUNK / 2.0F;
        float centreX = chunkX * PopManGeometry.SQUARES_PER_CHUNK + half;
        float centreY = chunkY * PopManGeometry.SQUARES_PER_CHUNK + half;
        return Math.abs(sound.y + 0.5F - centreY) + Math.abs(sound.x + 0.5F - centreX);
    }

    /** Ground level, not playing dead, and standing somewhere it can walk out of. */
    private List<PopManZombie> eligible(PopManChunk chunk) {
        List<PopManZombie> movers = new ArrayList<>();
        for (PopManZombie zombie : chunk.zombies) {
            int squareX = (int) Math.floor(zombie.x);
            int squareY = (int) Math.floor(zombie.y);
            if ((int) Math.floor(zombie.z) == 0
                    && (zombie.stateFlags & FAKE_DEAD) == 0
                    && !world.isSquareBlocked(squareX, squareY)) {
                movers.add(zombie);
            }
        }
        return movers;
    }

    private PopManGroup formGroup(
            PopManCell cell,
            PopManChunk chunk,
            List<PopManZombie> movers,
            int targetX,
            int targetY,
            int maxMembers,
            PopManFloodFill fill,
            PopManWorldSound sound) {

        PopManZombie leader = movers.get(0);
        PopManGroup group = new PopManGroup(leader);
        leader.pathTargetX = targetX;
        leader.pathTargetY = targetY;
        group.followedSound = sound;
        group.squaresTravelled = 0;

        int leaderX = (int) Math.floor(leader.x);
        int leaderY = (int) Math.floor(leader.y);
        fill.run(
                leaderX,
                leaderY,
                PopManGeometry.chunkOfSquare(leaderX) * PopManGeometry.SQUARES_PER_CHUNK,
                PopManGeometry.chunkOfSquare(leaderY) * PopManGeometry.SQUARES_PER_CHUNK,
                world);

        for (int i = 1; i < movers.size() && group.members.size() < maxMembers; ) {
            PopManZombie follower = movers.get(i);
            if (fill.isVisited((int) Math.floor(follower.x), (int) Math.floor(follower.y))) {
                group.members.add(follower);
                movers.remove(i);
            } else {
                i++;
            }
        }
        movers.remove(leader);

        PopManGroupTick.rehome(cells, group);
        groups.add(group);
        cell.virtualCount -= (short) group.members.size();
        chunk.zombies.removeAll(group.members);
        cell.dirty = true;
        return group;
    }
}
