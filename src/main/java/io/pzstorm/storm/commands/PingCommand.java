package io.pzstorm.storm.commands;

import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

@CommandName(name = "ping")
@CommandHelp(helpText = "Responds with pong.", shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class PingCommand extends CommandBase {

    public PingCommand(String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        return "pong";
    }
}
