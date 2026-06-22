package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import zombie.core.znet.SteamUtils;

/**
 * Guards against the race between Storm's premain mod-jar load and PZ's dedicated-server workshop
 * sync.
 *
 * <p>Storm catalogs and {@code defineClass}-es mod jars during {@code premain} (via {@link
 * StormBootstrap#loadAndRegisterMods()}), <b>before</b> {@code zombie.network.GameServer.main}
 * calls {@code GameServerWorkshopItems.Install(...)} - which is where Steam actually performs the
 * blocking {@code RemoveFolderForReinstall} + {@code DownloadItem} for any pending workshop
 * updates. If a Storm mod has a pending update at boot time, Storm pins the stale bytes for the
 * JVM's lifetime and Steam then swaps the on-disk jar beneath us.
 *
 * <p>This guard snapshots the workshop mod directories + every jar mtime when Storm catalogs mods.
 * After {@code Install} returns successfully (advised via {@code GameServerWorkshopItemsPatch}),
 * the guard re-scans: if any cataloged jar changed mtime, or any new jar-bearing workshop mod
 * appeared, it logs the affected mods and calls {@code System.exit(0)}. A process supervisor
 * (systemd, Docker {@code restart: always}, a wrapper script, etc.) is expected to bring the JVM
 * back up; the fresh JVM's premain then catalogs the now-updated jars correctly.
 *
 * <p>The check fires after Steam's blocking {@code Install} returns but before {@code
 * ServerWorldDatabase.create} / {@code startServer}, so no client connects to the stale JVM.
 *
 * <p>This guard is server-only and is a no-op under {@code -DstormType=local}: local-dev iteration
 * controls its own jar contents and shouldn't be force-exited by Steam mtime deltas.
 */
public final class StormWorkshopUpdateGuard {

    private static final Map<String, Long> JAR_MTIME_SNAPSHOT = new HashMap<>();
    private static final Set<String> WORKSHOP_MOD_DIR_SNAPSHOT = new HashSet<>();
    private static volatile boolean checked = false;

    private StormWorkshopUpdateGuard() {}

    /**
     * Records the mod directories Storm catalogs from the Steam workshop content folder, along with
     * the {@code lastModified} time of every {@code .jar} reachable from each mod dir. Called from
     * {@link StormModLoader#loadStormMods()} with the Steam workshop content list.
     *
     * <p>No-op on the client (the {@code Install} advice never runs there) and under {@code
     * -DstormType=local} (local jars don't move under Steam's hand).
     */
    public static void snapshotWorkshopMods(List<Path> workshopModDirectories) {
        if (!StormEnv.isStormServer() || StormEnv.isStormLocal()) {
            return;
        }
        for (Path modDir : workshopModDirectories) {
            String absPath = modDir.toAbsolutePath().toString();
            WORKSHOP_MOD_DIR_SNAPSHOT.add(absPath);
            recordJarsUnder(modDir);
        }
        LOGGER.debug(
                "Storm workshop-update guard: snapshotted {} mod dir(s), {} jar(s)",
                WORKSHOP_MOD_DIR_SNAPSHOT.size(),
                JAR_MTIME_SNAPSHOT.size());
    }

    /**
     * Re-stats every snapshotted jar and rescans the workshop content folder for new jar-bearing
     * mods. On any delta, logs the offending mods/jars and calls {@code System.exit(0)} so the
     * supervisor can restart the JVM with the updated jars.
     *
     * <p>Idempotent: a second invocation in the same JVM is a no-op.
     */
    public static synchronized void checkAndExitIfJarsChanged() {
        if (checked) {
            return;
        }
        checked = true;

        if (!StormEnv.isStormServer() || StormEnv.isStormLocal()) {
            return;
        }

        List<String> updatedJars = findUpdatedJars();
        List<String> newJarMods = findNewJarMods();

        if (updatedJars.isEmpty() && newJarMods.isEmpty()) {
            LOGGER.info(
                    "Storm workshop-update guard: no jar-mod changes detected, continuing startup.");
            return;
        }

        emitRestartBanner(updatedJars, newJarMods);
        System.out.flush();
        // Quiesce Steam's native worker threads before the JVM unloads steamclient.so /
        // tier0.so. Without this, Steam IPC threads keep pumping while the JVM shutdown
        // sequence rips out their TLS, producing a SIGSEGV in malloc_consolidate() and a
        // core dump even though the exit is intentional. Mirrors how PZ aborts in
        // GameServer.main's other pre-startServer failure branches (Steam init failure,
        // Steam connect timeout, startServer ConnectException).
        try {
            SteamUtils.shutdown();
        } catch (Throwable t) {
            LOGGER.warn("Storm workshop-update guard: SteamUtils.shutdown() failed", t);
        }
        System.exit(0);
    }

