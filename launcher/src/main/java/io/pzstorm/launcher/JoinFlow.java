package io.pzstorm.launcher;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * The whole point of the launcher, in one place — everything happens before the game process
 * exists, so nothing is ever file-locked:
 *
 * <ol>
 *   <li>fetch the server's manifest (java mods + required workshop items)
 *   <li>failing that, ask the server for its workshop items over its game UDP port
 *   <li>failing that, read them out of the server's login response ({@link ServerModList}) — the
 *       only source a stock server offers, and the only one that names items never installed here
 *   <li>whichever answered, add every installed workshop item Steam considers stale ({@link
 *       WorkshopStaleScan}) — the set the game would otherwise interrupt the join for
 *   <li>have Steam update the workshop items (child process, Steam's own dirs)
 *   <li>mirror the server-published java mods (SHA-256 verified)
 *   <li>write the credential handoff for Storm's client Java (optional)
 *   <li>spawn the game JVM pointed at all of it
 * </ol>
 */
public final class JoinFlow {

    /**
     * First Storm version whose client Java ships the launcher integration: {@code
     * io.pzstorm.storm.client.LauncherAutoJoin} (consumes the credential handoff) and the {@code
     * -Dstorm.launcher.mods} loader. Against anything older, arming the handoff strands the player
     * at the main menu — the file is never consumed and the {@code +connect} args were suppressed.
     */
    static final String MIN_INTEGRATION_STORM_VERSION = "2.5.1";

    private JoinFlow() {}

    /** Runs the full pre-launch pipeline and starts the game. */
    public static Process join(LauncherConfig config, ServerProfile profile)
            throws IOException, InterruptedException {
        ModManifest manifest = fetchManifest(profile);
        updateWorkshopItems(config, profile, manifest);
        Path modsDir = syncMods(config, profile, manifest);
        boolean handoffActive = prepareAutoJoin(config, profile);
        return launch(config, profile, modsDir, handoffActive);
    }

    /** Manifest, or null when this profile has no Storm HTTP endpoint to ask. */
    public static ModManifest fetchManifest(ServerProfile profile) {
        if (profile.stormHttpPort <= 0 || !(profile.syncMods || profile.updateWorkshopMods)) {
            return null;
        }
        URI base = ModSync.baseUri(profile.host, profile.stormHttpPort);
        Log.info("Fetching mod manifest from " + base + " …");
        try {
            return new ModSync().fetchManifest(base);
        } catch (ModSync.SyncException e) {
            if (profile.syncMods) {
                throw e; // java-mod sync is required; joining anyway would desync
            }
            Log.warn(e.getMessage() + " — continuing without workshop pre-update.");
            return null;
        }
    }

    /**
     * Best-effort: failures fall back to the game's own in-game workshop flow. Storm's own workshop
     * item is always first in the list — clients get (and keep) Storm from the workshop by default,
     * even when the server publishes no items of its own.
     */
    public static void updateWorkshopItems(
            LauncherConfig config, ServerProfile profile, ModManifest manifest)
            throws InterruptedException {
        if (!profile.updateWorkshopMods || profile.noSteam) {
            return;
        }
        List<String> items = new ArrayList<>();
        String stormItem = config.stormWorkshopItemId(config.resolveGameDir());
        if (stormItem != null) {
            items.add(stormItem);
            Log.info("Storm workshop item " + stormItem + " added to the pre-update.");
        }
        for (String item : serverWorkshopItems(config, profile, manifest)) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        for (String item : staleInstalledItems(config)) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            Log.info("Server did not publish workshop items to pre-update.");
            return;
        }
        try {
            WorkshopUpdate.run(config, items);
        } catch (IOException e) {
            Log.warn(
                    "Workshop pre-update failed: "
                            + e.getMessage()
                            + " — the game's own join flow will handle items.");
        }
    }

    /**
     * The server's own requirement list, from the cheapest source that answers. Each fallback costs
     * more and reaches further:
     *
     * <ol>
     *   <li>{@link ModManifest} over Storm's HTTP port — free, but that port is rarely open to
     *       players
     *   <li>{@link ServerQuery} over the game's UDP port — needs Storm on the server
     *   <li>{@link ServerModList} — a real login, so it works against a stock server, but it needs
     *       credentials and briefly occupies a slot
     * </ol>
     */
    static List<String> serverWorkshopItems(
            LauncherConfig config, ServerProfile profile, ModManifest manifest)
            throws InterruptedException {
        if (manifest != null && !manifest.workshopItems.isEmpty()) {
            return manifest.workshopItems;
        }
        List<String> queried = queryWorkshopItems(config, profile);
        if (!queried.isEmpty()) {
            return queried;
        }
        ServerModList.Result probed = ServerModList.run(config, profile);
        return probed == null ? Collections.emptyList() : probed.workshopItems;
    }

    /**
     * Last source: installed items whose published version diverged from the local install —
     * exactly what would otherwise surface as the game's in-game "install workshop updates" dialog
     * (see {@link WorkshopStaleScan}). Still needed even when a server states its list, because it
     * also refreshes items the server never mentions.
     */
    static List<String> staleInstalledItems(LauncherConfig config) throws InterruptedException {
        try {
            List<String> stale = WorkshopStaleScan.run(config);
            if (!stale.isEmpty()) {
                Log.info(
                        stale.size()
                                + " installed workshop item(s) have published updates —"
                                + " pre-updating: "
                                + String.join(", ", stale));
            }
            return stale;
        } catch (IOException | RuntimeException e) {
            Log.warn(
                    "Could not scan installed workshop items for updates: "
                            + e.getMessage()
                            + " — the game may prompt in-game.");
            return Collections.emptyList();
        }
    }

    /**
     * Second source for the workshop list, over the game's own UDP port. Most servers never expose
     * Storm's HTTP port to players, so this is the path that actually fires in the field.
     *
     * <p>The result deliberately stops here rather than being folded into a {@link ModManifest}: a
     * manifest also drives {@link #syncMods}, whose deletion pass would wipe the local java-mod
     * mirror if handed a file list this query cannot produce.
     */
    static List<String> queryWorkshopItems(LauncherConfig config, ServerProfile profile)
            throws InterruptedException {
        ServerQuery.Result result = ServerQuery.run(config, profile);
        return result == null ? Collections.emptyList() : result.workshopItems;
    }

    /** Sync (if enabled) and return the mods dir to pass to the game, or null. */
    public static Path syncMods(
            LauncherConfig config, ServerProfile profile, ModManifest manifest) {
        if (!profile.syncMods || manifest == null) {
            if (!profile.syncMods) {
                Log.info(
                        "Java mod sync disabled for "
                                + profile.connectAddress()
                                + (profile.stormHttpPort <= 0
                                        ? " (no Storm HTTP port configured)"
                                        : ""));
            }
            return null;
        }
        checkStormVersionSkew(config, manifest.stormVersion);
        if (!manifest.files.isEmpty()) {
            String local = localStormVersion(config);
            if (!supportsLauncherIntegration(local)) {
                // joining anyway would desync: old Storm ignores -Dstorm.launcher.mods, so the
                // server's java mods would never load client-side
                throw new ModSync.SyncException(
                        "This server publishes java mods, but the client's Storm ("
                                + (local == null ? "none found" : local)
                                + ") predates launcher-managed mods (needs storm "
                                + MIN_INTEGRATION_STORM_VERSION
                                + "+). Close the game, let Steam update the Storm workshop item,"
                                + " then join again.");
            }
        }
        URI base = ModSync.baseUri(profile.host, profile.stormHttpPort);
        Path modsDir = LauncherPaths.modsDir(profile.serverKey());
        try {
            ModSync.SyncResult result = new ModSync().sync(base, manifest, modsDir);
            Log.info(
                    "Mod sync complete: "
                            + result.downloaded
                            + " downloaded ("
                            + result.downloadedBytes
                            + " bytes), "
                            + result.kept
                            + " up-to-date, "
                            + result.deleted
                            + " removed.");
        } catch (IOException e) {
            throw new ModSync.SyncException("Mod sync failed: " + e.getMessage(), e);
        }
        return modsDir;
    }

    /**
     * Arms the full auto-join by writing the credential handoff that Storm's client Java ({@code
     * io.pzstorm.storm.client.LauncherAutoJoin}) consumes at the first main menu. Returns true when
     * armed — the caller then passes the file's path as {@code -Dstorm.autojoin.file} and
     * suppresses vanilla's {@code +connect} args so the two flows can't race. Any failure degrades
     * to the +connect pre-filled popup.
     */
    public static boolean prepareAutoJoin(LauncherConfig config, ServerProfile profile) {
        if (!profile.autoConnect) {
            clearAutoJoinHandoff();
            return false;
        }
        if (profile.username.isEmpty()) {
            Log.warn(
                    "Auto-connect is on but no username is set for "
                            + profile.connectAddress()
                            + " — falling back to the connect popup.");
            clearAutoJoinHandoff();
            return false;
        }
        String stormVersion = localStormVersion(config);
        if (!supportsLauncherIntegration(stormVersion)) {
            Log.info(
                    "Client Storm "
                            + (stormVersion == null ? "(none found)" : stormVersion)
                            + " predates launcher auto-join (needs storm "
                            + MIN_INTEGRATION_STORM_VERSION
                            + "+) — using the game's connect flow instead. Let Steam update the"
                            + " Storm workshop item to enable one-click join.");
            clearAutoJoinHandoff();
            return false;
        }
        return writeAutoJoinHandoff(profile);
    }

    /**
     * Whether the installed client Storm understands the launcher handoff (see {@link
     * #MIN_INTEGRATION_STORM_VERSION}). Full version strings look like {@code
     * 42.20.2_2.5.1-SNAPSHOT}: game version, '_', storm version.
     *
     * <p>No storm jar at all → false; only {@code +connect} can work on a vanilla client. A storm
     * segment that does not parse as a dotted number → true: that is a hand-built dev Storm, which
     * postdates the integration.
     */
    static boolean supportsLauncherIntegration(String fullStormVersion) {
        if (fullStormVersion == null) {
            return false;
        }
        int sep = fullStormVersion.lastIndexOf('_');
        if (sep < 0 || sep == fullStormVersion.length() - 1) {
            return true;
        }
        String storm = fullStormVersion.substring(sep + 1);
        int dash = storm.indexOf('-');
        if (dash >= 0) {
            storm = storm.substring(0, dash);
        }
        String[] have = storm.split("\\.");
        String[] need = MIN_INTEGRATION_STORM_VERSION.split("\\.");
        for (int i = 0; i < Math.max(have.length, need.length); i++) {
            int haveN;
            try {
                haveN = i < have.length ? Integer.parseInt(have[i]) : 0;
            } catch (NumberFormatException e) {
                return true;
            }
            int needN = i < need.length ? Integer.parseInt(need[i]) : 0;
            if (haveN != needN) {
                return haveN > needN;
            }
        }
        return true;
    }

    /**
     * {@link Properties} format because both sides are Java and its escaping survives any password.
     * The client deletes the file on first read; stale leftovers are cleared on launcher start.
     */
    static boolean writeAutoJoinHandoff(ServerProfile profile) {
        Properties handoff = new Properties();
        handoff.setProperty("host", profile.host);
        handoff.setProperty("port", String.valueOf(profile.port));
        handoff.setProperty("username", profile.username);
        handoff.setProperty("password", profile.accountPassword);
        handoff.setProperty("serverPassword", profile.serverPassword);
        try {
            Path file = LauncherPaths.autoJoinFile();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                handoff.store(writer, null);
            }
            Log.info(
                    "Auto-connect armed for "
                            + profile.username
                            + "@"
                            + profile.connectAddress()
                            + ".");
            return true;
        } catch (IOException e) {
            Log.warn(
                    "Could not write auto-connect handoff: "
                            + e.getMessage()
                            + " — falling back to the connect popup.");
            return false;
        }
    }

    /** Stale handoffs must not fire on an unrelated later game start. */
    public static void clearAutoJoinHandoff() {
        try {
            Files.deleteIfExists(LauncherPaths.autoJoinFile());
        } catch (IOException ignored) {
            // next write truncates it anyway
        }
    }

    public static Process launch(LauncherConfig config, ServerProfile profile, Path modsDir)
            throws IOException {
        return launch(config, profile, modsDir, false);
    }

    public static Process launch(
            LauncherConfig config, ServerProfile profile, Path modsDir, boolean handoffActive)
            throws IOException {
        GameLaunch.LaunchPlan plan =
                GameLaunch.plan(
                        config,
                        profile,
                        modsDir,
                        handoffActive ? LauncherPaths.autoJoinFile() : null);
        for (String warning : plan.warnings) {
            Log.warn(warning);
        }
        Log.info("Launching: " + GameLaunch.describe(plan));
        try {
            Process process = plan.start(LauncherPaths.gameLogFile());
            Log.info(
                    "Game started (pid "
                            + process.pid()
                            + "). Output -> "
                            + LauncherPaths.gameLogFile());
            return process;
        } catch (IOException e) {
            clearAutoJoinHandoff();
            throw e;
        }
    }

    private static void checkStormVersionSkew(LauncherConfig config, String serverVersion) {
        String local = localStormVersion(config);
        if (local == null || serverVersion == null || serverVersion.equals("unknown")) {
            return;
        }
        if (!local.equals(serverVersion)) {
            Log.warn(
                    "Storm version skew: server runs "
                            + serverVersion
                            + ", this client has "
                            + local
                            + ". If the join fails, let Steam update the Storm workshop item"
                            + " (no game running) and try again.");
        } else {
            Log.info("Storm version matches server (" + local + ").");
        }
    }

    /** Best-effort: read the client's Storm version off the lib jar filename. */
    static String localStormVersion(LauncherConfig config) {
        Path bootstrapDir = config.resolveBootstrapDir(config.resolveGameDir());
        if (bootstrapDir == null) {
            return null;
        }
        Path libDir =
                bootstrapDir.getParent() == null
                        ? null
                        : bootstrapDir.getParent().resolve("42").resolve("lib");
        if (libDir == null || !Files.isDirectory(libDir)) {
            return null;
        }
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(libDir, "storm-*.jar")) {
            for (Path jar : jars) {
                String name = jar.getFileName().toString();
                String version = name.substring("storm-".length(), name.length() - 4);
                if (!version.isEmpty() && Character.isDigit(version.charAt(0))) {
                    return version;
                }
            }
        } catch (IOException ignored) {
            // purely informational; skew check just gets skipped
        }
        return null;
    }
}
