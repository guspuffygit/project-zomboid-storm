package io.pzstorm.storm.commands;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import zombie.characters.Capability;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.characters.SurvivorDesc;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.Core;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoDirections;
import zombie.iso.IsoWorld;
import zombie.iso.SpawnPoints;
import zombie.savefile.ServerPlayerDB;

@CommandName(name = "stormcreatechar")
@CommandHelp(
        helpText = "Creates a test character: stormcreatechar <username>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class CreateTestCharCommand extends CommandBase {

    public CreateTestCharCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() throws SQLException {
        return Command();
    }

    @Override
    protected String Command() {
        String targetUser = getCommandArg(0);
        if (targetUser == null || targetUser.isEmpty()) {
            return "Usage: stormcreatechar <username>";
        }

        try {
            if (ServerPlayerDB.getInstance().conn == null) {
                return "ERROR: player database not available";
            }

            if (characterExists(targetUser)) {
                return "Character already exists for " + targetUser;
            }

            IsoGameCharacter.Location spawn = getSpawnLocation();

            SurvivorDesc desc = new SurvivorDesc();
            IsoPlayer player =
                    new IsoPlayer(IsoWorld.instance.currentCell, desc, spawn.x, spawn.y, spawn.z);
            player.setX(spawn.x + 0.5f);
            player.setY(spawn.y + 0.5f);
            player.setZ(spawn.z);
            player.setDir(IsoDirections.SE);
            player.setUsername(targetUser);

            ByteBuffer buf = ByteBuffer.allocate(500_000);
            player.save(buf);
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);

            insertCharacter(targetUser, spawn, data);
            return "Created test character for " + targetUser + " at " + spawn.x + "," + spawn.y;

        } catch (Exception e) {
            return "ERROR creating character: " + e.getMessage();
        }
    }

    private boolean characterExists(String username) throws Exception {
        try (PreparedStatement ps =
                ServerPlayerDB.getInstance()
                        .conn
                        .prepareStatement(
                                "SELECT id FROM networkPlayers WHERE username=? AND world=? AND"
                                        + " playerIndex=0")) {
            ps.setString(1, username);
            ps.setString(2, Core.gameSaveWorld);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        }
    }

    private void insertCharacter(String username, IsoGameCharacter.Location spawn, byte[] data)
            throws Exception {
        String sql =
                "INSERT INTO networkPlayers(world, username, steamid, playerIndex, name, x, y, z,"
                        + " worldversion, isDead, data) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = ServerPlayerDB.getInstance().conn.prepareStatement(sql)) {
            ps.setString(1, Core.gameSaveWorld);
            ps.setString(2, username);
            ps.setString(3, "");
            ps.setInt(4, 0);
            ps.setString(5, username);
            ps.setFloat(6, spawn.x + 0.5f);
            ps.setFloat(7, spawn.y + 0.5f);
            ps.setFloat(8, spawn.z);
            ps.setInt(9, IsoWorld.getWorldVersion());
            ps.setBoolean(10, false);
            ps.setBytes(11, data);
            ps.executeUpdate();
            ServerPlayerDB.getInstance().conn.commit();
        }
    }

    private static IsoGameCharacter.Location getSpawnLocation() {
        if (SpawnPoints.instance != null && !SpawnPoints.instance.getSpawnPoints().isEmpty()) {
            return SpawnPoints.instance.getSpawnPoints().get(0);
        }
        IsoGameCharacter.Location loc = new IsoGameCharacter.Location();
        loc.x = 10745;
        loc.y = 9412;
        loc.z = 0;
        return loc;
    }
}
