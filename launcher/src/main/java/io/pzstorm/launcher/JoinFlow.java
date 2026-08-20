package io.pzstorm.launcher;

import java.io.IOException;
import java.io.Writer;
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
 *   <li>ask the server for its required workshop items over its game UDP port
 *   <li>failing that, read them out of the server's login response ({@link ServerModList}) — the
 *       only source a stock server offers, and the only one that names items never installed here
 *   <li>whichever answered, add every installed workshop item Steam considers stale ({@link
 *       WorkshopStaleScan}) — the set the game would otherwise interrupt the join for
 *   <li>have Steam update the workshop items (child process, Steam's own dirs)
 *   <li>write the credential handoff for Storm's client Java (optional)
 *   <li>spawn the game JVM pointed at all of it
 * </ol>
 */
public final class JoinFlow {

    /**
     * First Storm version whose client Java ships the launcher integration: {@code
     * io.pzstorm.storm.client.LauncherAutoJoin} (consumes the credential handoff). Against anything
     * older, arming the handoff strands the player at the main menu — the file is never consumed
     * and the {@code +connect} args were suppressed.
     */
    static final String MIN_INTEGRATION_STORM_VERSION = "2.5.1";

    /** Same key the bootstrap's StormCoreUpdate HEADs: {@code storm/core/<pzVersion>/storm.jar}. */
    static final String CORE_CDN_URL_TEMPLATE = "https://guspuffy.com/storm/core/%s/storm.jar";

    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    private JoinFlow() {}

    /** Runs the full pre-launch pipeline and starts the game. */
    public static Process join(LauncherConfig config, ServerProfile profile)
            throws IOException, InterruptedException {
        return join(config, profile, true);
    }

    /**
     * Runs the full pre-launch pipeline and starts the game. With {@code forceModUpdates} every
     * item goes through Steam's per-item DownloadItem confirm; without it, items the batched
     * workshop scan proves current only get an instant local state check.
     */
    public static Process join(
            LauncherConfig config, ServerProfile profile, boolean forceModUpdates)
            throws IOException, InterruptedException {
        GameProcessTracker.reapLeftover();
        updateWorkshopItems(config, profile, forceModUpdates);
        boolean handoffActive = prepareAutoJoin(config, profile);
        return launch(config, profile, handoffActive);
    }

    /**
     * Best-effort: failures fall back to the game's own in-game workshop flow — except when Steam
     * refused every single item ({@link WorkshopUpdate.Result#nothingUpdated}), which aborts the
     * join: the in-game flow talks to the same stuck Steam client and can only strand the player at
     * the workshop screen. Storm's own workshop item is always first in the list — clients get (and
     * keep) Storm from the workshop by default, even when the server publishes no items of its own.
     */
    public static void updateWorkshopItems(
            LauncherConfig config, ServerProfile profile, boolean forceModUpdates)
            throws IOException, InterruptedException {
        if (!profile.updateWorkshopMods) {
            return;
        }
        List<String> items = new ArrayList<>();
        String stormItem = config.stormWorkshopItemId(config.resolveGameDir());
        if (stormItem != null) {
            items.add(stormItem);
            Log.info("Storm workshop item " + stormItem + " added to the pre-update.");
        }
        for (String item : serverWorkshopItems(config, profile)) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        WorkshopStaleScan.Scan scan = scanInstalledItems(config, items);
        if (scan != null) {
            List<String> stale = scan.staleInstalled();
            if (!stale.isEmpty()) {
                Log.info(
                        stale.size()
                                + " installed workshop item(s) have published updates —"
                                + " pre-updating: "
                                + String.join(", ", stale));
            }
            for (String item : stale) {
                if (!items.contains(item)) {
                    items.add(item);
                }
            }
        }
        if (items.isEmpty()) {
            Log.info("Server did not publish workshop items to pre-update.");
            return;
        }
        List<String> update = new ArrayList<>();
        List<String> verify = new ArrayList<>();
        for (String item : items) {
            if (!forceModUpdates && scan != null && scan.isCurrent(item)) {
                verify.add(item);
            } else {
                update.add(item);
            }
        }
        if (!verify.isEmpty()) {
            Log.info(
                    verify.size()
                            + " item(s) already match the published workshop version —"
                            + " quick state check only.");
        }
        WorkshopUpdate.Result result;
        try {
            result = WorkshopUpdate.run(config, update, verify);
        } catch (IOException e) {
            Log.warn(
                    "Workshop pre-update failed: "
                            + e.getMessage()
                            + " — the game's own join flow will handle items.");
            return;
        }
        if (result.nothingUpdated()) {
            throw new SteamRestartRequiredException(
                    "Steam refused to update all "
                            + result.attempted
                            + " workshop item(s) this server needs, so the join was cancelled —"
                            + " the game would only get stuck at its workshop screen.",
                    "Restart Steam and press Join again; if it keeps happening, use \"Launch to"
                            + " Main Menu\" and join from inside the game.");
        }
        if (stormItem != null && result.failedItemIds.contains(stormItem)) {
            throw new SteamRestartRequiredException(
                    "Steam couldn't update the Storm workshop item ("
                            + stormItem
                            + ") — the version this server needs won't load, so the join was"
                            + " cancelled.",
                    "Restart Steam and press Join again.");
        }
    }

    /**
     * The server's own requirement list, from the cheapest source that answers:
     *
     * <ol>
     *   <li>{@link ServerQuery} over the game's UDP port — needs Storm on the server
     *   <li>{@link ServerModList} — a real login, so it works against a stock server, but it needs
     *       credentials and briefly occupies a slot
     * </ol>
     *
     * <p>An answering Storm server also names its Storm version, which feeds the skew warning.
     */
    static List<String> serverWorkshopItems(LauncherConfig config, ServerProfile profile)
            throws InterruptedException {
        ServerQuery.Result queried = ServerQuery.run(config, profile);
        if (queried != null) {
            checkStormVersionSkew(config, queried.stormVersion);
            if (!queried.workshopItems.isEmpty()) {
                return queried.workshopItems;
            }
        }
        ServerModList.Result probed = ServerModList.run(config, profile);
        return probed == null ? Collections.emptyList() : probed.workshopItems;
    }

    /**
     * One batched scan serving two purposes: it names installed items whose published version
     * diverged from the local install — exactly what would otherwise surface as the game's in-game
     * "install workshop updates" dialog (see {@link WorkshopStaleScan}) — and it proves the rest
     * current so the non-forced join can skip their per-item Steam confirm. Null when the scan
     * failed; the caller then sends everything through the full update path.
     */
    static WorkshopStaleScan.Scan scanInstalledItems(
            LauncherConfig config, List<String> candidateItems) throws InterruptedException {
        try {
            return WorkshopStaleScan.run(config, candidateItems);
        } catch (IOException | RuntimeException e) {
            Log.warn(
                    "Could not scan installed workshop items for updates: "
                            + e.getMessage()
                            + " — updating every item via Steam.");
            return null;
        }
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
        if (!profile.accountPassword.isEmpty()
                && !PzPasswordHash.isHashed(profile.accountPassword)) {
            // Storm submits the handoff password unhashed (doHash=false), so a raw value is a
            // guaranteed "incorrect password". The popup fallback hashes whatever the game's
            // database pre-fills, so it still works against the same raw stored value.
            Log.warn(
                    "Account password for "
                            + profile.connectAddress()
                            + " is not in the game's stored form — falling back to the connect"
                            + " popup.");
            clearAutoJoinHandoff();
            return false;
        }
        String stormVersion = effectiveStormVersion(config);
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
     *
     * <p>{@code password} carries the game's stored form ({@link PzPasswordHash}, never the
     * plaintext); Storm submits it unhashed, exactly like the in-game browser's saved-credentials
     * join. {@code serverPassword} is the raw server access password — the game sends it as-is.
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

    public static Process launch(
            LauncherConfig config, ServerProfile profile, boolean handoffActive)
            throws IOException {
        GameLaunch.LaunchPlan plan =
                GameLaunch.plan(
                        config, profile, handoffActive ? LauncherPaths.autoJoinFile() : null);
        for (String warning : plan.warnings) {
            Log.warn(warning);
        }
        Log.info("Launching: " + GameLaunch.describe(plan));
        Log.info("Game JVM args: " + GameLaunch.describeJvmArgs(plan));
        try {
            Process process = plan.start(LauncherPaths.gameLogFile());
            GameProcessTracker.record(process);
            GameCrashWatch.arm(process, LauncherPaths.gameLogFile());
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
        String local = effectiveStormVersion(config);
        if (local == null
                || serverVersion == null
                || serverVersion.isEmpty()
                || serverVersion.equals("unknown")) {
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

    /**
     * Mirrors the bootstrap's {@code StormCoreUpdate}: the core the game will actually run is the
     * CDN-staged build whenever it publishes a strictly higher stormVersion for the same PZ build
     * and the item jar is not a SNAPSHOT; otherwise the item's jar. One HEAD; any failure falls
     * back to the item jar's version so the callers degrade to the old behaviour.
     */
    static String effectiveStormVersion(LauncherConfig config) {
        String local = localStormVersion(config);
        if (local == null) {
            return null;
        }
        int split = local.indexOf('_');
        if (split <= 0 || local.endsWith(SNAPSHOT_SUFFIX)) {
            return local;
        }
        CdnUpdate.Remote remote =
                CdnUpdate.fetch(String.format(CORE_CDN_URL_TEMPLATE, local.substring(0, split)));
        return withCdnCore(local, remote == null ? null : remote.version());
    }

    /** Pure half of {@link #effectiveStormVersion}: {@code <pz>_<storm>} vs the published core. */
    static String withCdnCore(String local, String publishedStormVersion) {
        if (local == null || publishedStormVersion == null || local.endsWith(SNAPSHOT_SUFFIX)) {
            return local;
        }
        int split = local.indexOf('_');
        if (split <= 0) {
            return local;
        }
        String ownStorm = local.substring(split + 1);
        return CdnUpdate.isNewer(publishedStormVersion, ownStorm)
                ? local.substring(0, split + 1) + publishedStormVersion
                : local;
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
