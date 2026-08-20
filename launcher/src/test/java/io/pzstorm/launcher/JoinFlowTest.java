package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinFlowTest {

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("storm.launcher.zomboidDir");
    }

    /** A stored-form account password, as {@link ServerStore} guarantees by the time joins run. */
    private static final String HASHED_PASSWORD =
            PzPasswordHash.GAME_SALT + "0123456789012345678901234567890";

    private ServerProfile profile() {
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.username = "gus";
        profile.accountPassword = HASHED_PASSWORD;
        profile.serverPassword = "spw";
        profile.autoConnect = true;
        return profile;
    }

    /** A config pinned at a packaged-layout Storm install whose lib jar carries this version. */
    private LauncherConfig configWithStorm(String fullVersion) throws IOException {
        Path modRoot = tmp.resolve("storm-" + fullVersion);
        Path bootstrap = modRoot.resolve("bootstrap");
        Files.createDirectories(bootstrap);
        Files.write(bootstrap.resolve("storm-bootstrap.jar"), new byte[] {0x50, 0x4b});
        Path lib = modRoot.resolve("42").resolve("lib");
        Files.createDirectories(lib);
        Files.createFile(lib.resolve("storm-" + fullVersion + ".jar"));
        LauncherConfig config = new LauncherConfig();
        config.bootstrapDir = bootstrap.toString();
        return config;
    }

    /** A config whose bootstrap does not resolve at all — a vanilla client. */
    private LauncherConfig configWithoutStorm() {
        LauncherConfig config = new LauncherConfig();
        config.bootstrapDir = tmp.resolve("nope").toString();
        return config;
    }

    @Test
    void writesHandoffAsJavaProperties() throws IOException {
        ServerProfile profile = profile();
        // Properties escaping must survive what the old key=value lines could not; the server
        // access password is the one field that stays raw
        profile.serverPassword = "se=cret\nwith:newline";
        assertTrue(JoinFlow.writeAutoJoinHandoff(profile));

        Properties read = new Properties();
        try (Reader reader =
                Files.newBufferedReader(LauncherPaths.autoJoinFile(), StandardCharsets.UTF_8)) {
            read.load(reader);
        }
        assertEquals("play.example.org", read.getProperty("host"));
        assertEquals("16261", read.getProperty("port"));
        assertEquals("gus", read.getProperty("username"));
        assertEquals(HASHED_PASSWORD, read.getProperty("password"));
        assertEquals("se=cret\nwith:newline", read.getProperty("serverPassword"));

        JoinFlow.clearAutoJoinHandoff();
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }

    @Test
    void prepareFallsBackWhenThePasswordIsNotInStoredForm() throws IOException {
        // a raw password would be submitted unhashed and fail auth; the popup fallback hashes
        LauncherConfig config = configWithStorm("42.20.2_2.5.1-SNAPSHOT");
        ServerProfile raw = profile();
        raw.accountPassword = "my-actual-password";
        assertFalse(JoinFlow.prepareAutoJoin(config, raw));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }

    @Test
    void prepareArmsOnlyWithAutoConnectAndUsername() throws IOException {
        LauncherConfig config = configWithStorm("42.20.2_2.5.1-SNAPSHOT");
        assertTrue(JoinFlow.prepareAutoJoin(config, profile()));
        assertTrue(Files.exists(LauncherPaths.autoJoinFile()));

        ServerProfile off = profile();
        off.autoConnect = false;
        assertFalse(JoinFlow.prepareAutoJoin(config, off));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()), "disarming must clear the handoff");

        ServerProfile anonymous = profile();
        anonymous.username = "";
        assertFalse(JoinFlow.prepareAutoJoin(config, anonymous));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }

    @Test
    void prepareFallsBackWhenClientStormPredatesIntegration() throws IOException {
        // seed a stale handoff: the gate must clear it, or an old-Storm client would
        // consume it on some unrelated later launch
        assertTrue(JoinFlow.writeAutoJoinHandoff(profile()));

        LauncherConfig old = configWithStorm("42.20.2_2.5.0-SNAPSHOT");
        assertFalse(JoinFlow.prepareAutoJoin(old, profile()));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));

        assertFalse(JoinFlow.prepareAutoJoin(configWithoutStorm(), profile()));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }

    @Test
    void cdnCoreOverridesItemJarOnlyWhenStrictlyNewerAndNotSnapshot() {
        assertEquals("42.20.3_2.6.11", JoinFlow.withCdnCore("42.20.3_2.6.0", "2.6.11"));
        assertEquals("42.20.3_2.6.11", JoinFlow.withCdnCore("42.20.3_2.6.11", "2.6.11"));
        assertEquals("42.20.3_2.6.12", JoinFlow.withCdnCore("42.20.3_2.6.12", "2.6.11"));
        assertEquals(
                "42.20.3_2.6.0-SNAPSHOT", JoinFlow.withCdnCore("42.20.3_2.6.0-SNAPSHOT", "2.6.11"));
        assertEquals("42.20.3_2.6.0", JoinFlow.withCdnCore("42.20.3_2.6.0", null));
        assertEquals("garbage", JoinFlow.withCdnCore("garbage", "2.6.11"));
        assertNull(JoinFlow.withCdnCore(null, "2.6.11"));
    }

    @Test
    void readsLocalStormVersionFromLibJarName() throws IOException {
        assertEquals(
                "42.20.2_2.5.1-SNAPSHOT",
                JoinFlow.localStormVersion(configWithStorm("42.20.2_2.5.1-SNAPSHOT")));
        assertNull(JoinFlow.localStormVersion(configWithoutStorm()));
    }

    @Test
    void supportsLauncherIntegrationComparesTheStormSegment() {
        assertFalse(JoinFlow.supportsLauncherIntegration(null), "no Storm: only +connect works");
        assertFalse(JoinFlow.supportsLauncherIntegration("42.20.2_2.5.0-SNAPSHOT"));
        assertFalse(JoinFlow.supportsLauncherIntegration("42.20.2_2.5"));
        assertFalse(JoinFlow.supportsLauncherIntegration("42.20.2_2.5.0.9"));

        assertTrue(JoinFlow.supportsLauncherIntegration("42.20.2_2.5.1-SNAPSHOT"));
        assertTrue(JoinFlow.supportsLauncherIntegration("42.20.2_2.5.1"));
        assertTrue(JoinFlow.supportsLauncherIntegration("42.20.2_2.6.0"));
        assertTrue(JoinFlow.supportsLauncherIntegration("42.20.3_3.0"));

        // hand-built dev Storms postdate the integration; give them the benefit of the doubt
        assertTrue(JoinFlow.supportsLauncherIntegration("42.20.2_custom"));
        assertTrue(JoinFlow.supportsLauncherIntegration("dev-build"));
    }
}
