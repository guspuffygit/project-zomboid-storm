package io.pzstorm.storm.los;

import io.pzstorm.storm.event.core.OnClientCommand;
import io.pzstorm.storm.los.commands.LOSReportCommand;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Server-only ingestion handler for the client-Lua LOS rollout. Receives per-tick {@code
 * storm_los:report} commands and stores them in {@link PlayerLOSReportCache} for Phase 4's {@code
 * updateLOS} substitution to consume.
 *
 * <p><b>Thread contract:</b> {@link #onLOSReport} runs on the GameServer main update thread. UDP
 * packets are queued by {@code UdpEngine} on its receiver thread but drained on the main thread via
 * {@code mainLoopDealWithNetData} before {@code ClientCommandDispatcher} fires this handler, so the
 * {@link se.krka.kahlua.vm.KahluaTable} reads here happen on the same thread that produced the
 * table.
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
}
