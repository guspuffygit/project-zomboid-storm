package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameLaunchTest {

    /** Wired in from the gitignored local.properties by launcher/build.gradle. */
    private static final String ZOMBOID_DIR = System.getProperty("storm.test.zomboidDir", "");

    @TempDir Path tmp;

    private Path gameDir;
    private Path bootstrapDir;

    @BeforeEach
    void setUp() throws IOException {
        gameDir = tmp.resolve("game");
        Files.createDirectories(gameDir);
        Files.write(
                gameDir.resolve("ProjectZomboid64.json"),
                ("{"
                                + "\"mainClass\": \"zombie/gameStates/MainScreenState\","
                                + "\"classpath\": [\".\", \"projectzomboid.jar\"],"
                                + "\"vmArgs\": [\"-Dzomboid.steam=1\"]}")
                        .getBytes());
        bootstrapDir = tmp.resolve("bootstrap");
        Files.createDirectories(bootstrapDir);
        Files.write(bootstrapDir.resolve("storm-bootstrap.jar"), new byte[] {0x50, 0x4b});
        // keep the launcher's Zomboid dir inside the sandbox
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("storm.launcher.zomboidDir");
    }

    private LauncherConfig config() {
        LauncherConfig config = new LauncherConfig();
        config.gameDir = gameDir.toString();
        config.jvmPath = tmp.resolve("jvm/bin/java").toString();
        config.bootstrapDir = bootstrapDir.toString();
        config.globalVmArgs.add("-Xmx16g");
        return config;
    }

    @Test
    void buildsJoinCommand() throws IOException {
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.serverPassword = "sekrit";
        profile.extraVmArgs.add("-Dstorm.http.port=8089");

        Path modsDir = tmp.resolve("mods/play.example.org_16261");
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config(), profile, modsDir);

        List<String> cmd = plan.command;
        assertEquals(config().jvmPath, cmd.get(0));
        assertTrue(plan.warnings.isEmpty(), plan.warnings.toString());
        assertEquals(gameDir, plan.workingDir);
        assertTrue(cmd.contains("-Dzomboid.steam=1"));
        assertTrue(cmd.contains("-javaagent:" + bootstrapDir.resolve("storm-bootstrap.jar")));
        assertTrue(
                cmd.contains("-D" + GameLaunch.MODS_DIR_PROPERTY + "=" + modsDir.toAbsolutePath()));
        assertTrue(cmd.contains("-Xmx16g"));
        assertTrue(cmd.contains("-Dstorm.http.port=8089"));
        assertTrue(cmd.contains("zombie.gameStates.MainScreenState"));

        int mainIdx = cmd.indexOf("zombie.gameStates.MainScreenState");
        int connectIdx = cmd.indexOf("+connect");
        assertTrue(connectIdx > mainIdx, "+connect must be a program arg, not a JVM arg");
        assertEquals("play.example.org:16261", cmd.get(connectIdx + 1));
        assertEquals("sekrit", cmd.get(cmd.indexOf("+password") + 1));

        int cpIdx = cmd.indexOf("-cp");
        assertTrue(cpIdx >= 0 && cpIdx + 1 < cmd.size());
        String sep = GameLaunch.isWindowsJvm(tmp.resolve("jvm/bin/java")) ? ";" : ":";
        assertEquals("." + sep + "projectzomboid.jar", cmd.get(cpIdx + 1));

        String described = GameLaunch.describe(plan);
        assertFalse(described.contains("sekrit"), "password must be masked in logs");
        assertTrue(described.contains("+password *****"));
    }

    @Test
    void launchWithoutServerHasNoConnectArgs() throws IOException {
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config(), null, null);
        assertFalse(plan.command.contains("+connect"));
        assertFalse(plan.command.contains("-D" + GameLaunch.MODS_DIR_PROPERTY));
        assertTrue(plan.command.contains("zombie.gameStates.MainScreenState"));
    }

    @Test
    void missingBootstrapWarnsAndLaunchesVanilla() throws IOException {
        LauncherConfig config = config();
        config.bootstrapDir = tmp.resolve("nope").toString();
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config, null, null);
        assertEquals(1, plan.warnings.size());
        assertTrue(plan.command.stream().noneMatch(a -> a.startsWith("-javaagent")));
    }

    @Test
    void armedAutoJoinPassesHandoffPathAndSuppressesConnectArgs() throws IOException {
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.serverPassword = "sekrit";

        Path handoff = LauncherPaths.autoJoinFile();
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config(), profile, null, handoff);

        assertTrue(
                plan.command.contains(
                        "-D" + GameLaunch.AUTOJOIN_FILE_PROPERTY + "=" + handoff.toAbsolutePath()),
                plan.command.toString());
        assertFalse(plan.command.contains("+connect"), "auto-join must suppress +connect");
        assertFalse(plan.command.contains("+password"), "auto-join must suppress +password");
    }

    @Test
    void clientPerfFixesDefaultOnAndOverridable() throws IOException {
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config(), null, null);
        assertTrue(plan.command.contains("-D" + GameLaunch.CLIENT_PERF_PROPERTY + "=true"));

        LauncherConfig off = config();
        off.clientPerfFixes = false;
        assertTrue(
                GameLaunch.plan(off, null, null).command.stream()
                        .noneMatch(a -> a.startsWith("-D" + GameLaunch.CLIENT_PERF_PROPERTY)));

        LauncherConfig userOverride = config();
        userOverride.globalVmArgs.add("-D" + GameLaunch.CLIENT_PERF_PROPERTY + "=false");
        List<String> cmd = GameLaunch.plan(userOverride, null, null).command;
        assertFalse(cmd.contains("-D" + GameLaunch.CLIENT_PERF_PROPERTY + "=true"));
        assertTrue(cmd.contains("-D" + GameLaunch.CLIENT_PERF_PROPERTY + "=false"));
    }

    @Test
    void missingBootstrapOmitsClientPerfFlag() throws IOException {
        LauncherConfig config = config();
        config.bootstrapDir = tmp.resolve("nope").toString();
        assertTrue(
                GameLaunch.plan(config, null, null).command.stream()
                        .noneMatch(a -> a.startsWith("-D" + GameLaunch.CLIENT_PERF_PROPERTY)));
    }

    @Test
    void nativeEnvironmentMirrorsVanillaLauncherOnLinux() throws IOException {
        assumeTrue(
                System.getProperty("os.name", "").toLowerCase().contains("linux"),
                "linux loader-path conventions");
        Files.createDirectories(gameDir.resolve("linux64"));
        Files.createDirectories(gameDir.resolve(Path.of("jre64", "lib")));
        Files.write(gameDir.resolve(Path.of("jre64", "lib", "libjsig.so")), new byte[] {1});
        Files.write(gameDir.resolve("libPZXInitThreads64.so"), new byte[] {1});

        Map<String, String> env = GameLaunch.nativeEnvironment(gameDir, false);
        assertTrue(env.get("LD_LIBRARY_PATH").contains(gameDir.resolve("linux64").toString()));
        assertTrue(env.get("LD_LIBRARY_PATH").contains(gameDir.toString()));
        assertTrue(env.get("LD_PRELOAD").contains("libjsig.so"));
        assertTrue(env.get("LD_PRELOAD").contains("libPZXInitThreads64.so"));

        assertTrue(
                GameLaunch.nativeEnvironment(gameDir, true).isEmpty(),
                "windows targets need no loader env");
    }

    @Test
    void translatesWslPathsOnlyForWindowsJvms() {
        assertEquals(
                "C:\\Zomboid\\storm-launcher.jar",
                GameLaunch.pathArgFor(
                        Path.of("jre64\\bin\\java.exe"),
                        Path.of("/mnt/c/Zomboid/storm-launcher.jar")));
        // non-windows JVM: pass through untouched
        assertEquals(
                "/mnt/c/x.jar",
                GameLaunch.pathArgFor(Path.of("/usr/bin/java"), Path.of("/mnt/c/x.jar")));
        // windows JVM with a native windows path: untouched
        assertEquals("C:\\x.jar", GameLaunch.pathArgFor(Path.of("java.exe"), Path.of("C:\\x.jar")));
    }

    @Test
    void translatesTheConfiguredZomboidDirForWindowsJvms() {
        Path zomboidDir = Path.of(ZOMBOID_DIR);
        assumeTrue(
                ZOMBOID_DIR.startsWith("/mnt/") && zomboidDir.getNameCount() > 1,
                "local.properties zomboidDir is unset or is not a WSL /mnt path");

        String drive = zomboidDir.getName(1).toString().toUpperCase();
        String arg =
                GameLaunch.pathArgFor(
                        Path.of("jre64\\bin\\java.exe"), zomboidDir.resolve("storm-launcher.jar"));

        assertTrue(arg.startsWith(drive + ":\\"), arg);
        assertFalse(arg.contains("/"), arg);
        assertTrue(arg.endsWith("\\storm-launcher.jar"), arg);
    }

    @Test
    void exeJvmUsesWindowsConventionsEvenFromLinuxHost() throws IOException {
        LauncherConfig config = config();
        config.jvmPath = tmp.resolve("jre64\\bin\\javaw.exe").toString();
        Files.write(
                gameDir.resolve("ProjectZomboid64.json"),
                ("{"
                                + "\"mainClass\": \"zombie/gameStates/MainScreenState\","
                                + "\"classpath\": [\".\", \"projectzomboid.jar\"],"
                                + "\"vmArgs\": [\"-Dzomboid.steam=1\"],"
                                + "\"windows\": {\"10.0.17134\": {\"vmArgs\": [\"-XX:+UseZGC\"]}}}")
                        .getBytes());
        GameLaunch.LaunchPlan plan = GameLaunch.plan(config, null, null);
        String cp = plan.command.get(plan.command.indexOf("-cp") + 1);
        assertEquals(".;projectzomboid.jar", cp);
        assertTrue(
                plan.command.contains("-XX:+UseZGC"),
                "windows overlay must follow the target JVM: " + plan.command);
    }
}
