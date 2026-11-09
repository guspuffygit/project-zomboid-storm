package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LauncherMainTest {

    @Test
    void steamInterposedGameCommandOpensTheUiInsteadOfErroring() {
        // "<bat> %command%": Steam appends the vanilla game command — UI mode, args dropped
        assertArrayEquals(
                new String[0],
                LauncherMain.effectiveArgs(
                        new String[] {
                            "E:\\SteamLibrary\\steamapps\\common\\ProjectZomboid\\ProjectZomboid64.exe",
                            "-pzexeconfig",
                            "ProjectZomboid64.json"
                        }));
        // real flags pass through untouched, including a trailing interposed command
        String[] join = {"--join", "myserver", "E:\\...\\ProjectZomboid64.exe"};
        assertArrayEquals(join, LauncherMain.effectiveArgs(join));
        assertArrayEquals(new String[0], LauncherMain.effectiveArgs(new String[0]));
    }

    @Test
    void findsProfileByNameAddressOrAdHoc() {
        LauncherConfig config = new LauncherConfig();
        ServerProfile profile = new ServerProfile();
        profile.name = "ATF";
        profile.host = "play.example.org";
        profile.port = 16261;
        config.servers.add(profile);

        assertEquals(profile, LauncherMain.findProfile(config, "atf"));
        assertEquals(profile, LauncherMain.findProfile(config, "play.example.org:16261"));

        ServerProfile adHoc = LauncherMain.findProfile(config, "10.1.2.3:16777");
        assertEquals("10.1.2.3", adHoc.host);
        assertEquals(16777, adHoc.port);

        assertNull(LauncherMain.findProfile(config, "unknown"));
        assertNull(LauncherMain.findProfile(config, "host:notaport"));
    }

    @Test
    void usernamePicksAmongProfilesSharingAServer() {
        LauncherConfig config = new LauncherConfig();
        ServerProfile alt = new ServerProfile();
        alt.name = "ATF";
        alt.host = "play.example.org";
        alt.username = "Alt Char";
        ServerProfile main = new ServerProfile();
        main.name = "ATF";
        main.host = "play.example.org";
        main.username = "Main Char";
        config.servers.add(alt);
        config.servers.add(main);

        assertEquals(alt, LauncherMain.findProfile(config, "atf"));
        assertEquals(main, LauncherMain.findProfile(config, "atf", "main char"));
        assertEquals(main, LauncherMain.findProfile(config, "play.example.org:16261", "Main Char"));

        ServerProfile adHoc = LauncherMain.findProfile(config, "play.example.org:16261", "New");
        assertEquals("New", adHoc.username);
        assertEquals("play.example.org", adHoc.host);
        assertTrue(adHoc.autoConnect);
        assertNull(LauncherMain.findProfile(config, "atf", "New"));
    }
}
