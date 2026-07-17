package io.pzstorm.storm.diagnostics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.diagnostics.commands.RamAllocationCommand;
import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.zomboid.OnServerChatMessageEvent;
import io.pzstorm.storm.http.HttpEndpoint;
import io.pzstorm.storm.http.HttpRequestEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.chat.ChatMessage;
import zombie.network.GameServer;
import zombie.network.chat.ChatServer;

public class RamAllocationTracker {

    public record RamAllocationDto(String username, String steamId, long ramAllocation) {}

    private record Snapshot(long steamId, long ramAllocationMb) {}

    // 3 GiB on the same 1073 MB-per-GiB scale StormRamAllocation.lua uses for its 4 GiB modal.
    private static final long LOW_RAM_THRESHOLD_MB = 3221;
    private static final long LAG_NAG_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(5);
    private static final String MODULE = "StormDiagnostics";
    private static final String SHOW_RAM_ALLOC_COMMAND = "showRamAlloc";

    private static final Pattern LAG_PATTERN =
            Pattern.compile(
                    "\\b(?:lag\\w*|stutter\\w*|freez\\w*|chopp?y|rubber ?band\\w*|desync\\w*"
                            + "|fps|frame ?rate|slideshow|unplayable)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> LAG_NAG_TIMESTAMPS =
            new ConcurrentHashMap<>();

    @OnClientCommand
    public static void onRamAlloc(RamAllocationCommand event) {
        IsoPlayer player = event.getPlayer();
        if (player == null || player.getUsername() == null) {
            return;
        }
        Snapshot snap = new Snapshot(player.getSteamID(), event.getMaxMb());
        SNAPSHOTS.put(player.getUsername(), snap);
        LOGGER.debug(
                "ramAlloc: {} steamId={} ramAllocation={}MB",
                player.getUsername(),
                snap.steamId,
                snap.ramAllocationMb);
    }

    static boolean mentionsLag(String text) {
        return LAG_PATTERN.matcher(text).find();
    }

    @SubscribeEvent
    public static void onServerChatMessage(OnServerChatMessageEvent event) {
        ChatMessage message = event.getMessage();
        if (message == null || message.isServerAuthor() || message.isFromDiscord()) {
            return;
        }
        String username = message.getAuthor();
        String text = message.getText();
        if (username == null || text == null || !mentionsLag(text)) {
            return;
        }
        Snapshot snap = SNAPSHOTS.get(username);
        if (snap == null || snap.ramAllocationMb > LOW_RAM_THRESHOLD_MB) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastNag = LAG_NAG_TIMESTAMPS.get(username);
        if (lastNag != null && now - lastNag < LAG_NAG_COOLDOWN_MS) {
            return;
        }
        LAG_NAG_TIMESTAMPS.put(username, now);

        if (ChatServer.isInited()) {
            ChatServer.getInstance()
                    .sendMessageToServerChat(
                            String.format(
                                    "%s may be lagging because their game only has %d MB of RAM"
                                            + " allocated. %s: follow the instructions in the RAM"
                                            + " allocation window on your screen to fix this.",
                                    username, snap.ramAllocationMb, username));
        }

        IsoPlayer player = GameServer.getPlayerByUserName(username);
        if (player != null) {
            KahluaTable args = LuaManager.platform.newTable();
            args.rawset("maxMb", (double) snap.ramAllocationMb);
            GameServer.sendServerCommand(player, MODULE, SHOW_RAM_ALLOC_COMMAND, args);
        }
        LOGGER.debug(
                "lag complaint: nagged {} about low RAM allocation ({}MB)",
                username,
                snap.ramAllocationMb);
    }

    @HttpEndpoint(path = "/storm/ram-allocations")
    public static void getRamAllocations(HttpRequestEvent event) throws IOException {
        List<RamAllocationDto> dtos = new ArrayList<>(SNAPSHOTS.size());
        for (var entry : SNAPSHOTS.entrySet()) {
            Snapshot s = entry.getValue();
            dtos.add(
                    new RamAllocationDto(
                            entry.getKey(), Long.toString(s.steamId), s.ramAllocationMb));
        }
        event.sendJson(200, MAPPER.writeValueAsString(dtos));
    }
}
