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

    /** Storm's experimental client-side performance patches; on by default. */
    public boolean clientPerfFixes = true;

    /** Skip intro screens (photosensitivity warning, logos, TOS); on by default. */
    public boolean skipMenus = true;

    /** Size the game's -Xmx automatically from system RAM ({@link GameMemory#autoGb}). */
    public boolean autoMemory = true;

    /** Manual max heap in GB when {@link #autoMemory} is off; clamped by {@link GameMemory}. */
    public int memoryGb = 8;

    /**
     * Extra JVM args applied to every launch; an explicit -Xmx here wins over the memory setting.
     */
    public List<String> globalVmArgs = new ArrayList<>();

    public List<ServerProfile> servers = new ArrayList<>();

    /**
     * Item jar a staged copy was started from (never persisted; see {@link LauncherStage}). The
     * staged jar itself lives outside every workshop item, so own-item identity — which item to
     * keep updated, which steamapps to search — resolves through this instead.
     */
    private Path stagedOrigin;

    public void setStagedOrigin(Path origin) {
        this.stagedOrigin = origin;
    }

    /** Identity anchor for own-item resolution: the staging origin when staged, else the jar. */
    Path effectiveOwnLocation() {
        return stagedOrigin != null ? stagedOrigin : ownLocation();
    }

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
                "Storm Launcher config. Servers and all passwords live in the game's own"
                        + " saved-server database (Zomboid/db/ServerListSteam.db); entries here"
                        + " only carry launcher extras, joined by host:port:username.");
        map.put("gameDir", gameDir);
        map.put("jvmPath", jvmPath);
        map.put("bootstrapDir", bootstrapDir);
        map.put("clientPerfFixes", clientPerfFixes);
        map.put("skipMenus", skipMenus);
        map.put("autoMemory", autoMemory);
        map.put("memoryGb", (long) memoryGb);
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
        config.clientPerfFixes = ServerProfile.bool(map.get("clientPerfFixes"), true);
        config.skipMenus = ServerProfile.bool(map.get("skipMenus"), true);
        config.autoMemory = ServerProfile.bool(map.get("autoMemory"), true);
        config.memoryGb = (int) ServerProfile.num(map.get("memoryGb"), 8);
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

    /**
     * Max heap for the game JVM in GB, or 0 to keep the game json's own -Xmx (auto mode on a
     * runtime where RAM cannot be detected).
     */
    public int resolveMemoryGb() {
        return autoMemory ? GameMemory.autoGb() : GameMemory.clampManualGb(memoryGb);
    }

    public Path resolveGameDir() {
        if (!gameDir.isEmpty()) {
            return gameDirAt(Paths.get(gameDir));
        }
        if (stagedOrigin != null) {
            Path nearOrigin = gameDirNear(stagedOrigin);
            if (nearOrigin != null) {
                return nearOrigin;
            }
        }
        return detectGameDir();
    }

    public Path resolveJvm(Path resolvedGameDir) {
        if (!jvmPath.isEmpty()) {
            return Paths.get(jvmPath);
        }
        if (resolvedGameDir != null) {
            Path jre = resolvedGameDir.resolve("jre64");
            Path[] candidates = {
                jre.resolve(Paths.get("bin", "javaw.exe")),
                jre.resolve(Paths.get("bin", "java.exe")),
                jre.resolve(Paths.get("bin", "java")),
                // macOS ships the JRE as a bundle
                jre.resolve(Paths.get("Contents", "Home", "bin", "java")),
            };
            for (Path jvm : candidates) {
                if (Files.isRegularFile(jvm)) {
                    return jvm;
                }
            }
        }
        return Paths.get("java");
    }

    /**
     * Directory holding storm-bootstrap.jar (and agentlib.dll on Windows), or null. Workshop
     * installs win: clients run the Storm that Steam downloaded, so the join flow can keep it
     * current. The local-dev tree ({@code ~/Zomboid/Workshop/storm}) only applies when no workshop
     * item is installed, or when set explicitly.
     */
    public Path resolveBootstrapDir(Path resolvedGameDir) {
        if (!bootstrapDir.isEmpty()) {
            Path dir = Paths.get(bootstrapDir);
            return hasBootstrap(dir) ? dir : null;
        }
        for (String workshopId : orderedWorkshopIds()) {
            Path candidate = workshopBootstrap(resolvedGameDir, workshopId);
            if (candidate != null) {
                return candidate;
            }
        }
        Path localDev = localDevBootstrap();
        return hasBootstrap(localDev) ? localDev : null;
    }

    /**
     * The Storm workshop item this client should keep updated before joining: the item the launcher
     * itself ships inside, else the item providing the resolved bootstrap, else the first baked id
     * (prod) so a client with no Storm at all downloads it fresh. Null when the user pinned a
     * bootstrap dir outside any workshop item — an explicit custom install is not fought.
     */
    public String stormWorkshopItemId(Path resolvedGameDir) {
        if (!bootstrapDir.isEmpty()) {
            return workshopItemIdOf(resolveBootstrapDir(resolvedGameDir));
        }
        String own = workshopItemIdOf(effectiveOwnLocation());
        if (own != null) {
            return own;
        }
        String fromBootstrap = workshopItemIdOf(resolveBootstrapDir(resolvedGameDir));
        if (fromBootstrap != null) {
            return fromBootstrap;
        }
        List<String> baked = LauncherInfo.workshopIds();
        return baked.isEmpty() ? null : baked.get(0);
    }

    /** Baked ids (prod, stage, dev) with the item this launcher ships inside tried first. */
    List<String> orderedWorkshopIds() {
        List<String> ids = LauncherInfo.workshopIds();
        String own = workshopItemIdOf(effectiveOwnLocation());
        if (own != null) {
            ids.remove(own);
            ids.add(0, own);
        }
        return ids;
    }

    /** Bootstrap dir inside the given workshop item, or null when absent. */
    Path workshopBootstrap(Path resolvedGameDir, String workshopId) {
        for (Path steamapps : steamappsCandidates(resolvedGameDir)) {
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
        return null;
    }

    /**
     * steamapps roots reachable from the launcher jar or the game dir. An ancestor either IS
     * steamapps (standard Steam layout, including the linux depot's extra {@code projectzomboid/}
     * nesting) or CONTAINS one (steamcmd's force_install_dir layout).
     */
    List<Path> steamappsCandidates(Path resolvedGameDir) {
        List<Path> roots = new ArrayList<>();
        addSteamappsAbove(effectiveOwnLocation(), roots);
        addSteamappsAbove(resolvedGameDir, roots);
        return roots;
    }

    private static void addSteamappsAbove(Path start, List<Path> out) {
        Path cursor = start == null ? null : start.toAbsolutePath().normalize();
        for (; cursor != null; cursor = cursor.getParent()) {
            Path name = cursor.getFileName();
            if (name != null && name.toString().equals("steamapps")) {
                if (!out.contains(cursor)) {
                    out.add(cursor);
                }
            } else {
                Path child = cursor.resolve("steamapps");
                if (Files.isDirectory(child) && !out.contains(child)) {
                    out.add(child);
                }
            }
        }
    }

    /** The workshop item id a path lives under ({@code …/content/108600/<id>/…}), or null. */
    static String workshopItemIdOf(Path path) {
        if (path == null) {
            return null;
        }
        Path child = null;
        for (Path cursor = path.toAbsolutePath().normalize();
                cursor != null;
                child = cursor, cursor = cursor.getParent()) {
            Path name = cursor.getFileName();
            Path parent = cursor.getParent();
            if (child != null
                    && name != null
                    && name.toString().equals("108600")
                    && parent != null
                    && parent.getFileName() != null
                    && parent.getFileName().toString().equals("content")) {
                return child.getFileName().toString();
            }
        }
        return null;
    }

    static Path localDevBootstrap() {
        return LauncherPaths.zomboidDir()
                .resolve(Paths.get("Workshop", "storm", "Contents", "mods", "storm", "bootstrap"));
    }

    /** Where this launcher runs from (jar path); overridable so tests can simulate. */
    Path ownLocation() {
        return WorkshopUpdate.ownJar();
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

    /** The dir itself, or the {@code projectzomboid/} subdir the linux/mac depots nest under. */
    static Path gameDirAt(Path dir) {
        if (isGameDir(dir)) {
            return dir;
        }
        Path nested = dir == null ? null : dir.resolve("projectzomboid");
        return isGameDir(nested) ? nested : null;
    }

    static Path detectGameDir() {
        List<Path> candidates = new ArrayList<>();
        Path jarRelative = ownJarLocation();
        if (jarRelative != null) {
            Path nearJar = gameDirNear(jarRelative);
            if (nearJar != null) {
                return nearJar;
            }
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
        candidates.add(home.resolve("Library/Application Support/Steam/" + rel));
        for (Path candidate : candidates) {
            Path resolved = gameDirAt(candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /**
     * The game install in the same Steam library as the given path — a jar shipping inside the
     * Storm workshop item (…/steamapps/workshop/content/108600/&lt;id&gt;/…) sits in the library
     * that also holds the game. Returns a validated game dir or null.
     */
    static Path gameDirNear(Path start) {
        for (Path cursor = start.toAbsolutePath().normalize();
                cursor != null;
                cursor = cursor.getParent()) {
            if (cursor.getFileName() != null
                    && cursor.getFileName().toString().equals("steamapps")) {
                return gameDirAt(cursor.resolve("common").resolve("ProjectZomboid"));
            }
        }
        return null;
    }

    private static Path ownJarLocation() {
        try {
            URI jar =
                    LauncherConfig.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI();
            return Paths.get(jar);
        } catch (Exception ignored) {
            // not running from a jar, or an opaque location — detection just moves on
        }
        return null;
    }
}
