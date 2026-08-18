package io.pzstorm.storm.advice.client.playerdatarequestbackoff;

import io.pzstorm.storm.connection.StormPlayerDataRequestBackoff;
import net.bytebuddy.asm.Advice;
import zombie.network.PacketTypes;

/**
 * Skips the body of {@code INetworkPacket.send(PacketType, Object...)} when a {@code
 * PlayerDataRequest} for the same online id was already sent within the backoff window. All other
 * packet types fall through untouched. {@code suppress = Throwable.class} makes any advice failure
 * resolve to the default {@code false} — vanilla send proceeds.
 */
public class PlayerDataRequestBackoffAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static boolean onEnter(
            @Advice.Argument(0) PacketTypes.PacketType packetType,
            @Advice.Argument(1) Object[] values) {
        return packetType == PacketTypes.PacketType.PlayerDataRequest
                && StormPlayerDataRequestBackoff.shouldSuppress(values);
    }
}
