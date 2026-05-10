package io.pzstorm.storm.los;

import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.EveryOneMinuteEvent;
import io.pzstorm.storm.logging.StormLogger;
import io.pzstorm.storm.los.commands.LOSReportCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Stack;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.iso.IsoMovingObject;
import zombie.network.GameServer;

/**
 * Server-only ingestion + parity-diff handler for the client-Lua LOS rollout.
 *
 * <p>Receives per-tick {@code storm_los:report} commands and stores them in {@link
 * PlayerLOSReportCache}. Once per in-game minute, walks every connected player and emits a {@code
 * [LOS-diff]} log line comparing the server's just-computed {@code spottedList} against the cached
 * client view.
 */
public final class PlayerLOSReportHandler {

    private PlayerLOSReportHandler() {}

    @OnClientCommand
    public static void onLOSReport(LOSReportCommand event) {
        if (!GameServer.server) {
            return;
        }
        IsoPlayer sender = event.getPlayer();
        if (sender == null) {
            return;
        }

        short playerId = event.getPlayerOnlineID();
        if (playerId == (short) -1) {
            playerId = sender.getOnlineID();
        }

        KahluaTable entries = event.getEntriesTable();
        short[] ids;
        boolean[] couldSee;
        boolean[] canSee;
        if (entries != null) {
            int n = entries.len();
            ids = new short[n];
            couldSee = new boolean[n];
            canSee = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object eObj = entries.rawget(i + 1);
                if (!(eObj instanceof KahluaTable e)) {
                    continue;
                }
                Number idNum = (Number) e.rawget("id");
                Object cs = e.rawget("couldSee");
                Object sn = e.rawget("canSee");
                ids[i] = idNum != null ? idNum.shortValue() : (short) -1;
                couldSee[i] = cs instanceof Boolean cb && cb;
                canSee[i] = sn instanceof Boolean sb && sb;
            }
        } else {
            ids = new short[0];
            couldSee = new boolean[0];
            canSee = new boolean[0];
        }

        PlayerLOSReportCache.Report report =
                new PlayerLOSReportCache.Report(
                        playerId,
                        event.getTick(),
                        event.getWallMs(),
                        System.currentTimeMillis(),
                        event.isSelfSpotted(),
                        event.isTruncated(),
                        ids,
                        couldSee,
                        canSee);
        PlayerLOSReportCache.INSTANCE.put(report);
    }

    @SubscribeEvent
    public static void onEveryOneMinute(EveryOneMinuteEvent event) {
        if (!GameServer.server) {
            return;
        }
        ArrayList<IsoPlayer> players = GameServer.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            IsoPlayer player = players.get(i);
            if (player == null) {
                continue;
            }
            emitFor(player);
        }
    }

    private static void emitFor(IsoPlayer player) {
        short id = player.getOnlineID();
        Stack<IsoMovingObject> spotted = player.getSpottedList();

        ArrayList<Short> serverIdsList = new ArrayList<>(spotted.size());
        for (int i = 0; i < spotted.size(); i++) {
            IsoMovingObject obj = spotted.get(i);
            if (obj == player) {
                continue;
            }
            short objId = idOf(obj);
            if (objId != (short) -1) {
                serverIdsList.add(objId);
            }
        }
        short[] serverIds = toShortArray(serverIdsList);
        Arrays.sort(serverIds);

        int numVisibleZombies = player.getStats().numVisibleZombies;
        int numSurvivorsInVicinity = player.getNumSurvivorsInVicinity();

        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.get(id);
        if (report == null) {
            StormLogger.LOGGER.info(
                    "[LOS-diff] player={} server={{size={},ids={},vz={},sv={}}} client=NONE",
                    id,
                    serverIds.length,
                    formatIds(serverIds),
                    numVisibleZombies,
                    numSurvivorsInVicinity);
            return;
        }

        short[] clientIds = report.ids.clone();
        Arrays.sort(clientIds);

        HashSet<Short> serverSet = new HashSet<>(serverIds.length * 2);
        for (short s : serverIds) {
            serverSet.add(s);
        }
        HashSet<Short> clientSet = new HashSet<>(clientIds.length * 2);
        for (short s : clientIds) {
            clientSet.add(s);
        }

        ArrayList<Short> onlyServer = new ArrayList<>();
        for (short s : serverIds) {
            if (!clientSet.contains(s)) {
                onlyServer.add(s);
            }
        }
        ArrayList<Short> onlyClient = new ArrayList<>();
        for (short s : clientIds) {
            if (!serverSet.contains(s)) {
                onlyClient.add(s);
            }
        }
        int agree = serverIds.length - onlyServer.size();
        long ageMs = System.currentTimeMillis() - report.arrivedMs;

        StormLogger.LOGGER.info(
                "[LOS-diff] clientTick={} player={} server={{size={},ids={},vz={},sv={}}}"
                        + " client={{size={},ids={},selfSpotted={},truncated={},ageMs={}}} agree={}"
                        + " only_server={} only_client={}",
                report.clientTick,
                id,
                serverIds.length,
                formatIds(serverIds),
                numVisibleZombies,
                numSurvivorsInVicinity,
                clientIds.length,
                formatIds(clientIds),
                report.selfSpotted,
                report.truncated,
                ageMs,
                agree,
                formatList(onlyServer),
                formatList(onlyClient));
    }

    private static short idOf(IsoMovingObject obj) {
        if (obj instanceof IsoPlayer p) {
            return p.getOnlineID();
        }
        if (obj instanceof IsoZombie z) {
            return z.getOnlineID();
        }
        return (short) -1;
    }

    private static short[] toShortArray(ArrayList<Short> list) {
        short[] out = new short[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static String formatIds(short[] ids) {
        if (ids.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(ids.length * 6);
        sb.append('[');
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private static String formatList(ArrayList<Short> ids) {
        if (ids.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(ids.size() * 6);
        sb.append('[');
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        sb.append(']');
        return sb.toString();
    }
}
