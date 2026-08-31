package io.pzstorm.storm.core;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormPrivacyNoticeTest implements UnitTest {

    @Test
    void shouldShipPolicyInsideStormJar() {
        String text = StormPrivacyNotice.load();
        Assertions.assertNotNull(text, "privacy-policy.txt must be copied into Storm's resources");
        Assertions.assertTrue(text.contains("PRIVACY POLICY"), "unexpected policy text");
        Assertions.assertNotEquals("unversioned", StormPrivacyNotice.parseVersion(text));
    }

    @Test
    void shouldParseVersionHeader() {
        Assertions.assertEquals(
                "2026-01-02", StormPrivacyNotice.parseVersion("Title\nVersion: 2026-01-02\nbody"));
        Assertions.assertEquals("unversioned", StormPrivacyNotice.parseVersion("no header"));
    }
}
