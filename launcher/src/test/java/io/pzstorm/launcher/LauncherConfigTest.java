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
    void workshopBootstrapWinsOverLocalDev() throws IOException {
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
        try {
            String id = LauncherInfo.workshopIds().get(0);
            Path gameDir =
                    tmp.resolve(Path.of("SteamLibrary", "steamapps", "common", "ProjectZomboid"));
            Files.createDirectories(gameDir);
            Path workshopBootstrap =
                    tmp.resolve(
                            Path.of(
                                    "SteamLibrary",
                                    "steamapps",
                                    "workshop",
                                    "content",
                                    "108600",
                                    id,
                                    "mods",
                                    "storm",
                                    "bootstrap"));
            Files.createDirectories(workshopBootstrap);
            Files.write(workshopBootstrap.resolve("storm-bootstrap.jar"), new byte[] {1});
            Path localDev = LauncherConfig.localDevBootstrap();
            Files.createDirectories(localDev);
            Files.write(localDev.resolve("storm-bootstrap.jar"), new byte[] {1});

            LauncherConfig config = new LauncherConfig();
            Path resolved = config.resolveBootstrapDir(gameDir);
            assertEquals(workshopBootstrap, resolved);
            assertTrue(!LauncherConfig.isLocalDevBootstrap(resolved));
            assertEquals(id, config.stormWorkshopItemId(gameDir));

            // remove the workshop item: local dev is the fallback, self-update targets prod
            Files.delete(workshopBootstrap.resolve("storm-bootstrap.jar"));
            resolved = config.resolveBootstrapDir(gameDir);
            assertEquals(localDev, resolved);
            assertTrue(LauncherConfig.isLocalDevBootstrap(resolved));
            assertEquals(LauncherInfo.workshopIds().get(0), config.stormWorkshopItemId(gameDir));
        } finally {
            System.clearProperty("storm.launcher.zomboidDir");
        }
    }

    @Test
    void nestedLinuxDepotLayoutIsDetected() throws IOException {
        Path depot = tmp.resolve(Path.of("SteamLibrary", "steamapps", "common", "ProjectZomboid"));
        Path nested = depot.resolve("projectzomboid");
        Files.createDirectories(nested);
        Files.write(nested.resolve("ProjectZomboid64.json"), "{}".getBytes());

        LauncherConfig config = new LauncherConfig();
        config.gameDir = depot.toString();
        assertEquals(nested, config.resolveGameDir());

        // the steamapps walk-up still finds the workshop item from the nested dir
        String id = LauncherInfo.workshopIds().get(0);
        Path workshopBootstrap =
                tmp.resolve(
                        Path.of(
                                "SteamLibrary",
                                "steamapps",
                                "workshop",
                                "content",
                                "108600",
                                id,
                                "mods",
                                "storm",
                                "bootstrap"));
        Files.createDirectories(workshopBootstrap);
        Files.write(workshopBootstrap.resolve("storm-bootstrap.jar"), new byte[] {1});
        assertEquals(workshopBootstrap, config.resolveBootstrapDir(nested));
    }

    @Test
    void explicitCustomBootstrapDisablesSelfUpdate() throws IOException {
        Path custom = tmp.resolve("custom-bootstrap");
        Files.createDirectories(custom);
        Files.write(custom.resolve("storm-bootstrap.jar"), new byte[] {1});
        LauncherConfig config = new LauncherConfig();
        config.bootstrapDir = custom.toString();
        assertNull(config.stormWorkshopItemId(null));
    }

    @Test
    void workshopItemIdOfParsesContentPaths() {
        assertEquals(
                "3670772371",
                LauncherConfig.workshopItemIdOf(
                        Path.of(
                                "/mnt/e/SteamLibrary/steamapps/workshop/content/108600",
                                "3670772371/mods/storm/launcher/storm-launcher.jar")));
        assertNull(LauncherConfig.workshopItemIdOf(Path.of("/opt/storm/bootstrap")));
        assertNull(LauncherConfig.workshopItemIdOf(null));
    }

    @Test
    void clientPerfFixesPersistAndDefaultOn() throws IOException {
        LauncherConfig config = new LauncherConfig();
        assertTrue(config.clientPerfFixes);
        config.clientPerfFixes = false;
        Path file = tmp.resolve("launcher.json");
        config.save(file);
        assertTrue(!LauncherConfig.load(file).clientPerfFixes);
        assertTrue(LauncherConfig.load(tmp.resolve("missing.json")).clientPerfFixes);
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
