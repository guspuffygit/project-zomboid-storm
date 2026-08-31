package io.pzstorm.storm.popman;

/**
 * A world sound the population is listening to — native {@code popman::WorldSound}, 40 bytes.
 * {@code radius} arrives already scaled by the sandbox hearing setting; {@code volume} does not.
 */
public final class PopManWorldSound {

    public final int x;
    public final int y;
    public final int radius;
    public final int volume;

    /** Bumped every time Java re-sends an identical sound; drives expiry. */
    long lastRefreshedMs;

    /** Zero until the first recruitment, so a new sound recruits on its first ageing pass. */
    long lastRecruitMs;

    int worldAgeHours;

    public PopManWorldSound(int x, int y, int radius, int volume) {
        this.x = x;
        this.y = y;
        this.radius = radius;
        this.volume = volume;
    }

    /** Identity is all four fields exactly — this is how the native side deduplicates. */
    public boolean matches(int x, int y, int radius, int volume) {
        return this.x == x && this.y == y && this.radius == radius && this.volume == volume;
    }

    public int getWorldAgeHours() {
        return worldAgeHours;
    }
}
