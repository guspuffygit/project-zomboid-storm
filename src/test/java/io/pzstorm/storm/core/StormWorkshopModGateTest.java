package io.pzstorm.storm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StormWorkshopModGateTest {

    /** The gradle test task pins storm.server=true suite-wide, so save/restore, never clear. */
    private static final String[] MANAGED_PROPERTIES = {
        StormWorkshopModGate.WORKSHOP_MODS_PROPERTY,
        StormWorkshopModGate.SERVERNAME_PROPERTY,
        StormWorkshopModGate.CACHEDIR_PROPERTY,
        "storm.server"
    };

    private final Map<String, String> savedProperties = new HashMap<>();

    @TempDir Path cacheDir;

    @BeforeEach
    void isolateProperties() {
        for (String property : MANAGED_PROPERTIES) {
            savedProperties.put(property, System.getProperty(property));
        }
        // keep every test away from the real ~/Zomboid/Server directory
        System.setProperty(StormWorkshopModGate.CACHEDIR_PROPERTY, cacheDir.toString());
        System.clearProperty(StormWorkshopModGate.WORKSHOP_MODS_PROPERTY);
        System.clearProperty(StormWorkshopModGate.SERVERNAME_PROPERTY);
    }

    @AfterEach
    void restoreProperties() {
        for (String property : MANAGED_PROPERTIES) {
            String saved = savedProperties.get(property);
            if (saved == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, saved);
            }
        }
    }

    @Test
    void clientWithoutOverrideLoadsNothingFromWorkshop() {
        System.setProperty("storm.server", "false");
        assertEquals(Set.of(), StormWorkshopModGate.enabledMods());
    }

    @Test
    void propertyOverrideGatesAnyJvm() {
        System.setProperty("storm.server", "false");
        System.setProperty(StormWorkshopModGate.WORKSHOP_MODS_PROPERTY, "modA;modB");
        assertEquals(Set.of("modA", "modB"), StormWorkshopModGate.enabledMods());
    }

    @Test
    void serverReadsModsLineFromIni() throws IOException {
        writeServerIni("stormtest", "Mods=\\modA; modB ;;modC");
        System.setProperty("storm.server", "true");
        System.setProperty(StormWorkshopModGate.SERVERNAME_PROPERTY, "stormtest");
        assertEquals(Set.of("modA", "modB", "modC"), StormWorkshopModGate.enabledMods());
    }

    @Test
    void serverWithMissingIniLoadsNothingFromWorkshop() {
        System.setProperty("storm.server", "true");
        assertEquals(Set.of(), StormWorkshopModGate.enabledMods());
    }

    @Test
    void serverWithEmptyModsLineLoadsNothingFromWorkshop() throws IOException {
        writeServerIni("servertest", "Mods=");
        System.setProperty("storm.server", "true");
        assertEquals(Set.of(), StormWorkshopModGate.enabledMods());
    }

    @Test
    void captureGameArgsMirrorsGameServerParsing() {
        System.clearProperty(StormWorkshopModGate.CACHEDIR_PROPERTY);
        StormWorkshopModGate.captureGameArgs(
                new String[] {"-adminpassword", "x", "-servername", "atf", "-cachedir=/tmp/pz"});
        assertEquals("atf", System.getProperty(StormWorkshopModGate.SERVERNAME_PROPERTY));
        assertEquals("/tmp/pz", System.getProperty(StormWorkshopModGate.CACHEDIR_PROPERTY));
    }

    @Test
    void captureGameArgsDoesNotClobberExplicitProperties() {
        System.setProperty(StormWorkshopModGate.SERVERNAME_PROPERTY, "explicit");
        StormWorkshopModGate.captureGameArgs(new String[] {"-servername", "fromArgs"});
        assertEquals("explicit", System.getProperty(StormWorkshopModGate.SERVERNAME_PROPERTY));
    }

    private void writeServerIni(String serverName, String modsLine) throws IOException {
        Path serverDir = cacheDir.resolve("Server");
        Files.createDirectories(serverDir);
        Files.writeString(
                serverDir.resolve(serverName + ".ini"), "PVP=false\n" + modsLine + "\nOpen=true\n");
    }
}
