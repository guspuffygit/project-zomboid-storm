package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldSoundListTest implements UnitTest {

    private static final long T0 = 1_000_000L;

    @Test
    void anIdenticalResendRefreshesRatherThanDuplicates() {
        WorldSoundList list = new WorldSoundList();
        PopManWorldSound first = list.merge(100, 200, 50, 30, T0, 24);
        PopManWorldSound again = list.merge(100, 200, 50, 30, T0 + 500, 25);

        assertSame(first, again);
        assertEquals(1, list.sounds().size());
        assertEquals(25, again.getWorldAgeHours());
    }

    @Test
    void anyFieldDifferenceMakesADistinctSound() {
        WorldSoundList list = new WorldSoundList();
        list.merge(100, 200, 50, 30, T0, 0);
        list.merge(101, 200, 50, 30, T0, 0);
        list.merge(100, 201, 50, 30, T0, 0);
        list.merge(100, 200, 51, 30, T0, 0);
        list.merge(100, 200, 50, 31, T0, 0);

        assertEquals(5, list.sounds().size());
    }

    @Test
    void aSoundDiesOneSecondAfterItStopsBeingResent() {
        WorldSoundList list = new WorldSoundList();
        list.merge(100, 200, 50, 30, T0, 0);

        list.ageAndCollectRecruiters(T0 + WorldSoundList.EXPIRY_MS);
        assertEquals(1, list.sounds().size(), "still alive exactly at the boundary");

        list.ageAndCollectRecruiters(T0 + WorldSoundList.EXPIRY_MS + 1);
        assertTrue(list.sounds().isEmpty());
    }

    @Test
    void aSustainedSoundRecruitsOnAFiveSecondCadence() {
        WorldSoundList list = new WorldSoundList();
        list.merge(100, 200, 50, 30, T0, 0);

        assertEquals(1, list.ageAndCollectRecruiters(T0).size(), "a new sound recruits at once");
        assertTrue(list.ageAndCollectRecruiters(T0 + 100).isEmpty());

        long resend = T0 + WorldSoundList.RECRUIT_INTERVAL_MS;
        list.merge(100, 200, 50, 30, resend, 0);
        assertTrue(list.ageAndCollectRecruiters(resend).isEmpty(), "not yet, the test is strict <");

        list.merge(100, 200, 50, 30, resend + 1, 0);
        assertEquals(1, list.ageAndCollectRecruiters(resend + 1).size());
    }

    @Test
    void anExpiringSoundDoesNotAlsoRecruit() {
        WorldSoundList list = new WorldSoundList();
        PopManWorldSound sound = list.merge(100, 200, 50, 30, T0, 0);
        sound.lastRecruitMs = T0;

        List<PopManWorldSound> due =
                list.ageAndCollectRecruiters(T0 + WorldSoundList.RECRUIT_INTERVAL_MS + 2);

        assertTrue(due.isEmpty());
        assertTrue(list.sounds().isEmpty());
    }

    @Test
    void nearFieldChunksAreLeftToJava() {
        PopManWorldSound sound = new PopManWorldSound(100, 100, 50, 30);

        assertFalse(
                WorldSoundList.chunkRecruits(sound, 12, 12),
                "the chunk containing the sound is inside the 10-tile exclusion");
        assertTrue(WorldSoundList.chunkRecruits(sound, 14, 14), "a chunk further out recruits");
    }

    @Test
    void chunksBeyondTheRadiusDoNotRecruit() {
        PopManWorldSound sound = new PopManWorldSound(100, 100, 50, 30);

        assertFalse(WorldSoundList.chunkRecruits(sound, 30, 30));
        assertEquals(6, WorldSoundList.minChunk(100, 50));
        assertEquals(18, WorldSoundList.maxChunk(100, 50));
    }

    @Test
    void chunkSpanFloorsTowardsNegativeInfinity() {
        assertEquals(-2, WorldSoundList.minChunk(3, 12), "floorDiv, not integer division");
    }
}
