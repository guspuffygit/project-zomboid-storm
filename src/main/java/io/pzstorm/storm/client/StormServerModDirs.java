package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnServerWorkshopItemsEvent;
import io.pzstorm.storm.event.zomboid.OnLoadModsEvent;
import io.pzstorm.storm.util.StormEnv;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import zombie.ZomboidFileSystem;
import zombie.core.znet.SteamWorkshop;
import zombie.gameStates.ChooseGameInfo;
import zombie.gameStates.ConnectToServerState;
import zombie.network.GameClient;

/**
 * Pins every server-required mod id to the copy inside the <i>server's own</i> workshop items,
 * making that the copy the client checksums and loads — always.
 *
 * <p>Vanilla resolves a mod id to a folder with a global first-wins walk over <b>every</b>
 * installed workshop item ({@code ZomboidFileSystem.getAllModFolders} → {@code
 * ChooseGameInfo.getModDetails} → {@code setModIdToDir}, a {@code putIfAbsent}). When an unrelated
 * local item — a mega-pack, an old re-upload — ships a mod id the server also requires, Steam's
 * enumeration order decides which copy wins. The loser is silent; the symptom is a join-time
 * checksum kick naming a file inside the wrong item, unfixable from the UI.
 *
 * <p>The authoritative mapping is in the login payload: {@code ConnectToServerState} receives the
 * server's workshop item list in {@code WorkshopInit}, and {@code
 * LuaEventManager.triggerEvent("OnServerWorkshopItems", "Success")} fires on every Steam-mode
 * connect (even with zero items) right after the items are installed/updated and right before
 * {@code CheckMods} resolves mod dirs. This handler captures the item list there, scans each item's
 * {@code mods/} folder for mod ids, hoists those folders to the front of the installed-item search
 * order, and force-writes {@code modIdToDir} so the server's copies win. Re-applied on every {@code
 * OnLoadModsEvent} (fired at {@code loadMods(ArrayList)} entry) because connect-time {@code
 * ZomboidFileSystem.Reset()} clears the map and nulls the folder list.
 *
 * <p>Mods the server requires that live outside its workshop items (Zomboid/mods installs, staged
 * dev folders, translation mods) are untouched — they still resolve through the vanilla walk.
 *
 * <p>When a pin <i>overrides</i> an existing entry, the boot pass (see {@link StormJoinPrewarm})
 * loaded content from the wrong folder; {@link StormFastResetLua} consults {@link
 * #bootResolutionCorrected()} and declines the fast path so the vanilla reload rebuilds from the
 * corrected mapping. Every failure here is soft: vanilla resolution stands and the join proceeds
 * exactly as it would without Storm.
 */
public final class StormServerModDirs {

    /** Server mod-id → mod root dir inside a server workshop item; rebuilt on every connect. */
    private static volatile Map<String, String> serverModDirs = Map.of();

    /** Install roots of the server's own workshop items — the copies that must win every tie. */
    private static volatile List<Path> serverItemFolders = List.of();

    /**
     * Set when a pin replaced a different existing {@code modIdToDir} entry — the boot-time walk
     * resolved a server mod to a non-server copy, so boot-loaded content is suspect. Sticky for the
     * process: the fast ResetLua path is only ever available to the first connect anyway.
     */
    private static volatile boolean bootResolutionCorrected;

    private StormServerModDirs() {}

    /** True when a boot-time mod-dir resolution had to be corrected at connect time. */
    public static boolean bootResolutionCorrected() {
        return bootResolutionCorrected;
    }

    /**
     * "Success" fires exactly once per Steam-mode connect, after every server item is installed and
     * current, before {@code CheckMods} runs the first connect-time resolution.
     */
    @SubscribeEvent
    public static void onServerWorkshopItems(OnServerWorkshopItemsEvent event) {
        try {
            if (StormEnv.isStormServer() || !"Success".equals(event.state)) {
                return;
            }
            List<Long> itemIds = connectItemIds();
            List<Path> itemFolders = itemInstallFolders(itemIds);
            Map<String, String> dirs = scanItems(itemFolders);
            serverItemFolders = itemFolders;
            serverModDirs = dirs;
            if (dirs.isEmpty()) {
                return;
            }
            apply(dirs);
            LOGGER.info(
                    "Pinned {} mod dir(s) to the server's {} workshop item(s)",
                    dirs.size(),
                    itemIds.size());
        } catch (Throwable t) {
            LOGGER.error("Could not pin server workshop mod dirs — vanilla resolution stands", t);
        }
    }

