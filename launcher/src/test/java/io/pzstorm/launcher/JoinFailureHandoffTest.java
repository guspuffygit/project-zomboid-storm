package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JoinFailureHandoffTest {

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("storm.launcher.zomboidDir");
    }

    private void write(long timestampMs, String relPath, String absPath) throws IOException {
        Properties props = new Properties();
        props.setProperty("timestampMs", Long.toString(timestampMs));
        props.setProperty("reason", "File doesn't match the one on the server");
        props.setProperty("relPath", relPath);
        props.setProperty("absPath", absPath);
        props.setProperty("server", "40.160.20.9:16261");
        Files.createDirectories(JoinFailureHandoff.file().getParent());
        try (Writer writer =
                Files.newBufferedWriter(JoinFailureHandoff.file(), StandardCharsets.UTF_8)) {
            props.store(writer, null);
        }
    }

    @Test
    void readsWhatTheStormClientWrote() throws IOException {
        write(
                1756200000000L,
                "media/lua/client/vro_partlists.lua",
                "C:\\Steam\\steamapps\\workshop\\content\\108600\\2757712197\\mods\\VRO"
                        + "\\common\\media\\lua\\client\\vro_partlists.lua");

        JoinFailureHandoff handoff = JoinFailureHandoff.read();

        assertEquals(1756200000000L, handoff.timestampMs);
        assertEquals("media/lua/client/vro_partlists.lua", handoff.relPath);
        assertEquals("2757712197", handoff.workshopItemId());
        assertFalse(handoff.expired(1756200000000L + JoinFailureHandoff.MAX_AGE_MS - 1));
        assertTrue(handoff.expired(1756200000000L + JoinFailureHandoff.MAX_AGE_MS + 1));
    }

    @Test
    void itemIdParsesLinuxPathsToo() throws IOException {
        write(1L, "media/scripts/x.txt", "/home/p/.steam/steamapps/workshop/content/108600/42/x");

        assertEquals("42", JoinFailureHandoff.read().workshopItemId());
    }

    @Test
    void pathsOutsideWorkshopContentHaveNoItem() throws IOException {
        write(1L, "media/lua/shared/a.lua", "C:\\Games\\ProjectZomboid\\media\\lua\\shared\\a.lua");

        assertNull(JoinFailureHandoff.read().workshopItemId());
    }

    @Test
    void gameOwnedFilesAreToldApartFromModFiles() throws IOException {
        Path gameDir = tmp.resolve("SteamLibrary").resolve("ProjectZomboid");
        write(
                1L,
                "media/lua/shared/timedactions/isreadabook.lua",
                gameDir.resolve("media/lua/shared/TimedActions/ISReadABook.lua").toString());

        JoinFailureHandoff handoff = JoinFailureHandoff.read();

        assertNull(handoff.workshopItemId());
        assertTrue(handoff.insideGameInstall(gameDir));
        assertFalse(handoff.insideGameInstall(tmp.resolve("SteamLibrary").resolve("Other")));
        assertFalse(handoff.insideGameInstall(null));
    }

    @Test
    void absentFileReadsNullAndDeleteIsIdempotent() {
        assertNull(JoinFailureHandoff.read());
        JoinFailureHandoff.delete();
    }

    @Test
    void deleteConsumesTheRecord() throws IOException {
        write(1L, "a", "b");
        JoinFailureHandoff.delete();

        assertNull(JoinFailureHandoff.read());
    }
}
