package io.pzstorm.storm.commands;

import io.pzstorm.storm.mapscan.MapScanJob;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandArgs;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/**
 * Admin console command driving the whole-map reachability sweep ({@link MapScanJob}).
 *
 * <pre>
 * stormscanmap start                          scan the entire map
 * stormscanmap start x1 y1 x2 y2              scan a tile-coordinate rectangle
 * stormscanmap start [x1 y1 x2 y2] window N   override window size (N server cells, default 5)
 * stormscanmap status                         progress / result paths
 * stormscanmap stop                           abort the running scan
 * </pre>
 */
@CommandName(name = "stormscanmap")
@CommandHelp(
        helpText =
                "Scans the map for walkable areas unreachable from ground level and writes a"
                        + " no-spawn artifact + report. Usage: stormscanmap start [x1 y1 x2 y2]"
                        + " [window N] | stop | status",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
@CommandArgs(varArgs = true)
public class MapScanCommand extends CommandBase {

    private static final String USAGE =
            "Usage: stormscanmap start [x1 y1 x2 y2] [window N] | stop | status";

    public MapScanCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    protected String Command() {
        if (this.getCommandArgsCount() == 0) {
            return USAGE;
        }
        String sub = this.getCommandArg(0).toLowerCase();
        switch (sub) {
            case "status":
                return MapScanJob.status();
            case "stop":
                return MapScanJob.requestStop();
            case "start":
                return start();
            default:
                return USAGE;
        }
    }

    private String start() {
        int minX = MapScanJob.FULL_MAP;
        int minY = MapScanJob.FULL_MAP;
        int maxX = MapScanJob.FULL_MAP;
        int maxY = MapScanJob.FULL_MAP;
        int windowCells = -1;
        int i = 1;
        try {
            if (this.getCommandArgsCount() > i && isNumeric(this.getCommandArg(i))) {
                if (this.getCommandArgsCount() < i + 4) {
                    return USAGE;
                }
                minX = Integer.parseInt(this.getCommandArg(i));
                minY = Integer.parseInt(this.getCommandArg(i + 1));
                maxX = Integer.parseInt(this.getCommandArg(i + 2));
                maxY = Integer.parseInt(this.getCommandArg(i + 3));
                if (minX > maxX || minY > maxY) {
                    return "Invalid bounds: min corner must be <= max corner.";
                }
                i += 4;
            }
            if (this.getCommandArgsCount() > i) {
                if (!"window".equalsIgnoreCase(this.getCommandArg(i))
                        || this.getCommandArgsCount() < i + 2) {
                    return USAGE;
                }
                windowCells = Integer.parseInt(this.getCommandArg(i + 1));
                if (windowCells < 2 || windowCells > 10) {
                    return "Window size must be 2..10 server cells.";
                }
                i += 2;
            }
            if (this.getCommandArgsCount() > i) {
                return USAGE;
            }
        } catch (NumberFormatException e) {
            return USAGE;
        }
        return MapScanJob.requestStart(minX, minY, maxX, maxY, windowCells);
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        int start = s.charAt(0) == '-' ? 1 : 0;
        if (start == s.length()) {
            return false;
        }
        for (int i = start; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
