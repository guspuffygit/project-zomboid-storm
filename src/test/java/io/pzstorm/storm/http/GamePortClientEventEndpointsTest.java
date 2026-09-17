package io.pzstorm.storm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GamePortClientEventEndpointsTest implements UnitTest {

    @AfterEach
    void tearDown() {
        GamePortClientEventEndpoints.reset();
    }

    @Test
    void sanitizeStripsControlCharactersAndTruncates() {
        String raw = " a\nb" + (char) 27 + "[31mc" + (char) 0 + " ";
        assertEquals("a b[31mc", GamePortClientEventEndpoints.sanitize(raw, 64));
        assertEquals("abc", GamePortClientEventEndpoints.sanitize("abcdef", 3));
    }

    @Test
    void rateLimitIsPerConnection() {
        long now = 1_000_000L;
        assertTrue(GamePortClientEventEndpoints.allow(1L, now));
        assertFalse(GamePortClientEventEndpoints.allow(1L, now + 1));
        assertTrue(GamePortClientEventEndpoints.allow(2L, now + 1));
        assertTrue(
                GamePortClientEventEndpoints.allow(
                        1L, now + GamePortClientEventEndpoints.MIN_INTERVAL_MILLIS));
    }

    @Test
    void trackedConnectionsAreCapped() {
        long now = 5_000_000L;
        for (long guid = 0; guid < GamePortClientEventEndpoints.MAX_TRACKED_CONNECTIONS; guid++) {
            assertTrue(GamePortClientEventEndpoints.allow(guid, now));
        }
        assertFalse(GamePortClientEventEndpoints.allow(-1L, now));
        assertTrue(
                GamePortClientEventEndpoints.allow(
                        -1L, now + GamePortClientEventEndpoints.MIN_INTERVAL_MILLIS));
    }
}
