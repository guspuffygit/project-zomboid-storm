package io.pzstorm.storm.commands;

import io.pzstorm.storm.metrics.WorldMapVisibilityMemoMetrics;
import io.pzstorm.storm.worldmap.StormWorldMapVisibilityMemo;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import zombie.characters.Capability;
import zombie.characters.Faction;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.characters.Roles;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameServer;
import zombie.network.ServerOptions;

/**
 * Test-only fixture + parity probe for {@link StormWorldMapVisibilityMemo}. Console commands run on
 * the server main thread (GameServer drains {@code consoleCommands} inside the main loop), which is
 * the only place the memo's per-batch static state may be touched without racing the real
 * once-a-second {@code sendWorldMapPlayerPosition()} batch.
 *
 * <p>Subcommands (all answer with a single {@code RESULT ...} line):
 *
 * <pre>
 *   stormtestworldmapparity reset
 *       clears factions + safehouses, resets every connection's role to the default user role and
 *       restores MapRemotePlayerVisibility to the value seen before the first "mode" call
 *   stormtestworldmapparity mode &lt;1-4&gt;
 *   stormtestworldmapparity faction &lt;name&gt; &lt;owner&gt; [member...]
 *   stormtestworldmapparity safehouse &lt;owner&gt; [member...]
 *   stormtestworldmapparity role &lt;username&gt; &lt;roleName|none&gt;
 *   stormtestworldmapparity check
 *       evaluates the woven shouldSendWorldMapPlayerPosition for every (connection, player) pair
 *       once with the memo inactive (vanilla body) and once inside a memo batch, then runs the real
 *       all-connections batch once; reports mismatches, memo/vanilla evaluation counts, failures and
 *       the vanilla truth matrix as viewer&gt;target=0|1 entries
 * </pre>
 */
