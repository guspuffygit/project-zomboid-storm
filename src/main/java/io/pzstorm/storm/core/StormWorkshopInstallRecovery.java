package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import zombie.core.znet.SteamWorkshop;
import zombie.network.GameServer;
import zombie.network.GameServerWorkshopItems;

/**
 * Rescues dedicated-server startup when {@code GameServerWorkshopItems.Install(...)} fails.
 *
 * <p>Vanilla treats any workshop download failure as fatal twice over: the Fail branch calls {@code
 * ZomboidFileSystem.deleteDirectory(GetItemInstallFolder(id))} which NPEs when the item was never
 * installed ({@code GetItemInstallFolder} returns null), and even without the NPE {@code
 * GameServer.main} does {@code if (!Install(...)) return;} - one unavailable item stops the whole
 * server. Items become unavailable for reasons an admin cannot fix from the server: the workshop
 * item was deleted or set to hidden/friends-only (anonymous game-server logins then get "Failed to
 * get manifest request code, 'Access Denied'" from Steam), or Steam denies the manifest for a
 * window right after the item was updated.
 *
 * <p>Recovery strategy, invoked from {@code GameServerWorkshopItemsInstallAdvice} when {@code
 * Install} returns false or throws:
 *
 * <ol>
 *   <li>Re-run {@code Install} up to {@code -Dstorm.workshop.retryAttempts} times (default 2),
 *       sleeping {@code -Dstorm.workshop.retryDelaySeconds} (default 15) before each attempt -
 *       covers the transient deny-window after a mod update.
 *   <li>If it still fails, drop every item with no install folder on disk from {@code
 *       GameServer.WorkshopItems} (mutated in place - it is the same list {@code Install} received)
 *       and rebuild {@code GameServer.workshopInstallFolders} / {@code workshopTimeStamps} from the
 *       surviving items, keeping all three structures index-aligned for {@code
 *       ConnectionDetails.writeWorkshopItems}.
 * </ol>
 *
 * <p>If nothing at all is installed on disk the failure is kept - starting a modded server with
 * zero of its configured workshop items is more likely a total Steam outage than a per-item
 * problem, and the supervisor retrying later is the better outcome.
 *
 * <p>Set {@code -Dstorm.workshop.requireAllItems=true} to restore the vanilla all-or-nothing
 * behavior (minus the NPE).
 */
public final class StormWorkshopInstallRecovery {

    private StormWorkshopInstallRecovery() {}

    /**
     * Guards against re-entrant recovery: the retry loop below re-invokes the patched {@code
     * Install}, whose exit advice calls {@link #recover} again. Startup is single-threaded so a
     * plain flag suffices.
     */
    private static boolean inRecovery = false;

    /**
     * Attempts to recover from a failed workshop install. Returns {@code true} when the caller (the
     * {@code Install} exit advice) should report success to {@code GameServer.main}.
     *
     * @param itemIDList the list passed to {@code Install} - the same object as {@code
     *     GameServer.WorkshopItems}, mutated in place when items are dropped.
     * @param failure the throwable that escaped {@code Install}, or null if it returned false.
     */
    public static boolean recover(ArrayList<Long> itemIDList, Throwable failure) {
        if (!StormEnv.isStormServer() || !GameServer.server || inRecovery) {
            return false;
        }
        if (Boolean.getBoolean("storm.workshop.requireAllItems")) {
            LOGGER.error(
                    "Workshop item install failed and -Dstorm.workshop.requireAllItems=true;"
                            + " keeping vanilla fail-fast behavior.",
                    failure);
            return false;
        }
        inRecovery = true;
        try {
            return doRecover(itemIDList, failure);
        } catch (Throwable t) {
            LOGGER.error("Storm workshop-install recovery itself failed; server will not start", t);
            return false;
        } finally {
            inRecovery = false;
        }
    }

