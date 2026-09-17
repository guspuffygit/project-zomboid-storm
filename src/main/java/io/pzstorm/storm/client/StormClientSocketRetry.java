package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import zombie.Lua.LuaEventManager;
import zombie.core.Translator;
import zombie.core.raknet.UdpEngine;
import zombie.core.random.Rand;
import zombie.network.CoopMaster;
import zombie.network.GameClient;

/**
 * Recovers the client's RakNet startup when its local UDP port is already taken.
 *
 * <p>{@code GameClient.startClient} picks the client socket at random ({@code
 * GameServer.defaultPort + Rand.Next(10000) + 1234}) and makes exactly one attempt. A machine that
 * has a few hundred of those ten thousand ports in use — normal on Windows with WSL, Hyper-V or a
 * busy ephemeral range — loses that draw often, and RakNet answers {@code
 * SOCKET_PORT_ALREADY_IN_USE}. Vanilla catches the exception, logs it, leaves {@code clientStarted}
 * false, and returns; the connect request has already been polled off {@code
 * ConnectionManager.connectionRequests}, so nothing retries and nothing tells the UI, which sits on
 * "Contacting server..." until the player gives up.
 *
 * <p>Re-running the constructor re-rolls the port, so {@link #rebind} retries up to {@value
 * #MAX_BIND_ATTEMPTS} times. With a tenth of the range occupied that turns a ~10% chance of a dead
 * join into roughly one in ten billion. When every attempt fails, or when the failure was in {@code
 * Connect} rather than the bind, the player gets a real error instead of a hang.
 */
public final class StormClientSocketRetry {

    private static final int MAX_BIND_ATTEMPTS = 10;

    private StormClientSocketRetry() {}

    /**
     * Exit hook for {@code GameClient.startClient}. Returns the value {@code clientStarted} should
     * hold once this returns.
     */
    public static boolean afterStartClient(Object client, boolean clientStarted) {
        if (clientStarted) {
            return true;
        }
        try {
            GameClient gameClient = (GameClient) client;
            if (gameClient.udpEngine == null && rebind(gameClient)) {
                return true;
            }
            reportConnectFailed();
        } catch (Throwable t) {
            LOGGER.error("Client socket retry failed; the connect attempt is lost", t);
        }
        return false;
    }

    private static boolean rebind(GameClient gameClient) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS; attempt++) {
            try {
                UdpEngine engine = new UdpEngine(Rand.Next(10000) + 12345, 0, 1, null, false);
                gameClient.udpEngine = engine;
                if (CoopMaster.instance != null && CoopMaster.instance.isRunning()) {
                    engine.Connect(
                            "127.0.0.1",
                            CoopMaster.instance.getServerPort(),
                            GameClient.serverPassword,
                            false);
                } else {
                    engine.Connect(
                            GameClient.ip,
                            GameClient.port,
                            GameClient.serverPassword,
                            GameClient.useSteamRelay);
                }
                LOGGER.info(
                        "Client RakNet socket bound on retry {} after the local port vanilla drew"
                                + " was in use",
                        attempt);
                return true;
            } catch (Exception e) {
                last = e;
            }
        }
        LOGGER.error(
                "Client RakNet socket found no free local port in {} retries; connect aborted",
                MAX_BIND_ATTEMPTS,
                last);
        return false;
    }

    /** Vanilla leaves the connect screen spinning forever on this path. Tell the player instead. */
    private static void reportConnectFailed() {
        try {
            LuaEventManager.triggerEvent(
                    "OnConnectFailed",
                    Translator.getText("UI_servers_connectionfailed"),
                    "Could not open a local network socket");
        } catch (Throwable t) {
            LOGGER.error("Could not report the failed connect to the UI", t);
        }
    }
}