    /**
     * {@code loadMods(ArrayList)} entry — re-pin after connect-time {@code
     * ZomboidFileSystem.Reset()} wiped {@code modIdToDir}, before this load resolves anything. The
     * {@code GameClient.client} gate keeps menu/SP loads (where vanilla takes the non-client
     * branch) on vanilla resolution.
     */
    @SubscribeEvent
    public static void onLoadMods(OnLoadModsEvent event) {
        try {
            Map<String, String> dirs = serverModDirs;
            if (dirs.isEmpty() || !GameClient.client) {
                return;
            }
            apply(dirs);
        } catch (Throwable t) {
            LOGGER.error("Could not re-pin server workshop mod dirs", t);
        }
    }

    /** The item ids the server sent in the login payload, in the server's order. */
    private static List<Long> connectItemIds() throws ReflectiveOperationException {
        List<Long> ids = new ArrayList<>();
        ConnectToServerState state = ConnectToServerState.instance;
        if (state == null) {
            return ids;
        }
        Field itemsField = ConnectToServerState.class.getDeclaredField("workshopItems");
        itemsField.setAccessible(true);
        for (Object item : (List<?>) itemsField.get(state)) {
            Field idField = item.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            ids.add(idField.getLong(item));
        }
        return ids;
    }

    private static List<Path> itemInstallFolders(List<Long> itemIds) {
        List<Path> folders = new ArrayList<>();
        for (long itemId : itemIds) {
            String folder = SteamWorkshop.instance.GetItemInstallFolder(itemId);
            if (folder == null || folder.isEmpty()) {
                LOGGER.warn("Server workshop item {} has no install folder — not pinning", itemId);
                continue;
            }
            Path path = Path.of(folder);
            if (Files.isDirectory(path)) {
                folders.add(path);
            }
        }
        return folders;
    }

