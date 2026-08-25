package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StormServerModDirsTest {

    @TempDir Path tmp;

    private Path modDir(Path itemDir, String modName, String infoSubdir, String... infoLines)
            throws IOException {
        Path modDir = itemDir.resolve("mods").resolve(modName);
        Path infoDir = infoSubdir.isEmpty() ? modDir : modDir.resolve(infoSubdir);
        Files.createDirectories(infoDir);
        Files.write(
                infoDir.resolve("mod.info"),
                String.join("\n", infoLines).getBytes(StandardCharsets.UTF_8));
        return modDir;
    }

    @Test
    void readsIdFromCommonModInfo() throws IOException {
        Path mod = modDir(tmp, "TrueActionsDancing", "common", "name=TAD", "id=TrueActionsDancing");
        assertEquals("TrueActionsDancing", StormServerModDirs.readModId(mod));
    }

    @Test
    void readsIdFromVersionDirModInfo() throws IOException {
        Path mod = modDir(tmp, "SomeMod", "42.9", "id=SomeMod42");
        assertEquals("SomeMod42", StormServerModDirs.readModId(mod));
    }

    @Test
    void readsIdFromB41RootModInfo() throws IOException {
        Path mod = modDir(tmp, "OldMod", "", "id=OldMod");
        assertEquals("OldMod", StormServerModDirs.readModId(mod));
    }

    @Test
    void ignoresBomAndTrimsId() throws IOException {
        Path mod = modDir(tmp, "BomMod", "common", "\uFEFFid=BomMod  ");
        assertEquals("BomMod", StormServerModDirs.readModId(mod));
    }

    @Test
    void ignoresLinesMerelyContainingId() throws IOException {
        // vanilla only honors lines that START with id= — description=... id=Wrong must not match
        Path mod = modDir(tmp, "Tricky", "common", "description=has id=Wrong inside", "id=Right");
        assertEquals("Right", StormServerModDirs.readModId(mod));
    }

    @Test
    void missingOrIdlessModInfoYieldsNull() throws IOException {
        Path noInfo = tmp.resolve("mods").resolve("Empty");
        Files.createDirectories(noInfo);
        assertNull(StormServerModDirs.readModId(noInfo));

        Path noId = modDir(tmp, "NoId", "common", "name=NoId");
        assertNull(StormServerModDirs.readModId(noId));
    }

    @Test
    void scanMapsEveryModUnderEveryItem() throws IOException {
        Path itemA = tmp.resolve("3650071729");
        Path itemB = tmp.resolve("2392709985");
        Path tad = modDir(itemA, "TrueActionsDancing", "common", "id=TrueActionsDancing");
        Path tsar = modDir(itemB, "tsarslib", "common", "id=tsarslib");

        Map<String, String> dirs = StormServerModDirs.scanItems(List.of(itemA, itemB));

        assertEquals(2, dirs.size());
        assertEquals(tad.toAbsolutePath().toString(), dirs.get("TrueActionsDancing"));
        assertEquals(tsar.toAbsolutePath().toString(), dirs.get("tsarslib"));
    }

    @Test
    void firstItemWinsDuplicateModId() throws IOException {
        Path itemA = tmp.resolve("111");
        Path itemB = tmp.resolve("222");
        Path first = modDir(itemA, "Dupe", "common", "id=Dupe");
        modDir(itemB, "Dupe", "common", "id=Dupe");

        Map<String, String> dirs = StormServerModDirs.scanItems(List.of(itemA, itemB));

        assertEquals(first.toAbsolutePath().toString(), dirs.get("Dupe"));
    }

    @Test
    void itemWithoutModsDirIsSkipped() throws IOException {
        Path bare = tmp.resolve("333");
        Files.createDirectories(bare);
        assertTrue(StormServerModDirs.scanItems(List.of(bare)).isEmpty());
    }
}
