package io.pzstorm.storm.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;

class GamePortHandshakeEndpointsTest implements UnitTest {

    @Test
    void clientOsDefaultsToUnknownWithoutAHeader() {
        assertEquals("unknown", GamePortHandshakeEndpoints.clientOs(null));
        assertEquals("unknown", GamePortHandshakeEndpoints.clientOs("  "));
    }

    @Test
    void clientOsIsCleanedAndCapped() {
        assertEquals(
                "Mac OS X 26.5.1 aarch64",
                GamePortHandshakeEndpoints.clientOs("Mac OS X 26.5.1 aarch64"));
        assertEquals("Linux mtu=1200", GamePortHandshakeEndpoints.clientOs("Linux\nmtu=1200"));
        assertEquals(64, GamePortHandshakeEndpoints.clientOs("x".repeat(500)).length());
    }
}
