package io.pzstorm.launcher;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
        GameInstallState.warnIfUpdatePending(config);
        ServerRequirements required = serverRequirements(config, profile);
        updateWorkshopItems(config, profile, forceModUpdates, required);
        boolean handoffActive = prepareAutoJoin(config, profile);
        // after the workshop update, so the fingerprint describes what the game will load
        String fingerprint = contentFingerprint(config, required);
        return launch(
                config,
                profile,
                handoffActive,
                required.mods,
                required.joinChecksums(),
                fingerprint);
    }

    /**
     * Any item Steam leaves not join-ready aborts the join: the client would be mismatched with the
     * server, and the in-game workshop flow talks to the same stuck Steam client, so launching
     * anyway can only strand the player at the workshop screen. Only a Steam that never answered at
     * all falls back to the game's own flow. Storm's own workshop item is always first in the list
     * — clients get (and keep) Storm from the workshop by default, even when the server publishes
     * no items of its own.
     */
    public static void updateWorkshopItems(
            LauncherConfig config,
            ServerProfile profile,
            boolean forceModUpdates,
            ServerRequirements required)
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
        for (String item : required.workshopItems) {
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
        repairLastJoinChecksumKick(config, items);
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
        SteamRestartRequiredException blocker = joinBlocker(result);
        if (blocker != null) {
            throw blocker;
        }
        if (scan != null && result.childRan) {
            repairInstallStampDesync(config, scan, items);
        }
        repairContentSizeDesync(config, items);
    }

    /**
     * Acts on the checksum-kick record the Storm client left behind (see {@link
     * JoinFailureHandoff}): when the rejected file belongs to a workshop item this join needs,
     * Steam's install metadata is proven wrong no matter what it says — the bytes on disk already
     * failed the server's comparison — so the item goes straight to the full repair (delete +
     * subscription cycle + real re-download). A record naming an item this server doesn't require
     * is left for a join to the server it belongs to; only expiry removes it unconsumed.
     */
    static void repairLastJoinChecksumKick(LauncherConfig config, List<String> items)
            throws IOException, InterruptedException {
        JoinFailureHandoff handoff = JoinFailureHandoff.read();
        if (handoff == null) {
            return;
        }
        if (handoff.expired(System.currentTimeMillis())) {
            JoinFailureHandoff.delete();
            return;
        }
        String item = handoff.workshopItemId();
        if (item == null) {
            if (handoff.insideGameInstall(config.resolveGameDir())) {
                Log.warn(
                        "Last join was kicked by the server's file checksum on "
                                + handoff.relPath
                                + ", a file of the game itself — this client and the server are on"
                                + " different Project Zomboid builds. Let Steam finish updating"
                                + " Project Zomboid; if Steam says it is up to date, the server"
                                + " has not been updated yet.");
            } else {
                Log.warn(
                        "Last join was kicked by the server's file checksum on "
                                + handoff.relPath
                                + ", which is not inside Steam workshop content — Steam cannot"
                                + " repair it ("
                                + handoff.absPath
                                + ").");
            }
            JoinFailureHandoff.delete();
            return;
        }
        if (!items.contains(item)) {
            Log.warn(
                    "Last join was kicked by the server's file checksum on "
                            + handoff.relPath
                            + " from workshop item "
                            + item
                            + ", which this server does not require — a local mod is likely"
                            + " shadowing a server file; if joins keep failing, unsubscribe item "
                            + item
                            + ".");
            return;
        }
        Log.info(
                "Last join was kicked by the server's file checksum ("
                        + handoff.reason
                        + ": "
                        + handoff.relPath
                        + ", workshop item "
                        + item
                        + ") — forcing a clean re-download of that item before this join.");
        JoinFailureHandoff.delete();
        deleteItemContent(config, item);
        WorkshopUpdate.Result repair;
        try {
            repair = WorkshopUpdate.runRepair(config, List.of(item));
        } catch (IOException e) {
            throw new IOException(
                    kickRepairFailedMessage(item) + " (repair error: " + e.getMessage() + ")");
        }
        if (repair.steamUnavailable) {
            throw offlineRepairBlocker(config, List.of(item));
        }
        if (repair.childRan && repair.allOk) {
            Log.info("Workshop item " + item + " re-downloaded after last join's checksum kick.");
            return;
        }
        throw new IOException(kickRepairFailedMessage(item));
    }

    static String kickRepairFailedMessage(String itemId) {
        return "The last join was kicked because workshop item "
                + itemId
                + " has files that don't match the server, and Steam could not re-download it."
                + " Joining now would end in the same kick, so the join was cancelled. Fix: in"
                + " Steam, unsubscribe from the item, wait a minute, subscribe again, then press"
                + " Join.";
    }

    /**
     * Timestamp gates can all pass while the content is stale: Steam has been observed committing a
     * new install {@code timeupdated} without writing the release's bytes, which sails through
     * {@link WorkshopStaleScan}, the per-item Steam confirm and the game's own WorkshopConfirm,
     * then dies at the server's file checksum. Byte totals catch it before launch (see {@link
     * ContentSizeCheck}); a mismatch must survive spaced re-reads so an acf commit still in flight
     * from the update child never triggers a needless delete. Persistent mismatch after repair
     * cancels the join — launching would only reach the same kick.
     */
    static void repairContentSizeDesync(LauncherConfig config, List<String> items)
            throws IOException, InterruptedException {
        List<String> mismatched = sizeMismatchedWithSettle(config, items);
        if (mismatched.isEmpty()) {
            return;
        }
        Log.warn(
                mismatched.size()
                        + " workshop item(s) have content on disk that does not add up to Steam's"
                        + " install record — the server's file checksum would reject them; forcing"
                        + " a clean re-download: "
                        + String.join(", ", mismatched));
        for (String item : mismatched) {
            deleteItemContent(config, item);
        }
        WorkshopUpdate.Result repair;
        try {
            repair = WorkshopUpdate.runRepair(config, mismatched);
        } catch (IOException e) {
            throw new IOException(
                    sizeRepairFailedMessage(mismatched)
                            + " (repair error: "
                            + e.getMessage()
                            + ")");
        }
        if (repair.steamUnavailable) {
            throw offlineRepairBlocker(config, mismatched);
        }
        List<String> still =
                repair.childRan ? sizeMismatchedWithSettle(config, mismatched) : mismatched;
        if (!still.isEmpty()) {
            throw new IOException(sizeRepairFailedMessage(still));
        }
        Log.info("Workshop content repaired — on-disk bytes match Steam's install record again.");
    }

    /**
     * Same settle rationale as {@link #rescanWithSettle}: Steam commits install metadata
     * asynchronously, so only a byte mismatch that persists across every spaced re-read counts. A
     * check failure proves nothing and aborts quietly — the reactive checksum-kick handoff still
     * backstops whatever this misses.
     */
    private static List<String> sizeMismatchedWithSettle(
            LauncherConfig config, Collection<String> itemIds) throws InterruptedException {
        List<String> mismatched = List.of();
        for (int attempt = 0; ; attempt++) {
            try {
                mismatched = ContentSizeCheck.mismatched(config, itemIds);
            } catch (IOException | RuntimeException e) {
                Log.warn("Could not verify workshop content sizes: " + e.getMessage());
                return List.of();
            }
            if (mismatched.isEmpty() || attempt == STAMP_SETTLE_ATTEMPTS - 1) {
                return mismatched;
            }
            Thread.sleep(STAMP_SETTLE_MILLIS);
        }
    }

    /** Acf re-reads before concluding Steam really left an item's install stamp behind. */
    private static final int STAMP_SETTLE_ATTEMPTS = 3;

    private static final long STAMP_SETTLE_MILLIS = 2_000;

    /**
     * The update pass can end with Steam satisfied and the game's join gate still unsatisfiable:
     * when an item's installed content already matches the published manifest but its recorded
     * install timestamp does not, DownloadItem verifies the content, moves no bytes, and never
     * rewrites the install metadata. The game compares only the timestamps and re-requests the
     * download with no retry cap — launching would strand the player in an endless in-game
     * workshop-download loop that no Steam restart clears. Repair = discard the desynced install
     * record (delete the item's content directory, cycle its subscription) so Steam performs a real
     * download and commits fresh metadata; if even that leaves the stamp behind, cancel the join
     * with the manual fix instead of launching into a guaranteed hang.
     */
    static void repairInstallStampDesync(
            LauncherConfig config, WorkshopStaleScan.Scan scan, List<String> attemptedItems)
            throws IOException, InterruptedException {
        List<String> stale = rescanWithSettle(config, scan, attemptedItems);
        if (stale.isEmpty()) {
            return;
        }
        Log.warn(
                stale.size()
                        + " workshop item(s) came back from Steam \"up to date\" with the install"
                        + " stamp still behind the published version — forcing a clean re-download:"
                        + " "
                        + String.join(", ", stale));
        for (String item : stale) {
            deleteItemContent(config, item);
        }
        WorkshopUpdate.Result repair;
        try {
            repair = WorkshopUpdate.runRepair(config, stale);
        } catch (IOException e) {
            throw new IOException(
                    repairFailedMessage(stale) + " (repair error: " + e.getMessage() + ")");
        }
        if (repair.steamUnavailable) {
            throw offlineRepairBlocker(config, stale);
        }
        List<String> still = repair.childRan ? rescanWithSettle(config, scan, stale) : stale;
        if (!still.isEmpty()) {
            throw new IOException(repairFailedMessage(still));
        }
        Log.info("Workshop install stamp(s) repaired — the join gate comparison now matches.");
    }

    /**
     * Steam commits install metadata asynchronously, so one early read could call a just-updated
     * item stale; only an item that stays stale across every spaced re-read gets repaired. A
     * re-read failure aborts quietly: the pre-update itself succeeded, and the worst case is the
     * old behaviour (the in-game flow surfaces the problem).
     */
    private static List<String> rescanWithSettle(
            LauncherConfig config, WorkshopStaleScan.Scan scan, Collection<String> itemIds)
            throws InterruptedException {
        List<String> stale = List.of();
        for (int attempt = 0; ; attempt++) {
            try {
                stale = scan.reScanStale(config, itemIds);
            } catch (IOException | RuntimeException e) {
                Log.warn("Could not re-check workshop install stamps: " + e.getMessage());
                return List.of();
            }
            if (stale.isEmpty() || attempt == STAMP_SETTLE_ATTEMPTS - 1) {
                return stale;
            }
            Thread.sleep(STAMP_SETTLE_MILLIS);
        }
    }

    /**
     * The one deliberate write into steamapps: Steam will not re-download content whose manifest
     * already matches the published one, so the desynced install record can only be broken by
     * making the content itself diverge. Steam re-creates the directory during the forced
     * re-download that follows.
     */
    private static void deleteItemContent(LauncherConfig config, String itemId) {
        Path dir = WorkshopStaleScan.findItemContentDir(config, itemId);
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        Path ownJar = WorkshopUpdate.ownJar();
        if (ownJar != null
                && ownJar.toAbsolutePath()
                        .normalize()
                        .startsWith(dir.toAbsolutePath().normalize())) {
            // deleting the running launcher out from under itself would leave the item
            // half-gone with the jar locked against Steam's re-download
            Log.warn(
                    "Not deleting "
                            + dir
                            + " — the launcher itself runs from it; trying the subscription cycle"
                            + " alone.");
            return;
        }
        try (java.util.stream.Stream<Path> tree = Files.walk(dir)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
            Log.info("Deleted " + dir + " so Steam performs a real re-download.");
        } catch (IOException e) {
            Log.warn(
                    "Could not delete "
                            + dir
                            + ": "
                            + e.getMessage()
                            + " — Steam may skip the re-download.");
        }
    }

    /**
     * Offline completion of a repair the Steamworks child could not run because no Steam client is
     * running ({@link WorkshopUpdate.Result#steamUnavailable} — the only evidence strong enough to
     * edit Steam's own state file). The content directories are already deleted; leaving the acf
     * install records pointing at them would make the next Steam start reconcile by verifying the
     * ENTIRE workshop depot. Stripping the records instead makes that start a plain download of
     * just these items. Records are only stripped for items whose content is actually gone — a
     * directory {@link #deleteItemContent} skipped keeps its record. Always returns the exception
     * that cancels the join: without Steam the re-download cannot happen in this launch.
     */
    private static SteamRestartRequiredException offlineRepairBlocker(
            LauncherConfig config, Collection<String> itemIds) {
        List<String> contentGone = new ArrayList<>();
        for (String item : itemIds) {
            Path dir = WorkshopStaleScan.findItemContentDir(config, item);
            if (dir == null || !Files.isDirectory(dir)) {
                contentGone.add(item);
            }
        }
        List<String> stripped = List.of();
        try {
            stripped =
                    WorkshopAcfRepair.stripInstallRecords(
                            WorkshopStaleScan.findAppWorkshopAcf(config), contentGone);
        } catch (IOException e) {
            Log.warn("Could not remove Steam's install record(s): " + e.getMessage());
        }
        String summary =
                "Steam is not running, so workshop item(s) "
                        + String.join(", ", itemIds)
                        + " could not be re-downloaded now and the join was cancelled.";
        if (stripped.isEmpty()) {
            return new SteamRestartRequiredException(
                    summary,
                    "Start Steam, let it finish any workshop verification it begins, then press"
                            + " Join again.");
        }
        Log.info(
                "Steam is closed — removed its install record for item(s) "
                        + String.join(", ", stripped)
                        + " along with the damaged content, so the next Steam start downloads just"
                        + " these items instead of verifying all workshop content.");
        return new SteamRestartRequiredException(
                summary,
                "The damaged copy and Steam's record of it were removed. Start Steam — it will"
                        + " download just this content fresh — then press Join again.");
    }

    /**
     * Deliberately NOT a {@link SteamRestartRequiredException}: that popup tells the player to
     * restart Steam, which does not clear a desynced install record. The generic error box shows
     * this message with the fix that does work.
     */
    static String repairFailedMessage(Collection<String> itemIds) {
        return "Steam's install record for workshop item(s) "
                + String.join(", ", itemIds)
                + " is stuck behind the published version (the files match, the recorded version"
                + " stamp doesn't), and the automatic repair could not fix it. Joining now would"
                + " hang forever at the in-game workshop-download screen, so the join was"
                + " cancelled. Fix: in Steam, unsubscribe from the item(s), wait a minute,"
                + " subscribe again, then press Join. Restarting Steam does NOT clear this.";
    }

    static String sizeRepairFailedMessage(Collection<String> itemIds) {
        return "Workshop item(s) "
                + String.join(", ", itemIds)
                + " have content on disk that doesn't match what Steam recorded installing, and"
                + " the automatic re-download could not fix it. The server's file checksum would"
                + " kick this client, so the join was cancelled. Fix: in Steam, unsubscribe from"
                + " the item(s), wait a minute, subscribe again, then press Join.";
    }

    /**
     * Non-null when the update outcome must cancel the join: Steam answered yet left at least one
     * item not join-ready. A client missing any required item is mismatched with the server, and
     * the in-game workshop flow talks to the same stuck Steam client — launching anyway can only
     * strand the player at the workshop screen. Positive evidence that no Steam client is running
     * also cancels: the game executable is a Steam build, so launching it can only die at its own
     * "Steam failed to load" screen. Only a child that never ran for OTHER reasons (launcher jar
     * not on disk) stays best-effort — Steam's state is unknown there and the game may still boot.
     */
    static SteamRestartRequiredException joinBlocker(WorkshopUpdate.Result result) {
        if (result.steamUnavailable) {
            return new SteamRestartRequiredException(
                    "Steam is not running, so the workshop items cannot update and the game"
                            + " itself cannot start.",
                    "Start Steam, let it finish any workshop verification it begins, then press"
                            + " Join again.");
        }
        if (!result.childRan || result.allOk) {
            return null;
        }
        String failed =
                result.failedItemIds.isEmpty()
                        ? Math.max(result.failures, 1) + " of " + result.attempted
                        : result.failedItemIds.size()
                                + " of "
                                + result.attempted
                                + " ("
                                + String.join(", ", result.failedItemIds)
                                + ")";
        return new SteamRestartRequiredException(
                "Steam failed to update "
                        + failed
                        + " workshop item(s) this server needs, so the join was cancelled —"
                        + " a client missing any of them mismatches the server.",
                "Restart Steam and press Join again; if it keeps happening, use \"Send Logs to"
                        + " Developer\" so it can be investigated.");
    }

    /**
     * What the target server requires. {@code workshopItems} feeds the Steam pre-update; {@code
     * mods} (PZ mod ids, or null when no source answered) feeds {@code -Dstorm.workshop.mods} so
     * the client's Storm only catalogs workshop mods this server enables — an absent list makes
     * Storm load no workshop mods at all (see {@code io.pzstorm.storm.core.StormWorkshopModGate}).
     */
    static final class ServerRequirements {
        final List<String> workshopItems;
        final List<String> mods;
        final String checksumLua;
        final String checksumScript;
        final String checksumAnim;

        ServerRequirements(List<String> workshopItems, List<String> mods) {
            this(workshopItems, mods, "", "", "");
        }

        ServerRequirements(
                List<String> workshopItems,
                List<String> mods,
                String checksumLua,
                String checksumScript,
                String checksumAnim) {
            this.workshopItems = workshopItems;
            this.mods = mods;
            this.checksumLua = checksumLua;
            this.checksumScript = checksumScript;
            this.checksumAnim = checksumAnim;
        }

        /**
         * The {@code -Dstorm.join.checksums} value ({@code lua;script;anim}), or null when the
         * server published none (pre-v2 Storm, or the {@link ServerModList} fallback answered) —
         * the fast path then stays unarmed.
         */
        String joinChecksums() {
            if (checksumLua.isEmpty() && checksumScript.isEmpty() && checksumAnim.isEmpty()) {
                return null;
            }
            return checksumLua + ";" + checksumScript + ";" + checksumAnim;
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
    static ServerRequirements serverRequirements(LauncherConfig config, ServerProfile profile)
            throws InterruptedException {
        ServerQuery.Result queried = ServerQuery.run(config, profile);
        if (queried != null) {
            checkStormVersionSkew(config, queried.stormVersion);
            if (!queried.workshopItems.isEmpty() || !queried.mods.isEmpty()) {
                return new ServerRequirements(
                        queried.workshopItems,
                        queried.mods,
                        queried.checksumLua,
                        queried.checksumScript,
                        queried.checksumAnim);
            }
        }
        ServerModList.Result probed = ServerModList.run(config, profile);
        if (probed == null) {
            Log.warn(
                    "No mod list from "
                            + profile.connectAddress()
                            + " — the game will start with no workshop java mods loaded.");
            return new ServerRequirements(Collections.emptyList(), null);
        }
        return new ServerRequirements(probed.workshopItems, probed.mods);
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
        return launch(config, profile, handoffActive, null);
    }

    public static Process launch(
            LauncherConfig config,
            ServerProfile profile,
            boolean handoffActive,
            List<String> serverMods)
            throws IOException {
        return launch(config, profile, handoffActive, serverMods, null, null);
    }

    public static Process launch(
            LauncherConfig config,
            ServerProfile profile,
            boolean handoffActive,
            List<String> serverMods,
            String joinChecksums,
            String joinFingerprint)
            throws IOException {
        GameLaunch.LaunchPlan plan =
                GameLaunch.plan(
                        config,
                        profile,
                        handoffActive ? LauncherPaths.autoJoinFile() : null,
                        serverMods,
                        joinChecksums,
                        joinFingerprint);
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

    /**
     * SHA-256 over everything that determines the client's join checksums: the installed Storm/game
     * version, the server's ordered mod list, and each required workshop item's installed {@code
     * timeupdated} out of Steam's appworkshop acf. Storm keys its script-checksum cache on this, so
     * a game update, mod-list change, or workshop item update invalidates the cache with no file
     * walking inside the game JVM. Null (no mod list, acf unreadable, digest unavailable) just
     * leaves the fast path unarmed.
     */
    static String contentFingerprint(LauncherConfig config, ServerRequirements required) {
        try {
            Path acf = WorkshopStaleScan.findAppWorkshopAcf(config);
            Map<String, Long> stamps =
                    acf == null || !Files.isRegularFile(acf)
                            ? null
                            : WorkshopStaleScan.parseInstalledTimestamps(
                                    Files.readString(acf, StandardCharsets.UTF_8));
            GameInstallState game = GameInstallState.read(config);
            return contentFingerprint(
                    localStormVersion(config),
                    game == null ? "" : game.buildId,
                    required.mods,
                    required.workshopItems,
                    stamps);
        } catch (Exception e) {
            Log.warn("Could not fingerprint local content: " + e.getMessage());
            return null;
        }
    }

    /**
     * Pure half of {@link #contentFingerprint(LauncherConfig, ServerRequirements)}. A null {@code
     * installedStamps} means the acf was unreadable: with required workshop items that forces null
     * (a fingerprint blind to their content would validate a stale cache); with none it just means
     * no workshop content contributes. {@code gameBuildId} covers the game's own files, which a
     * Steam patch rewrites without touching anything else here — Storm's version string only names
     * the build Storm was compiled for, which lags the patch until Storm is republished.
     */
    static String contentFingerprint(
            String version,
            String gameBuildId,
            List<String> mods,
            List<String> workshopItems,
            Map<String, Long> installedStamps)
            throws java.security.NoSuchAlgorithmException {
        if (mods == null) {
            return null;
        }
        if (installedStamps == null && !workshopItems.isEmpty()) {
            return null;
        }
        StringBuilder content = new StringBuilder();
        content.append("version=").append(version == null ? "" : version).append('\n');
        content.append("build=").append(gameBuildId == null ? "" : gameBuildId).append('\n');
        for (String mod : mods) {
            content.append("mod=").append(mod).append('\n');
        }
        if (installedStamps != null) {
            for (String item : workshopItems) {
                content.append("item=")
                        .append(item)
                        .append(':')
                        .append(installedStamps.getOrDefault(item, 0L))
                        .append('\n');
            }
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
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
