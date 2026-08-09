package io.pzstorm.storm.advice.client.vehiclerequestmerge;

import net.bytebuddy.asm.Advice;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameClient;

/**
 * OR-merges the pending request flags for a vehicle instead of letting {@code
 * VehicleManager.sendVehicleRequest}'s {@code vehicleRequests.put(id, flag)} overwrite them.
 *
 * <p>Vanilla clobber: a Full request ({@code flag=1}) queued by a recovery path can be overwritten
 * by {@code VehicleManager.clientUpdate}'s 1&nbsp;Hz housekeeping request ({@code flag=16384},
 * Passengers) for the same vehicle before the 100&nbsp;ms {@code GameClient} flush sends the batch
 * — the server then never sets the Full bit and the client never receives the {@code
 * VehicleFullUpdate} it asked for. Merging preserves every requested bit; the server's {@code flag
 * == 16384} relevance-probe branch is unaffected because a merged value is never exactly 16384, and
 * {@code state.flags |= flag} handles combined bits natively.
 */
public class VehicleRequestMergeAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(
            @Advice.Argument(0) short vehicleId,
            @Advice.Argument(value = 1, readOnly = false) short flag) {
        if (!GameClient.client) {
            return;
        }
        UdpConnection connection = GameClient.connection;
        if (connection == null) {
            return;
        }
        short pending = connection.vehicleRequests.get(vehicleId);
        if (pending != 0 && pending != flag) {
            flag = (short) (pending | flag);
        }
    }
}
