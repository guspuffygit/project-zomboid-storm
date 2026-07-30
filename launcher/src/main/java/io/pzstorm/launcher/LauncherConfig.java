package io.pzstorm.launcher;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Launcher settings + saved servers, persisted as pretty JSON. */
public final class LauncherConfig {

    /** Project Zomboid install dir; empty = auto-detect. */
    public String gameDir = "";

    /** JVM used to run the game; empty = the game's bundled jre64. */
    public String jvmPath = "";

    /** Directory containing storm-bootstrap.jar / agentlib.dll; empty = auto-detect. */
    public String bootstrapDir = "";

    /** Extra JVM args applied to every launch (e.g. -Xmx16g). */
    public List<String> globalVmArgs = new ArrayList<>();

    public List<ServerProfile> servers = new ArrayList<>();

    public static LauncherConfig load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new LauncherConfig();
        }
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            return fromMap(Json.parseObject(text));
        } catch (IOException | RuntimeException e) {
            Log.warn("Could not read config " + file + ": " + e.getMessage());
            return new LauncherConfig();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, (Json.write(toMap()) + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(
                "_note",
                "Storm Launcher config. serverPassword is the server ACCESS password"
                        + " (stored in plain text); account credentials never live here.");
        map.put("gameDir", gameDir);
        map.put("jvmPath", jvmPath);
        map.put("bootstrapDir", bootstrapDir);
        map.put("globalVmArgs", new ArrayList<Object>(globalVmArgs));
        List<Object> serverList = new ArrayList<>();
        for (ServerProfile server : servers) {
            serverList.add(server.toMap());
        }
        map.put("servers", serverList);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static LauncherConfig fromMap(Map<String, Object> map) {
        LauncherConfig config = new LauncherConfig();
        config.gameDir = ServerProfile.str(map.get("gameDir"), "");
        config.jvmPath = ServerProfile.str(map.get("jvmPath"), "");
        config.bootstrapDir = ServerProfile.str(map.get("bootstrapDir"), "");
        Object args = map.get("globalVmArgs");
        if (args instanceof List) {
            for (Object arg : (List<?>) args) {
                if (arg != null && !String.valueOf(arg).isEmpty()) {
                    config.globalVmArgs.add(String.valueOf(arg));
                }
            }
        }
        Object serverList = map.get("servers");
        if (serverList instanceof List) {
            for (Object entry : (List<?>) serverList) {
                if (entry instanceof Map) {
                    config.servers.add(ServerProfile.fromMap((Map<String, Object>) entry));
                }
            }
        }
        return config;
    }

    // ------------------------------------------------------------------
    // Resolution (configured value first, then auto-detection)
    // ------------------------------------------------------------------

    public Path resolveGameDir() {
        if (!gameDir.isEmpty()) {
            Path dir = Paths.get(gameDir);
            return isGameDir(dir) ? dir : null;
        }
        return detectGameDir();
    }

    public Path resolveJvm(Path resolvedGameDir) {
        if (!jvmPath.isEmpty()) {
            return Paths.get(jvmPath);
        }
        if (resolvedGameDir != null) {
            Path jreBin = resolvedGameDir.resolve("jre64").resolve("bin");
            for (String candidate : new String[] {"javaw.exe", "java.exe", "java"}) {
                Path jvm = jreBin.resolve(candidate);
                if (Files.isRegularFile(jvm)) {
                    return jvm;
                }
            }
        }
        return Paths.get("java");
    }

    /** Directory holding storm-bootstrap.jar (and agentlib.dll on Windows), or null. */
    public Path resolveBootstrapDir(Path resolvedGameDir) {
        if (!bootstrapDir.isEmpty()) {
            Path dir = Paths.get(bootstrapDir);
            return hasBootstrap(dir) ? dir : null;
        }
        Path localDev =
                LauncherPaths.zomboidDir()
                        .resolve(
                                Paths.get(
                                        "Workshop",
                                        "storm",
                                        "Contents",
                                        "mods",
                                        "storm",
                                        "bootstrap"));
        if (hasBootstrap(localDev)) {
            return localDev;
        }
        if (resolvedGameDir != null) {
            // <library>/steamapps/common/ProjectZomboid -> <library>/steamapps/workshop
            Path steamapps =
                    resolvedGameDir.getParent() == null
                            ? null
                            : resolvedGameDir.getParent().getParent();
            if (steamapps != null) {
                for (String workshopId : LauncherInfo.workshopIds()) {
                    Path candidate =
                            steamapps.resolve(
                                    Paths.get(
                                            "workshop",
                                            "content",
                                            "108600",
                                            workshopId,
                                            "mods",
                                            "storm",
                                            "bootstrap"));
                    if (hasBootstrap(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** The local-dev install needs -DstormType=local so the bootstrapper finds its libs. */
    public static boolean isLocalDevBootstrap(Path bootstrapDir) {
        return bootstrapDir != null
                && bootstrapDir
                        .toAbsolutePath()
                        .normalize()
                        .startsWith(
                                LauncherPaths.zomboidDir()
                                        .resolve("Workshop")
                                        .toAbsolutePath()
                                        .normalize());
    }

    private static boolean hasBootstrap(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("storm-bootstrap.jar"));
    }

    public static boolean isGameDir(Path dir) {
        return dir != null && Files.isRegularFile(dir.resolve("ProjectZomboid64.json"));
    }

    static Path detectGameDir() {
        List<Path> candidates = new ArrayList<>();
        Path jarRelative = gameDirRelativeToOwnJar();
        if (jarRelative != null) {
            candidates.add(jarRelative);
        }
        String rel = "steamapps" + File.separator + "common" + File.separator + "ProjectZomboid";
        for (File root : File.listRoots()) {
            candidates.add(root.toPath().resolve("SteamLibrary").resolve(rel));
            candidates.add(
                    root.toPath().resolve("Program Files (x86)").resolve("Steam").resolve(rel));
            candidates.add(root.toPath().resolve("Steam").resolve(rel));
        }
        Path home = Paths.get(System.getProperty("user.home"));
        candidates.add(home.resolve(".steam/steam/" + rel));
        candidates.add(home.resolve(".local/share/Steam/" + rel));
        for (Path candidate : candidates) {
            if (isGameDir(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * When the launcher jar ships inside the Storm workshop item
     * (…/steamapps/workshop/content/108600/&lt;id&gt;/mods/storm/launcher/storm-launcher.jar) the
     * game install sits in the same Steam library.
     */
    private static Path gameDirRelativeToOwnJar() {
        try {
            URI jar =
                    LauncherConfig.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI();
            Path dir = Paths.get(jar).toAbsolutePath().normalize();
            for (Path cursor = dir; cursor != null; cursor = cursor.getParent()) {
                if (cursor.getFileName() != null
                        && cursor.getFileName().toString().equals("steamapps")) {
                    return cursor.resolve("common").resolve("ProjectZomboid");
                }
            }
        } catch (Exception ignored) {
            // not running from a jar, or an opaque location — detection just moves on
        }
        return null;
    }
}
