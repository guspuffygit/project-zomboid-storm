package io.pzstorm.storm.diagnostics;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pzstorm.storm.diagnostics.commands.RamAllocationCommand;
import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.http.HttpEndpoint;
import io.pzstorm.storm.http.HttpRequestEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import zombie.characters.IsoPlayer;

public class RamAllocationTracker {

    public record RamAllocationDto(String username, String steamId, long ramAllocation) {}

    private record Snapshot(long steamId, long ramAllocationMb) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<String, Snapshot> SNAPSHOTS = new ConcurrentHashMap<>();

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

    @HttpEndpoint(path = "/storm/ram-allocations")
    public static void getRamAllocations(HttpRequestEvent event) throws IOException {
        List<RamAllocationDto> dtos = new ArrayList<>(SNAPSHOTS.size());
        for (var entry : SNAPSHOTS.entrySet()) {
            Snapshot s = entry.getValue();
            dtos.add(new RamAllocationDto(
                    entry.getKey(), Long.toString(s.steamId), s.ramAllocationMb));
        }
        event.sendJson(200, MAPPER.writeValueAsString(dtos));
    }
}