    private static void emitRestartBanner(List<String> updatedJars, List<String> newJarMods) {
        String bar =
                "################################################################################";
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(bar);
        lines.add(bar);
        lines.add("##");
        lines.add("##  STORM: WORKSHOP JAR UPDATE DETECTED -- RESTARTING SERVER");
        lines.add("##");
        lines.add("##  Steam updated one or more mod .jar files after Storm cataloged them in");
        lines.add("##  premain. This JVM is running stale bytecode; exiting with code 0 so your");
        lines.add("##  supervisor (systemd / Docker restart:always / start.sh loop) restarts");
        lines.add("##  the server and loads the new jars on the next premain.");
        lines.add("##");
        if (!updatedJars.isEmpty()) {
            lines.add("##  Updated jars:");
            for (String jar : updatedJars) {
                lines.add("##    " + jar);
            }
            lines.add("##");
        }
        if (!newJarMods.isEmpty()) {
            lines.add("##  New jar-bearing workshop mods:");
            for (String dir : newJarMods) {
                lines.add("##    " + dir);
            }
            lines.add("##");
        }
        lines.add(bar);
        lines.add(bar);
        lines.add("");

        for (String line : lines) {
            System.out.println(line);
            LOGGER.info(line);
        }
    }

    private static List<String> findUpdatedJars() {
        List<String> updated = new ArrayList<>();
        for (Map.Entry<String, Long> entry : JAR_MTIME_SNAPSHOT.entrySet()) {
            String path = entry.getKey();
            long oldMtime = entry.getValue();
            Path jarPath = Path.of(path);
            if (!Files.exists(jarPath)) {
                updated.add(path + " (no longer present on disk)");
                continue;
            }
            try {
                long newMtime = Files.getLastModifiedTime(jarPath).toMillis();
                if (newMtime != oldMtime) {
                    updated.add(path + " (mtime " + oldMtime + " -> " + newMtime + ")");
                }
            } catch (IOException e) {
                updated.add(path + " (mtime unreadable: " + e.getMessage() + ")");
            }
        }
        return updated;
    }

    private static List<String> findNewJarMods() {
        List<String> result = new ArrayList<>();
        Path workshopDir;
        try {
            workshopDir = StormPaths.getWorkshopDirectory();
        } catch (RuntimeException e) {
            LOGGER.warn(
                    "Storm workshop-update guard: workshop dir unresolved, skipping new-mod scan",
                    e);
            return result;
        }
        if (!Files.isDirectory(workshopDir)) {
            return result;
        }
        List<Path> currentModDirs;
        try {
            currentModDirs = StormModLoader.listWorkshopDirectories(workshopDir);
        } catch (IOException e) {
            LOGGER.warn("Storm workshop-update guard: unable to list workshop dir", e);
            return result;
        }
        for (Path modDir : currentModDirs) {
            String absPath = modDir.toAbsolutePath().toString();
            if (WORKSHOP_MOD_DIR_SNAPSHOT.contains(absPath)) {
                continue;
            }
            if (containsJar(modDir)) {
                result.add(absPath);
            }
        }
        return result;
    }

    private static void recordJarsUnder(Path modDir) {
        try (Stream<Path> walk = Files.walk(modDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .forEach(StormWorkshopUpdateGuard::recordJar);
        } catch (IOException e) {
            LOGGER.warn(
                    "Storm workshop-update guard: unable to walk {} for jars",
                    modDir.toAbsolutePath(),
                    e);
        }
    }

    private static void recordJar(Path jar) {
        try {
            long mtime = Files.getLastModifiedTime(jar).toMillis();
            JAR_MTIME_SNAPSHOT.put(jar.toAbsolutePath().toString(), mtime);
        } catch (IOException e) {
            LOGGER.warn(
                    "Storm workshop-update guard: unable to read mtime for {}",
                    jar.toAbsolutePath(),
                    e);
        }
    }

    private static boolean containsJar(Path modDir) {
        try (Stream<Path> walk = Files.walk(modDir)) {
            return walk.anyMatch(
                    p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"));
        } catch (IOException e) {
            LOGGER.warn(
                    "Storm workshop-update guard: unable to walk new candidate {}",
                    modDir.toAbsolutePath(),
                    e);
            return false;
        }
    }
}
