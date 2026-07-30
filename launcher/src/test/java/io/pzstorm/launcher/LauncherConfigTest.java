package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherConfigTest {

    @TempDir Path tmp;

    @Test
    void saveLoadRoundtrip() throws IOException {
        LauncherConfig config = new LauncherConfig();
        config.gameDir = "E:\\SteamLibrary\\steamapps\\common\\ProjectZomboid";
        config.globalVmArgs.add("-Xmx16g");
        ServerProfile profile = new ServerProfile();
        profile.name = "ATF";
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.stormHttpPort = 41798;
        profile.serverPassword = "pw";
        profile.noSteam = true;
        profile.extraVmArgs.add("-DstormType=local");
        config.servers.add(profile);

        Path file = tmp.resolve("cfg/launcher.json");
        config.save(file);
        assertTrue(Files.isRegularFile(file));

        LauncherConfig loaded = LauncherConfig.load(file);
        assertEquals(config.gameDir, loaded.gameDir);
        assertEquals(config.globalVmArgs, loaded.globalVmArgs);
        assertEquals(1, loaded.servers.size());
        ServerProfile p = loaded.servers.get(0);
        assertEquals("ATF", p.name);
        assertEquals("play.example.org", p.host);
        assertEquals(16261, p.port);
        assertEquals(41798, p.stormHttpPort);
        assertEquals("pw", p.serverPassword);
        assertTrue(p.noSteam);
        assertTrue(p.syncMods);
        assertEquals(java.util.List.of("-DstormType=local"), p.extraVmArgs);
    }

    @Test
    void loadOfMissingOrBrokenFileYieldsDefaults() throws IOException {
        assertTrue(LauncherConfig.load(tmp.resolve("missing.json")).servers.isEmpty());
        Path broken = tmp.resolve("broken.json");
        Files.write(broken, "{not json".getBytes());
        assertTrue(LauncherConfig.load(broken).servers.isEmpty());
    }

    @Test
    void resolveGameDirRejectsDirWithoutGameJson() {
        LauncherConfig config = new LauncherConfig();
        config.gameDir = tmp.toString();
        assertNull(config.resolveGameDir());
        assertNotNull(config.resolveJvm(null));
    }

    @Test
    void serverKeyIsFilesystemSafe() {
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.port = 16261;
        assertEquals("play.example.org_16261", profile.serverKey());
        profile.host = "fe80::1%eth0";
        assertEquals("fe80__1_eth0_16261", profile.serverKey());
    }
}