@CommandName(name = "stormtestworldmapparity")
@CommandHelp(
        helpText =
                "World-map visibility memo parity probe: stormtestworldmapparity"
                        + " reset|mode|faction|safehouse|role|check",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestWorldMapVisibilityParityCommand extends CommandBase {

    private static final int FALSE = 0;
    private static final int TRUE = 1;
    private static final int THREW = 2;
    private static final int SAFEHOUSE_SIZE = 4;
    private static final int SAFEHOUSE_ORIGIN_X = 20;
    private static final int SAFEHOUSE_ORIGIN_Y = 20;

    private static int originalMode = -1;
    private static int safehousesCreated;

    public TestWorldMapVisibilityParityCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String sub = getCommandArg(0);
        if (sub == null) {
            return "RESULT ERROR usage: stormtestworldmapparity reset|mode|faction|safehouse|role|check";
        }
        try {
            switch (sub) {
                case "reset":
                    return reset();
                case "mode":
                    return mode(Integer.parseInt(getCommandArg(1)));
                case "faction":
                    return faction();
                case "safehouse":
                    return safehouse();
                case "role":
                    return role(getCommandArg(1), getCommandArg(2));
                case "check":
                    return check();
                default:
                    return "RESULT ERROR unknown subcommand " + sub;
            }
        } catch (Throwable t) {
            return "RESULT ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String reset() {
        Faction.getFactions().clear();
        SafeHouse.clearSafehouseList();
        safehousesCreated = 0;
        Role user = Roles.getDefaultForUser();
        for (UdpConnection c : GameServer.udpEngine.connections) {
            c.setRole(user);
        }
        if (originalMode > 0) {
            ServerOptions.getInstance().mapRemotePlayerVisibility.setValue(originalMode);
        }
        return "RESULT RESET mode="
                + ServerOptions.getInstance().mapRemotePlayerVisibility.getValue()
                + " connections="
                + GameServer.udpEngine.connections.size();
    }

    private static String mode(int mode) {
        ServerOptions.IntegerServerOption option =
                ServerOptions.getInstance().mapRemotePlayerVisibility;
        if (originalMode < 0) {
            originalMode = option.getValue();
        }
        option.setValue(mode);
        return "RESULT MODE mode=" + option.getValue();
    }

    private String faction() {
        String name = getCommandArg(1);
        String owner = getCommandArg(2);
        if (name == null || owner == null) {
            return "RESULT ERROR usage: faction <name> <owner> [member...]";
        }
        Faction faction = new Faction(name, owner);
        for (int i = 3; i < getCommandArgsCount(); i++) {
            // Bypass Faction.addPlayer's one-faction-per-user refusal so fixtures can be built in
            // any order; the live test never lists a user in two factions.
            faction.getPlayers().add(getCommandArg(i));
        }
        Faction.getFactions().add(faction);
        return "RESULT FACTION name="
                + name
                + " owner="
                + owner
                + " members="
                + faction.getPlayers().size()
                + " factions="
                + Faction.getFactions().size();
    }

    private String safehouse() {
        String owner = getCommandArg(1);
        if (owner == null) {
            return "RESULT ERROR usage: safehouse <owner> [member...]";
        }
        int x = SAFEHOUSE_ORIGIN_X + safehousesCreated * (SAFEHOUSE_SIZE + 2);
        safehousesCreated++;
        SafeHouse safehouse =
                SafeHouse.addSafeHouse(
                        x, SAFEHOUSE_ORIGIN_Y, SAFEHOUSE_SIZE, SAFEHOUSE_SIZE, owner);
        for (int i = 2; i < getCommandArgsCount(); i++) {
            safehouse.addPlayer(getCommandArg(i));
        }
        return "RESULT SAFEHOUSE owner="
                + owner
                + " members="
                + safehouse.getPlayers().size()
                + " safehouses="
                + SafeHouse.getSafehouseList().size();
    }

    private static String role(String username, String roleName) {
        if (username == null || roleName == null) {
            return "RESULT ERROR usage: role <username> <roleName>";
        }
        // "none" leaves the connection role-less, the shape of a peer still mid-login.
        Role role = "none".equals(roleName) ? null : Roles.getRole(roleName);
        if (role == null && !"none".equals(roleName)) {
            return "RESULT ERROR unknown role " + roleName;
        }
        for (UdpConnection c : GameServer.udpEngine.connections) {
            if (username.equals(c.getUserName())) {
                c.setRole(role);
                return "RESULT ROLE username="
                        + username
                        + " role="
                        + c.getRoleName()
                        + " seeWorldMap="
                        + (role != null && role.hasCapability(Capability.SeeWorldMap));
            }
        }
        return "RESULT ERROR no connection for " + username;
    }

    private static String check() throws Exception {
        Method predicate =
                GameServer.class.getDeclaredMethod(
                        "shouldSendWorldMapPlayerPosition", UdpConnection.class, IsoPlayer.class);
        predicate.setAccessible(true);

        List<UdpConnection> connections = new ArrayList<>(GameServer.udpEngine.connections);
        List<IsoPlayer> players = new ArrayList<>(GameServer.Players);
        int pairs = connections.size() * players.size();
        int[] vanilla = new int[pairs];
        int[] memo = new int[pairs];

        // Outside a batch the advice defers to the vanilla body.
        StormWorldMapVisibilityMemo.end();
        long vanillaBefore = WorldMapVisibilityMemoMetrics.vanillaEvaluations;
        evaluateMatrix(predicate, connections, players, vanilla);
        long vanillaEvals = WorldMapVisibilityMemoMetrics.vanillaEvaluations - vanillaBefore;

        // Same woven predicate, now answered by the memo, walked in vanilla batch order
        // (connection outer, player inner) so switchConnection() runs between viewers.
        long memoBefore = WorldMapVisibilityMemoMetrics.memoEvaluations;
        StormWorldMapVisibilityMemo.begin();
        try {
            evaluateMatrix(predicate, connections, players, memo);
        } finally {
            StormWorldMapVisibilityMemo.end();
        }
        long memoEvals = WorldMapVisibilityMemoMetrics.memoEvaluations - memoBefore;

        int mismatches = 0;
        int vanillaTrue = 0;
        int memoTrue = 0;
        StringBuilder matrix = new StringBuilder();
        StringBuilder bad = new StringBuilder();
        int k = 0;
        for (UdpConnection c : connections) {
            for (IsoPlayer p : players) {
                if (vanilla[k] == TRUE) {
                    vanillaTrue++;
                }
                if (memo[k] == TRUE) {
                    memoTrue++;
                }
                String key = c.getUserName() + ">" + p.getUsername();
                matrix.append(key).append('=').append(vanilla[k]).append(',');
                if (vanilla[k] != memo[k]) {
                    mismatches++;
                    bad.append(key)
                            .append("(v=")
                            .append(vanilla[k])
                            .append(",m=")
                            .append(memo[k])
                            .append("),");
                }
                k++;
            }
        }

        // The real woven batch: begin/end advice + per-pair memo answers, end to end.
        long batchMemoBefore = WorldMapVisibilityMemoMetrics.memoEvaluations;
        long batchVanillaBefore = WorldMapVisibilityMemoMetrics.vanillaEvaluations;
        long failuresBefore = WorldMapVisibilityMemoMetrics.failures();
        int batchThrew = 0;
        try {
            GameServer.sendWorldMapPlayerPosition();
        } catch (Throwable t) {
            batchThrew = 1;
        }
        long batchMemoEvals = WorldMapVisibilityMemoMetrics.memoEvaluations - batchMemoBefore;
        long batchVanillaEvals =
                WorldMapVisibilityMemoMetrics.vanillaEvaluations - batchVanillaBefore;

        return "RESULT PARITY connections="
                + connections.size()
                + " players="
                + players.size()
                + " pairs="
                + pairs
                + " mismatches="
                + mismatches
                + " vanillaTrue="
                + vanillaTrue
                + " memoTrue="
                + memoTrue
                + " vanillaEvals="
                + vanillaEvals
                + " memoEvals="
                + memoEvals
                + " batchMemoEvals="
                + batchMemoEvals
                + " batchVanillaEvals="
                + batchVanillaEvals
                + " batchThrew="
                + batchThrew
                + " failures="
                + WorldMapVisibilityMemoMetrics.failures()
                + " newFailures="
                + (WorldMapVisibilityMemoMetrics.failures() - failuresBefore)
                + " mode="
                + ServerOptions.getInstance().mapRemotePlayerVisibility.getValue()
                + " matrix="
                + matrix
                + " bad="
                + bad;
    }

    /**
     * Fills {@code out} with {@link #FALSE}/{@link #TRUE}, or {@link #THREW} when the predicate
     * threw (vanilla NPEs in mode 3 on a connection with an empty player slot; the memo defers that
     * case to vanilla, so both sides must agree on the throw too).
     */
    private static void evaluateMatrix(
            Method predicate, List<UdpConnection> connections, List<IsoPlayer> players, int[] out)
            throws Exception {
        int k = 0;
        for (UdpConnection c : connections) {
            for (IsoPlayer p : players) {
                try {
                    out[k] = (Boolean) predicate.invoke(null, c, p) ? TRUE : FALSE;
                } catch (InvocationTargetException e) {
                    out[k] = THREW;
                }
                k++;
            }
        }
    }
}
