package io.pzstorm.storm.advice.serveroptionsmaxplayers;

import io.pzstorm.storm.connection.StormMaxPlayersConfig;
import net.bytebuddy.asm.Advice;

/**
 * Advice for {@code zombie.network.ServerOptions.getMaxPlayers()}.
 *
 * <p>The vanilla body is {@code return Math.min(254, getInstance().maxPlayers.getValue())} — a hard
 * ceiling on top of the option's own declared max of 254. The exit advice routes the vanilla return
 * value through {@link StormMaxPlayersConfig#overrideOrVanilla(int)}: while the {@code
 * Storm.OverrideMaxPlayers} sandbox option is off (the default) the vanilla value passes through
 * untouched; while on, every caller sees the {@code Storm.MaxPlayers} override instead. Rewriting
 * the single getter (rather than its call sites) makes every enforcement point — {@code
 * LoginPacket}, {@code LoginQueue}, {@code ConnectCoopPacket}, {@code QueuePacket}, {@code
 * ConnectionDetails} — read the live value at check time, which is what makes a sandbox push apply
 * without a restart.
 */
public class ServerOptionsGetMaxPlayersAdvice {

    @Advice.OnMethodExit
    public static void onExit(@Advice.Return(readOnly = false) int ret) {
        ret = StormMaxPlayersConfig.overrideOrVanilla(ret);
    }
}
