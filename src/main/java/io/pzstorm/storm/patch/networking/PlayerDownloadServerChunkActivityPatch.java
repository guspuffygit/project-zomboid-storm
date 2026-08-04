package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Feeds the stalled-connection reaper's chunk-download exemption: without this stamp the reaper
 * cannot tell a client actively streaming the world (a state that legitimately outlasts the connect
 * budget on slow links) from a pre-spawn camper, because both classify as {@code awaiting_spawn}
 * and both stay chatty at the RakNet layer. Reaping the former is worse than a wasted download — a
 * mid-load disconnect crashes vanilla clients with an NPE from {@code IsoCell.LoadPlayer} (see
 * {@code StalledConnectionReaper}'s class doc).
 *
 * <p>Single hook: enter advice on {@code PlayerDownloadServer.getClientChunkRequest}, which every
 * chunk-request wave allocates through. Sampling server-side queue state from the sweep instead
 * would not work: {@code sendChunk} pushes into RakNet without waiting for client ACKs, so on a
 * slow link the queue drains long before the client has the data.
 *
 * <p>Registration is gated on {@code StormEnv.isStormServer()} — a co-op host's client JVM can load
 * {@code PlayerDownloadServer} through {@code zombie.spnetwork}, and this patch must never apply
 * there.
 */
public class PlayerDownloadServerChunkActivityPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.gameserverstalledconnections"
                    + ".PlayerDownloadServerChunkActivityAdvice";

    public PlayerDownloadServerChunkActivityPatch() {
        super("zombie.network.PlayerDownloadServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(
                                ElementMatchers.named("getClientChunkRequest")
                                        .and(ElementMatchers.takesArguments(1))));
    }
}
