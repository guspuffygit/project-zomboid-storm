package io.pzstorm.storm.advice.sendworldmapplayerposition;

import io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo;
import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;

/**
 * Answers {@code GameServer.shouldSendWorldMapPlayerPosition(UdpConnection, IsoPlayer)} from {@link
 * StormWorldMapVisibilityMemo} while a batch is open; otherwise the vanilla body runs.
 *
 * <p>Encoding: enter returns {@code 0} to run vanilla, {@code 1} for a memoized false, {@code 2}
 * for a memoized true; non-zero skips the body and exit writes the return value.
 */
public class ShouldSendWorldMapPlayerPositionAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.Argument(0) UdpConnection connection, @Advice.Argument(1) IsoPlayer player) {
        int result = StormWorldMapVisibilityMemo.evaluate(connection, player);
        return result < 0 ? 0 : result + 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code, @Advice.Return(readOnly = false) boolean ret) {
        if (code != 0) {
            ret = code == 2;
        }
    }
}
