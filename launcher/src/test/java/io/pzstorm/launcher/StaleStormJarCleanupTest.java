package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaleStormJarCleanupTest {

    @TempDir Path tmp;

    private Path libDir() throws IOException {
        Path libDir = tmp.resolve("mods").resolve("storm").resolve("42").resolve("lib");
        Files.createDirectories(libDir);
        return libDir;
    }

    private Path bootstrapDir() throws IOException {
        Path bootstrapDir = tmp.resolve("mods").resolve("storm").resolve("bootstrap");
        Files.createDirectories(bootstrapDir);
        return bootstrapDir;
    }

    private Path jar(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), new byte[] {0x50, 0x4b});
    }

    @Test
    void deletesStaleSnapshotLeftBehindNextToCurrentRelease() throws IOException {
        Path libDir = libDir();
        Path stale = jar(libDir, "storm-42.19.0_2.3.1-SNAPSHOT.jar");
        Path current = jar(libDir, "storm-42.20.2_2.5.5.jar");
        Path other = jar(libDir, "byte-buddy-1.18.4.jar");

        StaleStormJarCleanup.run(bootstrapDir());

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(current));
        assertTrue(Files.exists(other));
    }

    @Test
    void deletesEveryOlderJarKeepingOnlyTheNewest() throws IOException {
        Path libDir = libDir();
        Path oldest = jar(libDir, "storm-42.18.0_2.1.0.jar");
        Path older = jar(libDir, "storm-42.20.2_2.5.4.jar");
        Path current = jar(libDir, "storm-42.20.2_2.5.5.jar");

        StaleStormJarCleanup.run(bootstrapDir());

        assertFalse(Files.exists(oldest));
        assertFalse(Files.exists(older));
        assertTrue(Files.exists(current));
    }

    @Test
    void snapshotLosesToReleaseAtTheSameVersion() throws IOException {
        Path libDir = libDir();
        Path snapshot = jar(libDir, "storm-42.20.2_2.5.5-SNAPSHOT.jar");
        Path release = jar(libDir, "storm-42.20.2_2.5.5.jar");

        StaleStormJarCleanup.run(bootstrapDir());

        assertFalse(Files.exists(snapshot));
        assertTrue(Files.exists(release));
    }

    @Test
    void higherPzVersionWinsEvenWithLowerStormVersion() throws IOException {
        Path libDir = libDir();
        Path oldPz = jar(libDir, "storm-42.19.0_9.9.9.jar");
        Path newPz = jar(libDir, "storm-42.20.2_2.5.5.jar");

        StaleStormJarCleanup.run(bootstrapDir());

        assertFalse(Files.exists(oldPz));
        assertTrue(Files.exists(newPz));
    }

    @Test
    void neverDeletesUnparseableNamesOrTheBootstrapJar() throws IOException {
        Path libDir = libDir();
        Path unparseable = jar(libDir, "storm-custom-build.jar");
        Path bootstrapJar = jar(libDir, "storm-bootstrap.jar");
        Path current = jar(libDir, "storm-42.20.2_2.5.5.jar");

        StaleStormJarCleanup.run(bootstrapDir());

        assertTrue(Files.exists(unparseable));
        assertTrue(Files.exists(bootstrapJar));
        assertTrue(Files.exists(current));
    }

    @Test
    void singleJarAndMissingLibDirAreNoOps() throws IOException {
        Path libDir = libDir();
        Path only = jar(libDir, "storm-42.20.2_2.5.5.jar");
        StaleStormJarCleanup.run(bootstrapDir());
        assertTrue(Files.exists(only));

        StaleStormJarCleanup.run(tmp.resolve("nowhere").resolve("bootstrap"));
        StaleStormJarCleanup.run(null);
    }

    @Test
    void libDirIsSiblingOfBootstrapUnderTheVersionDir() throws IOException {
        assertEquals(
                tmp.resolve("mods").resolve("storm").resolve("42").resolve("lib"),
                StaleStormJarCleanup.libDirOf(bootstrapDir()));
        assertNull(StaleStormJarCleanup.libDirOf(null));
    }

    @Test
    void staleSelectionOnlyRanksParseableNames() {
        Path stale = Path.of("storm-42.19.0_2.3.1-SNAPSHOT.jar");
        Path current = Path.of("storm-42.20.2_2.5.5.jar");
        Path unparseable = Path.of("storm-custom.jar");
        assertEquals(
                List.of(stale),
                StaleStormJarCleanup.staleJars(List.of(stale, current, unparseable)));
        assertEquals(List.of(), StaleStormJarCleanup.staleJars(List.of(unparseable)));
        assertEquals(List.of(), StaleStormJarCleanup.staleJars(List.of()));
    }
}
