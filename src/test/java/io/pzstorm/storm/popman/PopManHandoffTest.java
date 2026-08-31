package io.pzstorm.storm.popman;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class PopManHandoffTest implements UnitTest {

    @Test
    void anEmptyFrameIsNotPublished() {
        PopManHandoff handoff = new PopManHandoff();

        assertFalse(handoff.publish());

        handoff.drainInput();
        assertTrue(handoff.workerInput().isEmpty());
    }

    @Test
    void aConfigChangeAloneIsWorthPublishing() {
        PopManHandoff handoff = new PopManHandoff();
        handoff.input().floatConfig.put("PopulationMultiplier", 2.0F);
        handoff.input().configDirty = true;

        assertTrue(handoff.publish());

        handoff.drainInput();
        assertEquals(2.0F, handoff.workerInput().floatConfig.get("PopulationMultiplier"));
    }

    @Test
    void publishingDetachesTheFrameFromLaterSetters() {
        PopManHandoff handoff = new PopManHandoff();
        PopManInputFrame published = handoff.input();
        published.chunkLoads.add(new PopManInputFrame.ChunkLoad(1, 2, true));
        handoff.publish();

        assertNotSame(published, handoff.input());
        handoff.input().chunkLoads.add(new PopManInputFrame.ChunkLoad(9, 9, false));

        handoff.drainInput();
        assertEquals(
                1, handoff.workerInput().chunkLoads.size(), "only the published frame arrives");
    }

    @Test
    void severalFramesMergePerFieldRatherThanLastWins() {
        PopManHandoff handoff = new PopManHandoff();

        handoff.input().chunkLoads.add(new PopManInputFrame.ChunkLoad(1, 1, true));
        handoff.input().radarRequested = true;
        handoff.input().timeChanged = true;
        handoff.input().worldAgeHours = 10.0;
        handoff.publish();

        handoff.input().chunkLoads.add(new PopManInputFrame.ChunkLoad(2, 2, false));
        handoff.input().timeChanged = true;
        handoff.input().worldAgeHours = 11.0;
        handoff.publish();

        handoff.drainInput();
        PopManInputFrame merged = handoff.workerInput();

        assertEquals(2, merged.chunkLoads.size(), "lists append");
        assertTrue(merged.radarRequested, "flags OR, so the older request survives");
        assertEquals(11.0, merged.worldAgeHours, "scalars take the newest");
    }

    @Test
    void resultsAreOnlyValidUntilTheNextDrain() {
        PopManHandoff handoff = new PopManHandoff();
        handoff.output().spawns.add(new PopManZombie());
        assertTrue(handoff.publishResults());

        handoff.drainResults();
        assertEquals(1, handoff.results().spawns.size());

        handoff.drainResults();
        assertTrue(handoff.results().spawns.isEmpty(), "a tick with no worker output clears them");
    }

    @Test
    void anEmptyResultFrameIsNotPublished() {
        PopManHandoff handoff = new PopManHandoff();

        assertFalse(handoff.publishResults());

        handoff.drainResults();
        assertTrue(handoff.results().isEmpty());
    }

    @Test
    void resultFramesProducedWithinOneTickAreMerged() {
        PopManHandoff handoff = new PopManHandoff();
        handoff.output().spawns.add(new PopManZombie());
        handoff.publishResults();
        handoff.output().spawns.add(new PopManZombie());
        handoff.output().radarXY = new float[] {1.0F, 2.0F};
        handoff.output().radarSet = true;
        handoff.publishResults();

        handoff.drainResults();

        assertEquals(2, handoff.results().spawns.size());
        assertTrue(handoff.results().radarSet);
    }
}
