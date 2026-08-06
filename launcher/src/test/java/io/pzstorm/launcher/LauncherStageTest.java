package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherStageTest {

    @TempDir Path tmp;

    @BeforeEach
    void setUp() {
        // keep the launcher's Zomboid dir (and so the stage dir) inside the sandbox
        System.setProperty("storm.launcher.zomboidDir", tmp.resolve("Zomboid").toString());
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("storm.launcher.zomboidDir");
    }

    @Test
    void parseStripsStageFlagsAndKeepsRemainingArgs() {
        LauncherStage.Context ctx =
                LauncherStage.parse(
                        new String[] {
                            "--staged-from=/items/x/storm-launcher.jar",
                            "--join",
                            "--parent-pid=4242",
                            "atf",
                            "--stage-hop=2",
                        });
        assertArrayEquals(new String[] {"--join", "atf"}, ctx.args);
        assertTrue(ctx.staged());
        assertEquals("storm-launcher.jar", ctx.stagedFrom.getFileName().toString());
        assertEquals(4242, ctx.parentPid);
        assertEquals(2, ctx.hop);
    }

    @Test
    void parseDefaultsWithoutFlags() {
        LauncherStage.Context ctx = LauncherStage.parse(new String[] {"--list"});
        assertArrayEquals(new String[] {"--list"}, ctx.args);
        assertFalse(ctx.staged());
        assertNull(ctx.stagedFrom);
        assertEquals(-1, ctx.parentPid);
        assertEquals(0, ctx.hop);
    }

    @Test
    void parseToleratesMalformedValues() {
        LauncherStage.Context ctx =
                LauncherStage.parse(
                        new String[] {"--staged-from=", "--parent-pid=oops", "--stage-hop=NaN"});
        assertFalse(ctx.staged());
        assertEquals(-1, ctx.parentPid);
        assertEquals(0, ctx.hop);
        assertEquals(0, ctx.args.length);
    }

    @Test
    void stageIsContentAddressedAndIdempotent() throws IOException {
        Path jar = fakeJar("item", "launcher v1".getBytes());
        Path staged = LauncherStage.stage(jar);

        String fingerprint = Sha256.of(jar).substring(0, 16);
        assertEquals(LauncherPaths.stageDir().resolve(fingerprint), staged.getParent());
        assertEquals(LauncherStage.JAR_NAME, staged.getFileName().toString());
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(staged));

        assertEquals(staged, LauncherStage.stage(jar));
        try (var entries = Files.list(LauncherPaths.stageDir())) {
            assertEquals(1, entries.count(), "no tmp leftovers, one dir per content hash");
        }
    }

    @Test
    void stageSeparatesDifferentContent() throws IOException {
        Path v1 = fakeJar("v1", "launcher v1".getBytes());
        Path v2 = fakeJar("v2", "launcher v2".getBytes());
        assertNotEquals(LauncherStage.stage(v1), LauncherStage.stage(v2));
    }

    @Test
    void handOffCommandCarriesIdentityHopAndArgv() throws IOException {
        Path itemJar = fakeJar("item", "launcher".getBytes());
        Path stagedJar = LauncherStage.stage(itemJar);
        LauncherStage.Context ctx =
                new LauncherStage.Context(new String[] {"--join", "atf"}, null, -1, 2);

        List<String> command = LauncherStage.handOffCommand(stagedJar, itemJar, ctx);

        int jarFlag = command.indexOf("-jar");
        assertTrue(jarFlag > 0, "spawns a jar");
        assertEquals(stagedJar.toString(), command.get(jarFlag + 1), "runs the staged copy");
        assertTrue(command.contains(LauncherStage.STAGED_FROM_FLAG + itemJar));
        assertTrue(command.contains(LauncherStage.PARENT_PID_FLAG + ProcessHandle.current().pid()));
        assertTrue(command.contains(LauncherStage.HOP_FLAG + 2), "hop forwarded unchanged");
        assertEquals("atf", command.get(command.size() - 1));
        assertEquals("--join", command.get(command.size() - 2));
    }

    @Test
    void restartCommandReentersItemJarWithIncrementedHop() throws IOException {
        Path itemJar = fakeJar("item", "launcher".getBytes());
        LauncherStage.Context ctx =
                new LauncherStage.Context(new String[] {"--join", "atf"}, itemJar, 4242, 1);

        List<String> command = LauncherStage.restartCommand(ctx);

        int jarFlag = command.indexOf("-jar");
        assertEquals(itemJar.toString(), command.get(jarFlag + 1), "re-enters the item's jar");
        assertTrue(command.contains(LauncherStage.HOP_FLAG + 2), "hop incremented");
        for (String arg : command) {
            assertFalse(
                    arg.startsWith(LauncherStage.STAGED_FROM_FLAG),
                    "fresh front-door entry stages itself");
        }
        assertEquals("atf", command.get(command.size() - 1));
    }

    @Test
    void shouldRestartOnlyOnMismatchBelowHopCap() {
        assertFalse(LauncherStage.shouldRestart("same", "same", 0));
        assertTrue(LauncherStage.shouldRestart("item", "own", 0));
        assertTrue(LauncherStage.shouldRestart("item", "own", LauncherStage.MAX_HOPS - 1));
        assertFalse(LauncherStage.shouldRestart("item", "own", LauncherStage.MAX_HOPS));
        assertTrue(LauncherStage.shouldRestart("item", null, 0), "unreadable own jar still hops");
    }

    @Test
    void gcStaleStagesKeepsOnlyTheRunningCopy() throws IOException {
        Path stale = LauncherStage.stage(fakeJar("v1", "launcher v1".getBytes()));
        Path current = LauncherStage.stage(fakeJar("v2", "launcher v2".getBytes()));
        Path tmpLeftover = LauncherPaths.stageDir().resolve(".tmp-crashed");
        Files.createDirectories(tmpLeftover);
        Files.write(tmpLeftover.resolve(LauncherStage.JAR_NAME), "partial".getBytes());

        LauncherStage.gcStaleStages(current);

        assertTrue(Files.isRegularFile(current), "the copy in use survives");
        assertFalse(Files.exists(stale.getParent()), "older versions are swept");
        assertFalse(Files.exists(tmpLeftover), "crashed staging attempts are swept");
    }

    private Path fakeJar(String dir, byte[] bytes) throws IOException {
        Path jarDir = tmp.resolve(dir);
        Files.createDirectories(jarDir);
        Path jar = jarDir.resolve(LauncherStage.JAR_NAME);
        Files.write(jar, bytes);
        return jar;
    }
}
