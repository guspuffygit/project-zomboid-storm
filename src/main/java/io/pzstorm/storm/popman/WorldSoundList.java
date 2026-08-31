package io.pzstorm.storm.popman;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The population's live world sounds, with the native merge and ageing rules. Java re-sends every
 * live sound each frame, so a sustained source (generator, alarm) stays resident and re-recruits on
 * a fixed cadence rather than once.
 *
 * <p>Times are milliseconds from a steady clock. The native side used {@code
 * _Xtime_get_ticks()/10000}, so callers must feed this {@link System#nanoTime()}, never wall time.
 */
public final class WorldSoundList {

    /** A sound dies this long after it was last re-sent. */
    public static final long EXPIRY_MS = 1000;

    /** A resident sound pulls zombies at most this often. */
    public static final long RECRUIT_INTERVAL_MS = 5000;

    /**
     * Chunks whose centre is nearer than 10 tiles do not recruit — zombies that close are inside a
     * loaded area and belong to Java. Dropping this makes near-field hordes spawn twice.
     */
    public static final float MIN_RECRUIT_DISTANCE_SQUARED = 100.0F;

    public static final int SQUARES_PER_CHUNK = 8;

    private final List<PopManWorldSound> sounds = new ArrayList<>();

    public List<PopManWorldSound> sounds() {
        return sounds;
    }

    /** Adds a sound, or refreshes the identical one already present. */
    public PopManWorldSound merge(
            int x, int y, int radius, int volume, long nowMs, int worldAgeHours) {
        for (PopManWorldSound existing : sounds) {
            if (existing.matches(x, y, radius, volume)) {
                existing.lastRefreshedMs = nowMs;
                existing.worldAgeHours = worldAgeHours;
                return existing;
            }
        }
        PopManWorldSound added = new PopManWorldSound(x, y, radius, volume);
        added.lastRefreshedMs = nowMs;
        added.worldAgeHours = worldAgeHours;
        sounds.add(added);
        return added;
    }

    /**
     * Drops expired sounds and returns those due to recruit, stamping them as recruited. A sound
     * that expires this pass never recruits in the same pass.
     */
    public List<PopManWorldSound> ageAndCollectRecruiters(long nowMs) {
        List<PopManWorldSound> due = new ArrayList<>();
        for (Iterator<PopManWorldSound> it = sounds.iterator(); it.hasNext(); ) {
            PopManWorldSound sound = it.next();
            if (sound.lastRefreshedMs + EXPIRY_MS < nowMs) {
                it.remove();
            } else if (sound.lastRecruitMs + RECRUIT_INTERVAL_MS < nowMs) {
                due.add(sound);
                sound.lastRecruitMs = nowMs;
            }
        }
        return due;
    }

    /** The chunk-coordinate span a sound reaches, inclusive. */
    public static int minChunk(int centre, int radius) {
        return Math.floorDiv(centre - radius, SQUARES_PER_CHUNK);
    }

    public static int maxChunk(int centre, int radius) {
        return Math.floorDiv(centre + radius, SQUARES_PER_CHUNK);
    }

    /** Whether a chunk is inside the sound and outside the near-field exclusion. */
    public static boolean chunkRecruits(PopManWorldSound sound, int chunkX, int chunkY) {
        float dx = (sound.x + 0.5F) - (chunkX * SQUARES_PER_CHUNK + 4);
        float dy = (sound.y + 0.5F) - (chunkY * SQUARES_PER_CHUNK + 4);
        float distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > (float) sound.radius * sound.radius) {
            return false;
        }
        return distanceSquared >= MIN_RECRUIT_DISTANCE_SQUARED;
    }
}
