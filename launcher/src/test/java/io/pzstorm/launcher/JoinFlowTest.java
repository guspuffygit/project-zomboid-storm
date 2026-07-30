package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private ServerProfile profile() {
        ServerProfile profile = new ServerProfile();
        profile.host = "play.example.org";
        profile.port = 16261;
        profile.username = "gus";
        // Properties escaping must survive what the old key=value lines could not
        profile.accountPassword = "se=cret\nwith:newline";
        profile.serverPassword = "spw";
        profile.autoConnect = true;
        return profile;
    }

    @Test
    void writesHandoffAsJavaProperties() throws IOException {
        assertTrue(JoinFlow.writeAutoJoinHandoff(profile()));

        Properties read = new Properties();
        try (Reader reader =
                Files.newBufferedReader(LauncherPaths.autoJoinFile(), StandardCharsets.UTF_8)) {
            read.load(reader);
        }
        assertEquals("play.example.org", read.getProperty("host"));
        assertEquals("16261", read.getProperty("port"));
        assertEquals("gus", read.getProperty("username"));
        assertEquals("se=cret\nwith:newline", read.getProperty("password"));
        assertEquals("spw", read.getProperty("serverPassword"));

        JoinFlow.clearAutoJoinHandoff();
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }

    @Test
    void prepareArmsOnlyWithAutoConnectAndUsername() {
        assertTrue(JoinFlow.prepareAutoJoin(profile()));
        assertTrue(Files.exists(LauncherPaths.autoJoinFile()));

        ServerProfile off = profile();
        off.autoConnect = false;
        assertFalse(JoinFlow.prepareAutoJoin(off));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()), "disarming must clear the handoff");

        ServerProfile anonymous = profile();
        anonymous.username = "";
        assertFalse(JoinFlow.prepareAutoJoin(anonymous));
        assertFalse(Files.exists(LauncherPaths.autoJoinFile()));
    }
}
