package io.pzstorm.storm.commands;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;

@CommandName(name = "screenshot")
@CommandHelp(
        helpText = "Requests a screenshot from a player. Usage: /screenshot <username>",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
@CommandArgs(required = "(.+)")
public class ScreenshotCommand extends CommandBase {

    public ScreenshotCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        String targetUsername = this.getCommandArg(0);

        IsoPlayer player = GameServer.getPlayerByUserNameForCommand(targetUsername);
        if (player == null) {
            return "Player not found: " + targetUsername;
        }

        String requestId = player.getUsername() + "_" + System.currentTimeMillis();

        KahluaTable args = LuaManager.platform.newTable();
        args.rawset("requestId", requestId);

        GameServer.sendServerCommand(player, "stormScreenshot", "request", args);

        return "Screenshot requested from "
                + player.getUsername()
                + " (id: "
                + requestId
                + "). "
                + "File will be saved to Lua cache dir as storm_screenshot_"
                + player.getUsername()
                + "_"
                + requestId
                + ".png";
    }
}
