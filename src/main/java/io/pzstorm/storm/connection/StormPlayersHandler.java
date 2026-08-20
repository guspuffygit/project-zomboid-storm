package io.pzstorm.storm.connection;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.connection.commands.StormPlayersHelloCommand;
import io.pzstorm.storm.connection.commands.StormPlayersRequestCommand;
import io.pzstorm.storm.event.core.OnClientCommand;
import java.util.ArrayList;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.core.raknet.UdpConnection;
import zombie.core.raknet.UdpEngine;
import zombie.network.GameServer;

/**
 * Server side of the "which players run Storm" column on the client scoreboards.
 *
 * <p>Storm clients announce their version with {@code StormPlayers.hello} once in-game; an admin
 * client holding {@link Capability#SeePlayersConnected} sends {@code StormPlayers.request} and gets
 * a {@code StormPlayers.list} server command back with {@code { [username] = stormVersion }} for
 * the players online right now. Vanilla clients never send either, so they simply have no entry.
 *
 * <p>Both handlers run on the server main thread (client commands are dispatched from {@code
 * GameServer.receiveClientCommand}), so the reply goes out from the same thread vanilla sends on.
 */
public final class StormPlayersHandler {

    static final String COMMAND_LIST = "list";

    private StormPlayersHandler() {}

    @OnClientCommand
    public static void onHello(StormPlayersHelloCommand event) {
        IsoPlayer player = event.getPlayer();
        UdpConnection connection =
                player == null ? null : GameServer.getConnectionFromPlayer(player);
        if (connection == null) {
            return;
        }
        String stored =
                StormClientVersionRegistry.record(
                        connection.getConnectedGUID(), event.getVersion());
        if (stored == null) {
            LOGGER.debug(
                    "Ignoring StormPlayers.hello without a version from {}", player.getUsername());
            return;
        }
        if (StormClientVersionRegistry.needsSweep()) {
            sweepDeadConnections();
        }
        LOGGER.debug("StormPlayers.hello: {} runs Storm {}", player.getUsername(), stored);
    }

    @OnClientCommand
    public static void onRequest(StormPlayersRequestCommand event) {
        IsoPlayer player = event.getPlayer();
        if (player == null) {
            return;
        }
        Role role = player.getRole();
        if (role == null || !role.hasCapability(Capability.SeePlayersConnected)) {
            LOGGER.debug(
                    "Refusing StormPlayers.request from {} (no SeePlayersConnected)",
                    player.getUsername());
            return;
        }
        sweepDeadConnections();
        GameServer.sendServerCommand(
                player, StormPlayersHelloCommand.MODULE, COMMAND_LIST, buildVersionTable());
    }

    /** {@code { [username] = version }} for every online player the server knows runs Storm. */
    static KahluaTable buildVersionTable() {
        KahluaTable table = LuaManager.platform.newTable();
        ArrayList<IsoPlayer> players = GameServer.getPlayers();
        for (IsoPlayer online : players) {
            String username = online.getUsername();
            if (username == null) {
                continue;
            }
            String version = versionOf(online);
            if (version != null) {
                table.rawset(username, version);
            }
        }
        return table;
    }

    static @Nullable String versionOf(IsoPlayer player) {
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        if (connection == null) {
            return null;
        }
        String announced = StormClientVersionRegistry.versionOf(connection.getConnectedGUID());
        if (announced != null) {
            return announced;
        }
        return StormTcpSessionRegistry.clientStormVersion(connection);
    }

    private static void sweepDeadConnections() {
        UdpEngine engine = GameServer.udpEngine;
        if (engine == null) {
            return;
        }
        StormClientVersionRegistry.sweep(guid -> engine.getActiveConnection(guid) != null);
    }
}
