package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.RakNetConnectionCapConfig;
import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.metrics.StormConnectionStageMetrics;
import io.pzstorm.storm.sandbox.StormPerformanceSandboxApplier;
import java.net.ConnectException;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Gives the dedicated server's RakNet peer real headroom above {@code MaxPlayers} instead of the
 * vanilla hard-coded incoming-connection cap (101 through 42.20.2; 255 since 42.20.3, which
 * absorbed most of this fix — the resolved cap now usually matches vanilla and this patch mainly
 * publishes the cap metrics and guards future regressions).
 *
 * <p>The substitution is scoped to {@code new UdpEngine(...)} calls occurring inside {@code
 * GameServer.startServer()}, which is the server's single listening peer. {@code
 * GameClient.startClient()} builds its own {@code new UdpEngine(..., 1, null, false)} and is a
 * different class entirely, so it is untouched — and this patch is registration-gated server-only
 * regardless.
 *
 * <p>See {@link RakNetConnectionCapConfig} for why the cap matters (clients wedged on "Getting
 * Server Info..." once the peer fills), why extra slots cannot admit players over {@code
 * MaxPlayers}, and the two hard ceilings (byte-wide wire index, {@code SlotToConnection.length}).
 *
 * <p>Raising the cap alone does not stop half-open connections leaking slots — {@link
 * io.pzstorm.storm.advice.gameserverstalledconnections.StalledConnectionReaper} is the fix for the
 * leak itself. This patch buys the login pipeline enough room that a leak has to be much larger
 * before it bites.
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

            int cap;
            int maxPlayers;
            try {
                maxPlayers = readMaxPlayers();
                cap =
                        RakNetConnectionCapConfig.resolveCap(
                                maxConnections, maxPlayers, readSlotTableLength());
            } catch (Throwable t) {
                LOGGER.error(
                        "Storm: RakNet cap resolution failed; using the vanilla cap {}",
                        maxConnections,
                        t);
                applyCap(maxConnections, true);
                return new UdpEngine(port, udpPort, maxConnections, serverPassword, bListen);
            }
            applyCap(cap, false);

            if (cap == maxConnections) {
                LOGGER.info(
                        "Storm: RakNet incoming-connection cap left at vanilla {} (MaxPlayers={})",
                        cap,
                        maxPlayers);
                return new UdpEngine(port, udpPort, cap, serverPassword, bListen);
            }

            LOGGER.info(
                    "Storm: RakNet incoming-connection cap raised {} -> {} (MaxPlayers={},"
                            + " login-pipeline headroom={}). Extra slots cannot become players"
                            + " — MaxPlayers is still enforced by LoginPacket.",
                    maxConnections,
                    cap,
                    maxPlayers,
                    cap - maxPlayers);
            try {
                return new UdpEngine(port, udpPort, cap, serverPassword, bListen);
            } catch (ConnectException e) {
                // Never let the raised cap keep the server down: RakNet refused to start with
                // the bigger pool (native limit, whatever), so fall back to the exact vanilla
                // construction. If that fails too we are where vanilla would have been.
                LOGGER.error(
                        "Storm: RakNet failed to start with raised cap {} — retrying with the"
                                + " vanilla cap {}. Set -Dstorm.raknet.connectionHeadroom=0 to"
                                + " silence this.",
                        cap,
                        maxConnections,
                        e);
                applyCap(maxConnections, true);
                UdpEngine engine =
                        new UdpEngine(port, udpPort, maxConnections, serverPassword, bListen);
                LOGGER.warn(
                        "Storm: RakNet started with the vanilla cap {} after the raised-cap"
                                + " failure",
                        maxConnections);
                return engine;
            }
        }

        /**
         * Records the cap for {@link #getAppliedCap()} and publishes it, so {@code
         * storm_connection_slots_max} and {@code storm_connection_cap_fallback} are right from boot
         * rather than from the first server tick.
         */
        private static void applyCap(int cap, boolean fellBackToVanilla) {
            appliedCap = cap;
            try {
                StormConnectionStageMetrics.setResolvedCap(cap);
                StormConnectionStageMetrics.setCapFallback(fellBackToVanilla);
            } catch (Throwable t) {
                LOGGER.warn("Storm: could not publish the RakNet cap metrics", t);
            }
        }

        /**
         * Reads the player ceiling the cap derives from. Applies the {@code
         * Storm.OverrideMaxPlayers} sandbox pair first: at {@code UdpEngine} construction time
         * sandbox vars are loaded but the {@code OnServerStarted} applier has not run yet, and a
         * save with the override enabled should get its connection headroom sized from the
         * override, not the {@code .ini} value.
         */
        /**
         * Length of {@code GameServer.SlotToConnection}, the hard bound the cap must respect —
         * {@code GameServer.disconnect} scans that array up to {@code getMaxConnections()}, so a
         * cap above its length throws on every disconnect (42.20.3 shrank it from 512 to 255).
         * {@code create} runs inside {@code startServer}, so the class is already initialized.
         */
        private static int readSlotTableLength() {
            try {
                return GameServer.SlotToConnection.length;
            } catch (Throwable t) {
                LOGGER.warn(
                        "Storm: could not read GameServer.SlotToConnection.length for the RakNet"
                                + " cap; using the conservative fallback",
                        t);
                return 0;
            }
        }

        private static int readMaxPlayers() {
            try {
                StormPerformanceSandboxApplier.applyMaxPlayersOverride();
            } catch (Throwable t) {
                LOGGER.warn(
                        "Storm: early MaxPlayers-override apply failed; sizing the RakNet cap"
                                + " from the .ini MaxPlayers",
                        t);
            }
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