    private static boolean doRecover(ArrayList<Long> itemIDList, Throwable failure) {
        if (failure != null) {
            LOGGER.error("Workshop item install threw (vanilla would have crashed here)", failure);
        } else {
            LOGGER.error("Workshop item install failed (vanilla would refuse to start here)");
        }

        // Ask Steam's public surfaces WHY each missing item is failing and surface it now,
        // before the retry sleeps - the steamclient error itself is the same for every cause.
        Map<Long, StormWorkshopItemProbe.ProbeResult> probes = new HashMap<>();
        boolean allPermanentlyGone = true;
        List<Long> missingNow = itemsWithoutInstallFolder(itemIDList);
        for (int i = 0; i < missingNow.size(); i++) {
            long id = missingNow.get(i);
            StormWorkshopItemProbe.ProbeResult result = StormWorkshopItemProbe.probe(id);
            probes.put(id, result);
            LOGGER.error(
                    "Workshop item {}{} could not be downloaded: {}",
                    id,
                    result.title == null ? "" : " (\"" + result.title + "\")",
                    result.verdict);
            allPermanentlyGone &= result.permanentlyGone;
        }

        int attempts = Integer.getInteger("storm.workshop.retryAttempts", 2);
        long delayMs = Integer.getInteger("storm.workshop.retryDelaySeconds", 15) * 1000L;
        if (!missingNow.isEmpty() && allPermanentlyGone) {
            LOGGER.warn(
                    "Every unavailable workshop item is confirmed gone from Steam"
                            + " (removed/deleted) - skipping the {} install retries.",
                    attempts);
            attempts = 0;
        }
        for (int attempt = 1; attempt <= attempts; attempt++) {
            LOGGER.warn(
                    "Retrying workshop item install in {}s (attempt {}/{})",
                    delayMs / 1000L,
                    attempt,
                    attempts);
            try {
                Thread.sleep(delayMs);
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

        List<Long> available = new ArrayList<>();
        List<String> folders = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        for (int i = 0; i < itemIDList.size(); i++) {
            long id = itemIDList.get(i);
            String folder = SteamWorkshop.instance.GetItemInstallFolder(id);
            if (folder != null && new File(folder).exists()) {
                available.add(id);
                folders.add(folder);
            } else {
                skipped.add(id);
            }
        }

        if (available.isEmpty() && !itemIDList.isEmpty()) {
            LOGGER.error(
                    "None of the {} configured workshop items are installed on disk - this looks"
                            + " like a total Steam failure, not a per-item one. Keeping the vanilla"
                            + " failure so the supervisor can retry later.",
                    itemIDList.size());
            return false;
        }

        for (int i = 0; i < skipped.size(); i++) {
            probes.computeIfAbsent(skipped.get(i), StormWorkshopItemProbe::probe);
        }
        emitSkipBanner(skipped, probes, available.size());

        itemIDList.clear();
        itemIDList.addAll(available);
        String[] folderArray = new String[available.size()];
        long[] timeStamps = new long[available.size()];
        for (int i = 0; i < available.size(); i++) {
            folderArray[i] = folders.get(i);
            timeStamps[i] = SteamWorkshop.instance.GetItemInstallTimeStamp(available.get(i));
        }
        GameServer.workshopInstallFolders = folderArray;
        GameServer.workshopTimeStamps = timeStamps;
        return true;
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

    private static void emitSkipBanner(
            List<Long> skipped,
            Map<Long, StormWorkshopItemProbe.ProbeResult> probes,
            int availableCount) {
        String bar =
                "################################################################################";
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(bar);
        lines.add(bar);
        lines.add("##");
        lines.add(
                "##  STORM: STARTING WITHOUT " + skipped.size() + " UNAVAILABLE WORKSHOP ITEM(S)");
        lines.add("##");
        lines.add("##  Steam refused to download the item(s) below (steamclient reports only");
        lines.add("##  'Access Denied'; see ~/Steam/logs/content_log.txt). Storm asked Steam's");
        lines.add("##  public API and each item's community page for the actual reason:");
        lines.add("##");
        for (int i = 0; i < skipped.size(); i++) {
            long id = skipped.get(i);
            StormWorkshopItemProbe.ProbeResult probe = probes.get(id);
            String title = probe == null || probe.title == null ? "" : "  \"" + probe.title + "\"";
            lines.add("##    " + id + title);
            lines.add("##      https://steamcommunity.com/sharedfiles/filedetails/?id=" + id);
            if (probe != null) {
                lines.add("##      -> " + probe.verdict);
            }
            lines.add("##");
        }
        lines.add("##  Mods provided only by these items will be missing this session.");
        lines.add(
                "##  Continuing startup with the " + availableCount + " item(s) present on disk.");
        lines.add("##  Set -Dstorm.workshop.requireAllItems=true to fail hard instead.");
        lines.add("##");
        lines.add(bar);
        lines.add(bar);
        lines.add("");
        for (int i = 0; i < lines.size(); i++) {
            System.out.println(lines.get(i));
            LOGGER.error(lines.get(i));
        }
    }
}
