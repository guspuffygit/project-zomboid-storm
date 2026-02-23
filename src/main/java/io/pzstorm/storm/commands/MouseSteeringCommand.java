package io.pzstorm.storm.commands;

import io.pzstorm.storm.vehicle.MouseSteeringCalculator;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

@CommandName(name = "mousesteering")
@CommandArgs(required = "(.+)", optional = "(.+)")
@CommandHelp(
        helpText =
                "Configure mouse steering parameters: /mousesteering <deadzone|fulllock|mindist|print> <value>")
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class MouseSteeringCommand extends CommandBase {

    public MouseSteeringCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        String param = this.getCommandArg(0).toLowerCase().trim();
        String valueStr = this.getCommandArg(1);

        if (valueStr == null || valueStr.isEmpty() || "print".equals(param)) {
            // No value provided — show current values
            return String.format(
                    "Mouse Steering Settings:\n  deadzone = %.4f rad\n  fulllock = %.4f rad\n  mindist = %.4f (squared)",
                    MouseSteeringCalculator.getDeadZoneRad(),
                    MouseSteeringCalculator.getFullLockAngleRad(),
                    MouseSteeringCalculator.getMinDistanceSq());
        }

        float value;
        try {
            value = Float.parseFloat(valueStr.trim());
        } catch (NumberFormatException e) {
            return "Invalid number: \"" + valueStr + "\"";
        }

        switch (param) {
            case "deadzone":
                if (value < 0.0f) return "Dead zone cannot be negative.";
                MouseSteeringCalculator.setDeadZoneRad(value);
                return "Dead zone set to " + value + " radians.";

            case "fulllock":
                if (value <= 0.0f) return "Full lock angle must be positive.";
                MouseSteeringCalculator.setFullLockAngleRad(value);
                return "Full lock angle set to " + value + " radians.";

            case "mindist":
                if (value < 0.0f) return "Minimum distance squared cannot be negative.";
                MouseSteeringCalculator.setMinDistanceSq(value);
                return "Minimum distance squared set to " + value + ".";

            default:
                return "Unknown parameter: \""
                        + param
                        + "\". Valid options: deadzone, fulllock, mindist";
        }
    }
}
