package io.pzstorm.storm.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StormVersionTest {

    @Test
    void getVersion_returnsNonNullValue() {
        String version = StormVersion.getVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());
    }

    @Test
    void getVersion_returnsDevWhenRunningUnpackaged() {
        assertEquals("dev", StormVersion.loadVersionFrom(null));
    }
}
