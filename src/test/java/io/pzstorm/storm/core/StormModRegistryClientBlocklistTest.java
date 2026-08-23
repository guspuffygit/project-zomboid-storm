package io.pzstorm.storm.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class StormModRegistryClientBlocklistTest {

    @Test
    void shouldBlockListedModOnClient() {
        withStormServer(
                false,
                () -> {
                    Assertions.assertTrue(
                            StormModRegistry.isBlockedOnClient(
                                    "org.dotd.authgate.DotdAuthGateMod"));
                    Assertions.assertTrue(StormModRegistry.isBlockedOnClient("org.dotd.authgate"));
                });
    }

    @Test
    void shouldNotBlockListedModOnServer() {
        withStormServer(
                true,
                () ->
                        Assertions.assertFalse(
                                StormModRegistry.isBlockedOnClient(
                                        "org.dotd.authgate.DotdAuthGateMod")));
    }

    @Test
    void shouldNotBlockUnlistedModOnClient() {
        withStormServer(
                false,
                () -> {
                    Assertions.assertFalse(
                            StormModRegistry.isBlockedOnClient(
                                    "com.sentientsimulations.projectzomboid.zonemarker.ZoneMarkerMod"));
                    Assertions.assertFalse(
                            StormModRegistry.isBlockedOnClient("org.dotd.authgateway.SomeMod"));
                });
    }

    private static void withStormServer(boolean isServer, Runnable test) {
        String previous = System.getProperty("storm.server");
        System.setProperty("storm.server", Boolean.toString(isServer));
        try {
            test.run();
        } finally {
            if (previous != null) {
                System.setProperty("storm.server", previous);
            } else {
                System.clearProperty("storm.server");
            }
        }
    }
}
