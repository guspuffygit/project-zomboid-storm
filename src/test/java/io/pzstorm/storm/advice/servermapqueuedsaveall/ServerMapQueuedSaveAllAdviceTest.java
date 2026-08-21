package io.pzstorm.storm.advice.servermapqueuedsaveall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.zomboid.OnPreSaveEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import zombie.network.GameServer;

/**
 * {@code ServerMap.QueuedSaveAll} is the single entry point for every dedicated-server save pass,
 * so the advice on it is where {@link OnPreSaveEvent} must fire — before the method body writes
 * anything. These tests drive the advice's static enter/exit methods directly (ByteBuddy inlines
 * the same code into {@code ServerMap}).
 */
class ServerMapQueuedSaveAllAdviceTest implements IntegrationTest {

    public static final class Recorder {
        static final List<OnPreSaveEvent> SEEN = new ArrayList<>();

        @SubscribeEvent
        public static void onPreSave(OnPreSaveEvent event) {
            SEEN.add(event);
        }
    }

    private static boolean registered;

    @BeforeEach
    void setUp() {
        if (!registered) {
            StormEventDispatcher.registerEventHandler(Recorder.class);
            registered = true;
        }
        Recorder.SEEN.clear();
        GameServer.server = true;
    }

    @AfterEach
    void tearDown() {
        GameServer.server = false;
    }

    @Test
    void autosaveDispatchesOnPreSaveWithQuitFalse() {
        long start = ServerMapQueuedSaveAllAdvice.onEnter(false);
        ServerMapQueuedSaveAllAdvice.onExit(start);

        assertTrue(start != 0L, "server save should be timed");
        assertEquals(1, Recorder.SEEN.size());
        assertFalse(Recorder.SEEN.get(0).isQuit());
        assertEquals("OnPreSave", Recorder.SEEN.get(0).getName());
    }

    @Test
    void shutdownSaveDispatchesOnPreSaveWithQuitTrue() {
        long start = ServerMapQueuedSaveAllAdvice.onEnter(true);
        ServerMapQueuedSaveAllAdvice.onExit(start);

        assertEquals(1, Recorder.SEEN.size());
        assertTrue(Recorder.SEEN.get(0).isQuit());
    }

    @Test
    void nothingFiresOffTheServer() {
        GameServer.server = false;
        long start = ServerMapQueuedSaveAllAdvice.onEnter(false);
        ServerMapQueuedSaveAllAdvice.onExit(start);

        assertEquals(0L, start);
        assertTrue(Recorder.SEEN.isEmpty());
    }
}
