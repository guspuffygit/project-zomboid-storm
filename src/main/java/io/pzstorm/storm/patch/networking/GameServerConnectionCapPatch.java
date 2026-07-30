package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.RakNetConnectionCapConfig;
import io.pzstorm.storm.core.StormClassTransformer;
import java.net.ConnectException;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.raknet.UdpEngine;
import zombie.network.ServerOptions;

/**
 * Gives the dedicated server's RakNet peer real headroom above {@code MaxPlayers} instead of the
 * vanilla hard-coded 101 incoming-connection cap.
 *
 * <p>The substitution is scoped to {@code new UdpEngine(...)} calls occurring inside {@code
 * GameServer.startServer()}, which is the server's single listening peer. {@code
 * GameClient.startClient()} builds its own {@code new UdpEngine(..., 1, null, false)} and is a
 * different class entirely, so it is untouched — and this patch is registration-gated server-only
 * regardless.
 *
 * <p>See {@link RakNetConnectionCapConfig} for why the cap matters (clients wedged on "Getting
 * Server Info..." once the peer fills), why extra slots cannot admit players over {@code
 * MaxPlayers}, and the {@code SlotToConnection[512]} ceiling.
 *
 * <p>Raising the cap alone does not stop half-open connections leaking slots — {@link
 * io.pzstorm.storm.connection.StormStalledConnectionReaper} is the fix for the leak itself. This
 * patch buys the login pipeline enough room that a leak has to be much larger before it bites.
 */
public class GameServerConnectionCapPatch extends StormClassTransformer {

    public GameServerConnectionCapPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .constructor(
                                    ElementMatchers.isDeclaredBy(UdpEngine.class)
                                            .and(
                                                    ElementMatchers.takesArguments(
                                                            int.class,
                                                            int.class,
                                                            int.class,
                                                            String.class,
                                                            boolean.class)))
                            .replaceWith(
                                    UdpEngineFactory.class.getDeclaredMethod(
                                            "create",
                                            int.class,
                                            int.class,
                                            int.class,
                                            String.class,
                                            boolean.class))
                            .on(ElementMatchers.named("startServer")));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for GameServer RakNet connection cap", e);
        }
    }

    /** Replacement for {@code new UdpEngine(int,int,int,String,boolean)} in {@code startServer}. */
    public static class UdpEngineFactory {

        private static volatile int appliedCap;

        /** Cap actually handed to RakNet, or {@code 0} before {@code startServer} has run. */
        public static int getAppliedCap() {
            return appliedCap;
        }

        public static UdpEngine create(
                int port, int udpPort, int maxConnections, String serverPassword, boolean bListen)
                throws ConnectException {

            int maxPlayers = readMaxPlayers();
            int cap = RakNetConnectionCapConfig.resolveCap(maxConnections, maxPlayers);
            appliedCap = cap;

            if (cap != maxConnections) {
                LOGGER.info(
                        "Storm: RakNet incoming-connection cap raised {} -> {} (MaxPlayers={},"
                                + " login-pipeline headroom={}). Extra slots cannot become players"
                                + " — MaxPlayers is still enforced by LoginPacket.",
                        maxConnections,
                        cap,
                        maxPlayers,
                        cap - maxPlayers);
            } else {
                LOGGER.info(
                        "Storm: RakNet incoming-connection cap left at vanilla {} (MaxPlayers={})",
                        cap,
                        maxPlayers);
            }
            return new UdpEngine(port, udpPort, cap, serverPassword, bListen);
        }

        private static int readMaxPlayers() {
            try {
                return ServerOptions.getInstance().getMaxPlayers();
            } catch (Throwable t) {
                LOGGER.warn(
                        "Storm: could not read ServerOptions MaxPlayers for the RakNet cap;"
                                + " falling back to the vanilla cap",
                        t);
                return 0;
            }
        }
    }
}
