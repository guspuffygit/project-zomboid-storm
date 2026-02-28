package io.pzstorm.storm.commands;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.debugging.TriggeredEvents;
import java.util.ArrayList;
import zombie.GameSounds;
import zombie.audio.GameSound;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

@CommandName(name = "printdebug")
@CommandHelp(
        helpText = "Prints debug info. Usage: /printdebug events | /printdebug sounds",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
@CommandArgs(required = "(events|sounds)")
public class PrintDebugCommand extends CommandBase {

    public PrintDebugCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        String target = this.getCommandArg(0);
        return switch (target) {
            case "events" -> printEvents();
            case "sounds" -> printSounds();
            default -> "Unknown option: " + target + ". Use 'events' or 'sounds'.";
        };
    }

    private String printEvents() {
        TriggeredEvents.printTriggeredEvents();
        return "Triggered events printed to debug log.";
    }

    private String printSounds() {
        ArrayList<String> categories = GameSounds.getCategories();

        for (String category : categories) {
            ArrayList<GameSound> sounds = GameSounds.getSoundsInCategory(category);
            LOGGER.info("=== Category: {} ({} sounds) ===", category, sounds.size());
            for (GameSound sound : sounds) {
                LOGGER.info(
                        "  [{}] name={}, loop={}, is3d={}, clips={}, master={}",
                        category,
                        sound.getName(),
                        sound.loop,
                        sound.is3d,
                        sound.clips.size(),
                        sound.getMasterName());
            }
        }

        return "Printed game sounds and categories to debug log.";
    }
}
