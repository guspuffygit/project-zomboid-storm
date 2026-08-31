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
        profile.serverPassword = "pw";
        profile.accountPassword = "secret";
        profile.username = "Gus";
        profile.autoConnect = true;
        profile.inGameDb = true;
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
        assertEquals("Gus", p.username);
        assertTrue(p.autoConnect);
        assertTrue(p.updateWorkshopMods);
        assertTrue(p.inGameDb);
        assertEquals(java.util.List.of("-DstormType=local"), p.extraVmArgs);
        // passwords live in the game's saved-server database, never in launcher.json
        assertEquals("", p.serverPassword);
        assertEquals("", p.accountPassword);
    }

    @Test
    void loadIgnoresCredentialsAlreadyPresentInTheFile() throws IOException {
        Path file = tmp.resolve("legacy.json");
        Files.write(
                file,
                ("{\"servers\":[{\"name\":\"ATF\",\"host\":\"play.example.org\","
                                + "\"port\":16261,\"username\":\"Gus\","
                                + "\"serverPassword\":\"pw\",\"accountPassword\":\"secret\"}]}")
                        .getBytes());

        ServerProfile p = LauncherConfig.load(file).servers.get(0);

        assertEquals("Gus", p.username);
        assertEquals("", p.serverPassword);
        assertEquals("", p.accountPassword);
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
    void localDevLauncherPrefersLocalDevBootstrap() throws IOException {
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
            Path localLauncherJar =
                    localDev.getParent().resolve(Path.of("launcher", "storm-launcher.jar"));

            LauncherConfig config = new LauncherConfig();
            config.setStagedOrigin(localLauncherJar);
            Path resolved = config.resolveBootstrapDir(gameDir);
            assertEquals(localDev, resolved);
            assertTrue(LauncherConfig.isLocalDevBootstrap(resolved));

            // a local install missing the bootstrap falls back to the workshop item
            Files.delete(localDev.resolve("storm-bootstrap.jar"));
            assertEquals(workshopBootstrap, config.resolveBootstrapDir(gameDir));
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
    void macAppBundleLayoutIsDetected() throws IOException {
        // the mac depot nests the game payload in the app bundle and ships no ProjectZomboid64.json
        Path depot = tmp.resolve(Path.of("Steam", "steamapps", "common", "ProjectZomboid"));
        Path javaDir = depot.resolve(Path.of("Project Zomboid.app", "Contents", "Java"));
        Files.createDirectories(javaDir);
        Files.write(javaDir.resolve("projectzomboid.jar"), new byte[] {1});

        LauncherConfig config = new LauncherConfig();
        config.gameDir = depot.toString();
        assertEquals(javaDir, config.resolveGameDir());

        // the steamapps walk-up still finds the workshop item from inside the app bundle
        String id = LauncherInfo.workshopIds().get(0);
        Path workshopBootstrap =
                tmp.resolve(
                        Path.of(
                                "Steam",
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
        assertEquals(workshopBootstrap, config.resolveBootstrapDir(javaDir));
    }

    @Test
    void macAppBundleJreIsResolved() throws IOException {
        Path depot = tmp.resolve(Path.of("Steam", "steamapps", "common", "ProjectZomboid"));
        Path contents = depot.resolve(Path.of("Project Zomboid.app", "Contents"));
        Path javaDir = contents.resolve("Java");
        Files.createDirectories(javaDir);
        Files.write(javaDir.resolve("projectzomboid.jar"), new byte[] {1});
        Files.write(contents.resolve("Info.plist"), "<plist/>".getBytes());
        for (String jre : new String[] {"jre-aarch64", "jre-x86_64"}) {
            Path java = contents.resolve(Path.of("PlugIns", jre, "Contents", "Home", "bin"));
            Files.createDirectories(java);
            Files.write(java.resolve("java"), new byte[] {1});
        }

        LauncherConfig config = new LauncherConfig();
        config.gameDir = depot.toString();
        Path jvm = config.resolveJvm(config.resolveGameDir());
        assertTrue(jvm.startsWith(contents.resolve("PlugIns")), jvm.toString());
        assertTrue(Files.isRegularFile(jvm));

        // an explicit jvmPath still wins over the bundled JRE
        config.jvmPath = tmp.resolve("custom/bin/java").toString();
        assertEquals(tmp.resolve("custom/bin/java"), config.resolveJvm(config.resolveGameDir()));
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
    void skipMenusPersistsAndDefaultsOn() throws IOException {
        LauncherConfig config = new LauncherConfig();
        assertTrue(config.skipMenus);
        config.skipMenus = false;
        Path file = tmp.resolve("launcher.json");
        config.save(file);
        assertTrue(!LauncherConfig.load(file).skipMenus);
        assertTrue(LauncherConfig.load(tmp.resolve("missing.json")).skipMenus);
    }

    @Test
    void memorySettingsPersistAndDefaultToAuto() throws IOException {
        LauncherConfig config = new LauncherConfig();
        assertTrue(config.autoMemory);
        assertEquals(8, config.memoryGb);

        config.autoMemory = false;
        config.memoryGb = 12;
        Path file = tmp.resolve("launcher.json");
        config.save(file);
        LauncherConfig loaded = LauncherConfig.load(file);
        assertTrue(!loaded.autoMemory);
        assertEquals(12, loaded.memoryGb);
        assertEquals(12, loaded.resolveMemoryGb());
        assertTrue(LauncherConfig.load(tmp.resolve("missing.json")).autoMemory);

        // out-of-range manual values (hand-edited json) clamp on resolve
        loaded.memoryGb = 2;
        assertEquals(4, loaded.resolveMemoryGb());
        loaded.memoryGb = 99;
        assertEquals(32, loaded.resolveMemoryGb());
    }
}
