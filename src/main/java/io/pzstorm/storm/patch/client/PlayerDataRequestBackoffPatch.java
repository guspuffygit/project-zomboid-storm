package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Client-only. Adds a per-target-id backoff to {@code PlayerDataRequest} sends by advising the
 * static client send path {@code INetworkPacket.send(PacketType, Object...)} — the single choke
 * point used by all vanilla request sites ({@code PlayerPacket.processClient} twice, {@code
 * VehiclePassengers.parsePlayer}).
 *
 * <p>Vanilla clients re-fire {@code PlayerDataRequest} on every received {@code PlayerPacket}
 * carrying an unknown online id, and the server refuses irrelevant/invisible targets silently, so
 * any relay of a non-resolvable player's updates becomes an unbounded request/refusal loop (96% of
 * inbound packet volume on a production server at peak). See {@link
 * io.pzstorm.storm.connection.StormPlayerDataRequestBackoff} for the backoff semantics.
 *
 * <p>Why a client bytecode patch: the request fires from compiled packet-parse code with no Lua or
 * event surface between the unknown-id check and the send. Fail-soft: the advice suppresses its own
 * exceptions and defaults to vanilla behavior, and the gate fails open on any unexpected argument
 * shape. Re-validate on game updates: the matcher assumes the 2-arg static {@code send} overload
 * remains the client-side entry (checked against 42.20.x).
 */
public class PlayerDataRequestBackoffPatch extends StormClassTransformer {

    private static final String PKG = "io.pzstorm.storm.advice.client.playerdatarequestbackoff.";

    public PlayerDataRequestBackoffPatch() {
        super("zombie.network.packets.INetworkPacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "PlayerDataRequestBackoffAdvice").resolve(),
                                locator)
                        .on(
                                ElementMatchers.named("send")
                                        .and(ElementMatchers.isStatic())
                                        .and(ElementMatchers.takesArguments(2))));
    }
}
