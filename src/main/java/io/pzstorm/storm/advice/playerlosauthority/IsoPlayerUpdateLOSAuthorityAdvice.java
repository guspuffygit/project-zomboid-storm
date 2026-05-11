package io.pzstorm.storm.advice.playerlosauthority;

import io.pzstorm.storm.los.PlayerLOSAuthorityManager;
import io.pzstorm.storm.los.PlayerLOSReportApplier;
import io.pzstorm.storm.los.PlayerLOSReportCache;
import io.pzstorm.storm.metrics.PlayerUpdateLOSAuthorityMetrics;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.network.GameServer;

/**
 * Server-side skip advice on {@code IsoPlayer.updateLOS}: when the player is "solo" per {@link
 * PlayerLOSAuthorityManager} and we have a fresh, non-truncated client LOS report cached, replay
 * the report's keep-list via {@link PlayerLOSReportApplier} and skip the vanilla O(N) cell scan.
 *
 * <p>All other cases fall through to vanilla {@code updateLOS}. Each decision is counted in {@link
 * PlayerUpdateLOSAuthorityMetrics}.
 */
public class IsoPlayerUpdateLOSAuthorityAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(@Advice.This IsoPlayer player) {
        if (!GameServer.server) {
            return 0;
        }
        short id = player.getOnlineID();
        if (!PlayerLOSAuthorityManager.INSTANCE.isSolo(id)) {
            PlayerUpdateLOSAuthorityMetrics.recordFellBackGrouped();
            return 0;
        }
        PlayerLOSReportCache.Report report = PlayerLOSReportCache.INSTANCE.getLatest(id);
        if (report == null) {
            PlayerUpdateLOSAuthorityMetrics.recordFellBackNoReport();
            return 0;
        }
        if (report.truncated) {
            PlayerUpdateLOSAuthorityMetrics.recordFellBackTruncated();
            return 0;
        }
        PlayerLOSReportApplier.apply(player, report);
        PlayerUpdateLOSAuthorityMetrics.recordTakenAuthority();
        return 1;
    }
}
