package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import zombie.core.znet.SteamWorkshop;
import zombie.network.GameServer;
import zombie.network.GameServerWorkshopItems;

/**
 * Diagnoses and retries a failed {@code GameServerWorkshopItems.Install(...)} before the dedicated
 * server gives up on starting.
 *
 * <p>Vanilla treats a workshop download failure as fatal but gives the admin nothing to act on: the
 * steamclient reports the same "Failed to get manifest request code, 'Access Denied'" for every
 * root cause, and the install-fail branch NPEs through {@code
 * ZomboidFileSystem.deleteDirectory(null)} before the failure is even reported (null-guarded by
 * {@code ZomboidFileSystemPatch.DeleteDirectoryGuardAdvice}).
 *
 * <p>Invoked from {@code GameServerWorkshopItemsInstallAdvice} when {@code Install} returns false
 * or throws:
 *
 * <ol>
 *   <li>Probes every configured item missing from disk via {@link StormWorkshopItemProbe} and logs
 *       WHY it cannot be downloaded (not anonymously visible - hidden/unlisted/removed - or the
 *       transient manifest deny-window right after an item update).
 *   <li>Re-runs {@code Install} up to {@value #RETRY_ATTEMPTS} times, {@value #RETRY_DELAY_SECONDS}
 *       seconds apart - enough to ride out the transient deny-window.
 *   <li>If the install still fails, prints a banner with the per-item diagnosis and remediation and
 *       keeps the failure: the server does not start without all of its configured workshop items,
 *       matching vanilla.
 * </ol>
 */
public final class StormWorkshopInstallRecovery {

    private StormWorkshopInstallRecovery() {}

    private static final int RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_SECONDS = 20;

    /**
     * Guards against re-entrant recovery: the retry loop below re-invokes the patched {@code
     * Install}, whose exit advice calls {@link #recover} again. Startup is single-threaded so a
     * plain flag suffices.
     */
    private static boolean inRecovery = false;

    /**
     * Attempts to recover from a failed workshop install. Returns {@code true} only when a retry
     * made {@code Install} succeed, in which case the caller (the {@code Install} exit advice)
     * reports success to {@code GameServer.main}; on {@code false} the failure stands and the
     * server does not start.
     *
     * @param itemIDList the list passed to {@code Install} ({@code GameServer.WorkshopItems}).
     * @param failure the throwable that escaped {@code Install}, or null if it returned false.
     */
    public static boolean recover(ArrayList<Long> itemIDList, Throwable failure) {
        if (!StormEnv.isStormServer() || !GameServer.server || inRecovery) {
            return false;
        }
        inRecovery = true;
        try {
            return doRecover(itemIDList, failure);
        } catch (Throwable t) {
            LOGGER.error("Storm workshop-install diagnosis failed; server will not start", t);
            return false;
        } finally {
            inRecovery = false;
        }
    }

    private static boolean doRecover(ArrayList<Long> itemIDList, Throwable failure) {
        if (failure != null) {
            LOGGER.error("Workshop item install threw", failure);
        } else {
            LOGGER.error("Workshop item install failed");
        }

        // Ask Steam's anonymous Web API WHY each missing item is failing and surface it now,
        // before the retry sleeps - the steamclient error itself is the same for every cause.
        List<Long> missingNow = itemsWithoutInstallFolder(itemIDList);
        Map<Long, StormWorkshopItemProbe.ProbeResult> probes =
                StormWorkshopItemProbe.probeAll(missingNow);
        for (int i = 0; i < missingNow.size(); i++) {
            long id = missingNow.get(i);
            StormWorkshopItemProbe.ProbeResult result = probes.get(id);
            LOGGER.error(
                    "Workshop item {}{} could not be downloaded: {}",
                    id,
                    result.title == null ? "" : " (\"" + result.title + "\")",
                    result.verdict);
        }

        for (int attempt = 1; attempt <= RETRY_ATTEMPTS; attempt++) {
            LOGGER.warn(
                    "Retrying workshop item install in {}s (attempt {}/{})",
                    RETRY_DELAY_SECONDS,
                    attempt,
                    RETRY_ATTEMPTS);
            try {
                Thread.sleep(RETRY_DELAY_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                if (GameServerWorkshopItems.Install(itemIDList)) {
                    LOGGER.info("Workshop item install succeeded on retry {}", attempt);
                    return true;
                }
            } catch (Throwable t) {
                LOGGER.warn("Workshop install retry {} threw: {}", attempt, t.toString());
            }
        }

        List<Long> missing = itemsWithoutInstallFolder(itemIDList);
        List<Long> unprobed = new ArrayList<>();
        for (int i = 0; i < missing.size(); i++) {
            if (!probes.containsKey(missing.get(i))) {
                unprobed.add(missing.get(i));
            }
        }
        probes.putAll(StormWorkshopItemProbe.probeAll(unprobed));
        emitFailureBanner(missing, probes);
        return false;
    }

    /** Items from the list whose install folder is absent on disk. */
    private static List<Long> itemsWithoutInstallFolder(List<Long> itemIDList) {
        List<Long> missing = new ArrayList<>();
        for (int i = 0; i < itemIDList.size(); i++) {
            long id = itemIDList.get(i);
            String folder = SteamWorkshop.instance.GetItemInstallFolder(id);
            if (folder == null || !new File(folder).exists()) {
                missing.add(id);
            }
        }
        return missing;
    }

    private static void emitFailureBanner(
            List<Long> missing, Map<Long, StormWorkshopItemProbe.ProbeResult> probes) {
        String bar =
                "################################################################################";
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(bar);
        lines.add(bar);
        lines.add("##");
        lines.add("##  STORM: SERVER START ABORTED - WORKSHOP ITEM INSTALL FAILED");
        lines.add("##");
        if (missing.isEmpty()) {
            lines.add("##  Storm could not identify a specific unavailable item (every configured");
            lines.add(
                    "##  item has an install folder on disk). Check ~/Steam/logs/content_log.txt");
            lines.add("##  for the underlying steamclient error.");
            lines.add("##");
        } else {
            lines.add("##  Steam refused to download the item(s) below (steamclient reports only");
            lines.add(
                    "##  'Access Denied'; see ~/Steam/logs/content_log.txt). Storm asked Steam's");
            lines.add("##  anonymous Web API for each item's status:");
            lines.add("##");
            for (int i = 0; i < missing.size(); i++) {
                long id = missing.get(i);
                StormWorkshopItemProbe.ProbeResult probe = probes.get(id);
                String title =
                        probe == null || probe.title == null ? "" : "  \"" + probe.title + "\"";
                lines.add("##    " + id + title);
                lines.add("##      https://steamcommunity.com/sharedfiles/filedetails/?id=" + id);
                if (probe != null) {
                    lines.add("##      -> " + probe.verdict);
                }
                lines.add("##");
            }
            lines.add("##  Fix the item on Steam's side, or remove its ID from WorkshopItems= in");
            lines.add("##  the server ini, then start the server again.");
            lines.add("##");
        }
        lines.add(bar);
        lines.add(bar);
        lines.add("");
        for (int i = 0; i < lines.size(); i++) {
            System.out.println(lines.get(i));
            LOGGER.error(lines.get(i));
        }
    }
}