    /**
     * Mod-id → mod root dir for every mod folder under the given items' {@code mods/} dirs. First
     * item wins a duplicate id, mirroring the server's own item order.
     */
    static Map<String, String> scanItems(List<Path> itemFolders) {
        Map<String, String> dirs = new LinkedHashMap<>();
        for (Path itemFolder : itemFolders) {
            Path modsDir = itemFolder.resolve("mods");
            if (!Files.isDirectory(modsDir)) {
                continue;
            }
            try (DirectoryStream<Path> mods =
                    Files.newDirectoryStream(modsDir, Files::isDirectory)) {
                for (Path modDir : mods) {
                    String modId = readModId(modDir);
                    if (modId != null) {
                        dirs.putIfAbsent(modId, modDir.toAbsolutePath().toString());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not scan workshop item mods dir {}: {}", modsDir, e.toString());
            }
        }
        return dirs;
    }

    /**
     * The {@code id=} value from the mod's {@code mod.info} — checked at the mod root (B41 layout)
     * and one level down (B42's {@code common/} and version dirs), matching where {@code
     * getAllModFoldersAux} accepts one. Null when no mod.info declares an id.
     */
    static @Nullable String readModId(Path modDir) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(modDir.resolve("mod.info"));
        try (DirectoryStream<Path> subDirs = Files.newDirectoryStream(modDir, Files::isDirectory)) {
            for (Path subDir : subDirs) {
                candidates.add(subDir.resolve("mod.info"));
            }
        } catch (Exception e) {
            return null;
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String content = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                for (String line : content.split("\r?\n")) {
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                    // same parse as vanilla ChooseGameInfo.readModInfoAux
                    if (line.startsWith("id=")) {
                        String id = line.replace("id=", "").trim();
                        if (!id.isEmpty()) {
                            return id;
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Could not read {}: {}", candidate, e.toString());
            }
        }
        return null;
    }

    /**
     * Force-writes the pins into {@code ZomboidFileSystem.modIdToDir}. Reflective because the only
     * public mutator is a {@code putIfAbsent} that cannot displace a wrong entry the boot walk
     * already made.
     */
    private static void apply(Map<String, String> dirs) throws ReflectiveOperationException {
        hoistServerItemFolders();
        clearModCaches();
        Field mapField = ZomboidFileSystem.class.getDeclaredField("modIdToDir");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> modIdToDir =
                (Map<String, String>) mapField.get(ZomboidFileSystem.instance);
        for (Map.Entry<String, String> entry : dirs.entrySet()) {
            String existing = modIdToDir.get(entry.getKey());
            if (existing != null && new File(existing).equals(new File(entry.getValue()))) {
                continue;
            }
            modIdToDir.put(entry.getKey(), entry.getValue());
            if (existing != null) {
                bootResolutionCorrected = true;
                LOGGER.warn(
                        "Mod '{}' had resolved to {} — overriding with the server's workshop item"
                                + " copy {}",
                        entry.getKey(),
                        existing,
                        entry.getValue());
            }
        }
    }

    /**
     * Moves the mod folders that belong to the server's own workshop items to the front of the
     * Steam-installed run of {@code ZomboidFileSystem.modFolders}.
     *
     * <p>Pinning {@code modIdToDir} is only half a fix, because two consumers disagree about where
     * a mod lives. {@code LuaManager.LoadDirBase} <i>walks</i> the folder reported by {@code
     * ChooseGameInfo.getAvailableModDetails(id)} but <i>resolves</i> every file it finds through
     * {@code activeFileMap}, which {@code loadMod} builds from {@code modIdToDir}. {@code
     * ChooseGameInfo.readModInfo} settles a duplicate-id tie by position in {@code
     * getAllModFolders()}, so a pin that is not reflected in that order gets re-derived back to the
     * shadowing copy on the next cache miss. The walk then yields a file only the shadowing copy
     * ships, {@code getAbsolutePath} returns null, and {@code ResetLua} dies on {@code couldn't
     * find "…"} — which leaves Lua half-built and the client on a blank screen. Correcting the
     * order instead makes every consumer agree, so a later re-derivation is harmless.
     *
     * <p>Only Steam-installed entries move. Staged workshop folders stay ahead of them and {@code
     * Zomboid/mods} stays behind, so a locally staged or user mod still overrides the server's
     * copy.
     */
    private static void hoistServerItemFolders() throws ReflectiveOperationException {
        List<Path> serverItems = serverItemFolders;
        List<Path> installedRoots = installedItemRoots();
        if (serverItems.isEmpty() || installedRoots.isEmpty()) {
            return;
        }
        // force the lazy build so we reorder the very list vanilla goes on to use
        ZomboidFileSystem.instance.getAllModFolders(new ArrayList<>());
        Field foldersField = ZomboidFileSystem.class.getDeclaredField("modFolders");
        foldersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> modFolders = (List<String>) foldersField.get(ZomboidFileSystem.instance);
        if (modFolders == null) {
            return;
        }
        List<Path> serverRoots = new ArrayList<>();
        for (Path item : serverItems) {
            serverRoots.add(normalize(item.toString()));
        }
        List<Integer> slots = new ArrayList<>();
        List<String> serverCopies = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (int i = 0; i < modFolders.size(); i++) {
            String folder = modFolders.get(i);
            if (!isUnder(normalize(folder), installedRoots)) {
                continue;
            }
            slots.add(i);
            (isUnder(normalize(folder), serverRoots) ? serverCopies : others).add(folder);
        }
        if (serverCopies.isEmpty() || others.isEmpty()) {
            return;
        }
        int slot = 0;
        for (String folder : serverCopies) {
            modFolders.set(slots.get(slot++), folder);
        }
        for (String folder : others) {
            modFolders.set(slots.get(slot++), folder);
        }
        LOGGER.info(
                "Hoisted {} server workshop mod folder(s) ahead of {} other installed folder(s)",
                serverCopies.size(),
                others.size());
    }

    /** Install roots of every subscribed workshop item, normalized for prefix tests. */
    private static List<Path> installedItemRoots() {
        List<Path> roots = new ArrayList<>();
        String[] folders = SteamWorkshop.instance.GetInstalledItemFolders();
        if (folders == null) {
            return roots;
        }
        for (String folder : folders) {
            if (folder != null && !folder.isEmpty()) {
                roots.add(normalize(folder));
            }
        }
        return roots;
    }

    /** Same normalization {@code ZomboidFileSystem.normalizeToPath} applies to allowed prefixes. */
    private static Path normalize(String path) {
        return Path.of(
                Path.of(path).normalize().toAbsolutePath().toString().toLowerCase(Locale.ROOT));
    }

    private static boolean isUnder(Path candidate, List<Path> roots) {
        for (Path root : roots) {
            if (candidate.startsWith(root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops every cached {@code ChooseGameInfo.Mod}. Wholesale rather than per corrected id: the
     * caches are keyed by mod id but the objects carry a folder, and any id the shadowing pack also
     * ships was resolved under the pre-hoist order.
     */
    private static void clearModCaches() throws ReflectiveOperationException {
        Field modsField = ChooseGameInfo.class.getDeclaredField("Mods");
        modsField.setAccessible(true);
        ((Map<?, ?>) modsField.get(null)).clear();
        Field missingField = ChooseGameInfo.class.getDeclaredField("MissingMods");
        missingField.setAccessible(true);
        ((Set<?>) missingField.get(null)).clear();
    }

    // test hook: pins and the correction flag are process-global
    static void resetForTests() {
        serverModDirs = Map.of();
        serverItemFolders = List.of();
        bootResolutionCorrected = false;
    }
}
