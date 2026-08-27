package io.pzstorm.storm.commands;

import io.pzstorm.storm.metrics.VehicleSoundRelevanceMetrics;
import io.pzstorm.storm.vehicles.StormVehicleAlphaCheckSkip;
import io.pzstorm.storm.vehicles.StormVehicleSoundRelevance;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.characters.Role;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.math.PZMath;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicleNetworkSound.server.Manager;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehiclesDB2;

/**
 * Test-only fixture + parity probe for {@link StormVehicleSoundRelevance} and {@link
 * StormVehicleAlphaCheckSkip}. Console commands run on the server main thread, the only thread that
 * may touch the relevance snapshot.
 *
 * <p>Subcommands (all answer with a single {@code RESULT ...} line):
 *
 * <pre>
 *   stormtestvehiclesound spawn &lt;username&gt;
 *       spawns a Base.CarNormal at the player's square (vanilla addvehicle semantics)
 *   stormtestvehiclesound engine on|off
 *       engineDoRunning() / engineDoIdle() on every loaded vehicle
 *   stormtestvehiclesound sound on|off
 *   stormtestvehiclesound alpha on|off
 *       kill switches (same setters the sandbox applier uses)
 *   stormtestvehiclesound lights on|off
 *       setHeadlightsOn + refresh every light part's active flag on every loaded vehicle
 *   stormtestvehiclesound bench [reps]
 *       main-thread timing of drainBatteryUpdateHack / breakingObjects per vehicle-call
 *   stormtestvehiclesound check
 *       for every (connection, vehicle) pair compares vanilla Connection.isRelevant with the
 *       snapshot answer from StormVehicleSoundRelevance.fill, and probes one vehicle's
 *       couldSeeIntersectedSquare(0) to see whether the alpha-check skip fired
 * </pre>
 */
@CommandName(name = "stormtestvehiclesound")
@CommandHelp(
        helpText =
                "Vehicle-sound relevance / alpha-check parity probe: stormtestvehiclesound"
                        + " spawn|engine|lights|sound|alpha|check|bench",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestVehicleSoundRelevanceCommand extends CommandBase {

    private static final String SCRIPT = "Base.CarNormal";
    private static final int SEARCH_RADIUS = 40;

    public TestVehicleSoundRelevanceCommand(
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
            return "RESULT ERROR usage: stormtestvehiclesound spawn|engine|lights|sound|alpha|check|bench";
        }
        try {
            switch (sub) {
                case "spawn":
                    return spawn(getCommandArg(1));
                case "engine":
                    return engine("on".equals(getCommandArg(1)));
                case "sound":
                    StormVehicleSoundRelevance.setEnabled("on".equals(getCommandArg(1)));
                    return "RESULT SOUND enabled=" + StormVehicleSoundRelevance.isEnabled();
                case "alpha":
                    StormVehicleAlphaCheckSkip.setEnabled("on".equals(getCommandArg(1)));
                    return "RESULT ALPHA enabled=" + StormVehicleAlphaCheckSkip.isEnabled();
                case "check":
                    return check();
                case "lights":
                    return lights("on".equals(getCommandArg(1)));
                case "bench":
                    return bench(
                            getCommandArg(1) == null ? 200 : Integer.parseInt(getCommandArg(1)));
                default:
                    return "RESULT ERROR unknown subcommand " + sub;
            }
        } catch (Throwable t) {
            return "RESULT ERROR " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String spawn(String user) {
        VehicleScript script = ScriptManager.instance.getVehicle(SCRIPT);
        if (script == null) {
            return "RESULT ERROR unknown vehicle script " + SCRIPT;
        }
        IsoPlayer player = user == null ? null : GameServer.getPlayerByUserNameForCommand(user);
        if (player == null) {
            return "RESULT ERROR player not found: " + user;
        }
        int px = PZMath.fastfloor(player.getX());
        int py = PZMath.fastfloor(player.getY());
        BaseVehicle v = new BaseVehicle(IsoWorld.instance.currentCell);
        v.setScriptName(script.getModule().getName() + "." + script.getName());
        IsoGridSquare square = placeNearby(v, px, py);
        if (square == null) {
            return "RESULT ERROR no valid vehicle position within "
                    + SEARCH_RADIUS
                    + " squares of "
                    + px
                    + ","
                    + py;
        }
        v.setSquare(square);
        v.square.chunk.vehicles.add(v);
        v.chunk = v.square.chunk;
        v.addToWorld();
        VehiclesDB2.instance.addVehicle(v);
        v.setCurrentKey(v.createVehicleKey());
        v.repair();
        if (v.getPassengerDoor(0) != null) {
            v.getPassengerDoor(0).getDoor().setLocked(false);
        }
        // the freshly added vehicle sits in cell.addVehicles until ObjectDeletionAddition
        // flushes it into getVehicles() next tick, so count the staged set too
        return "RESULT SPAWN id="
                + v.getId()
                + " vehicleId="
                + v.vehicleId
                + " x="
                + v.getX()
                + " y="
                + v.getY()
                + " vehicles="
                + (IsoWorld.instance.currentCell.getVehicles().size()
                        + IsoWorld.instance.currentCell.addVehicles.size());
    }

    /**
     * Vanilla {@code addvehicle} refuses squares that are indoors and outside a vehicle zone; test
     * characters spawn indoors, so walk outward from the player for the first square the vanilla
     * placement check accepts.
     */
    private static IsoGridSquare placeNearby(BaseVehicle v, int px, int py) {
        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.abs(dx) != r && Math.abs(dy) != r) {
                        continue;
                    }
                    IsoGridSquare square = ServerMap.instance.getGridSquare(px + dx, py + dy, 0);
                    if (square == null) {
                        continue;
                    }
                    v.setX(px + dx - 1.0F);
                    v.setY(py + dy - 0.1F);
                    v.setZ(0.2F);
                    if (IsoChunk.doSpawnedVehiclesInInvalidPosition(v)) {
                        return square;
                    }
                }
            }
        }
        return null;
    }

    private static String engine(boolean on) {
        int notIdle = 0;
        List<BaseVehicle> vehicles = new ArrayList<>(IsoWorld.instance.currentCell.getVehicles());
        for (BaseVehicle v : vehicles) {
            if (on) {
                v.engineDoRunning();
            } else {
                v.engineDoIdle();
            }
            if (v.getEngineState() != BaseVehicle.engineStateTypes.Idle) {
                notIdle++;
            }
        }
        return "RESULT ENGINE vehicles=" + vehicles.size() + " notIdle=" + notIdle;
    }

    private static String lights(boolean on) {
        int active = 0;
        List<BaseVehicle> vehicles = new ArrayList<>(IsoWorld.instance.currentCell.getVehicles());
        for (BaseVehicle v : vehicles) {
            v.setHeadlightsOn(on);
            for (int i = 0; i < v.getPartCount(); i++) {
                VehiclePart part = v.getPartByIndex(i);
                if (part.getLight() != null) {
                    v.getParts().updatePart(part);
                    if (part.getLight().getActive()) {
                        active++;
                    }
                }
            }
        }
        return "RESULT LIGHTS vehicles=" + vehicles.size() + " lightsActive=" + active;
    }

    /**
     * Times the two per-tick methods under investigation (C = drainBatteryUpdateHack, D =
     * breakingObjects) over every loaded vehicle, plus replicas of their cheap sub-steps, so the
     * cost split can be measured without a profiler. Runs on the main thread like a real tick.
     */
    private static String bench(int reps) {
        List<BaseVehicle> vehicles = new ArrayList<>(IsoWorld.instance.currentCell.getVehicles());
        int running = 0,
                lightsActive = 0,
                devicesOn = 0,
                physics = 0,
                needParts = 0,
                candidates = 0;
        for (BaseVehicle v : vehicles) {
            if (v.isEngineRunning()) running++;
            if (v.getController() != null) physics++;
            if (v.needPartsUpdate()) needParts++;
            for (int i = 0; i < v.getPartCount(); i++) {
                VehiclePart part = v.getPartByIndex(i);
                if (part.getLight() != null && part.getLight().getActive()) {
                    lightsActive++;
                    candidates++;
                } else if (part.getDeviceData() != null && part.getDeviceData().getIsTurnedOn()) {
                    devicesOn++;
                    candidates++;
                }
            }
        }
        long drain = 0, drainScan = 0, brk = 0, brkSquares = 0, engineChk = 0;
        int squares = 0, objects = 0;
        for (int r = 0; r < reps; r++) {
            for (BaseVehicle v : vehicles) {
                long t0 = System.nanoTime();
                v.drainBatteryUpdateHack();
                long t1 = System.nanoTime();
                boolean any = false;
                if (!v.isEngineRunning()) {
                    for (int i = 0; i < v.getPartCount(); i++) {
                        VehiclePart part = v.getPartByIndex(i);
                        if (part.getDeviceData() != null && part.getDeviceData().getIsTurnedOn()) {
                            any = true;
                        } else if (part.getLight() != null && part.getLight().getActive()) {
                            any = true;
                        }
                    }
                }
                long t2 = System.nanoTime();
                v.breakingObjects();
                long t3 = System.nanoTime();
                float ext =
                        Math.max(
                                        v.getScript().getExtents().x / 2.0F,
                                        v.getScript().getExtents().z / 2.0F)
                                + 0.3F
                                + 1.0F;
                int radiusSq = (int) Math.ceil(ext);
                int sqCount = 0, objCount = 0;
                for (int yy = -radiusSq; yy < radiusSq; yy++) {
                    for (int xx = -radiusSq; xx < radiusSq; xx++) {
                        IsoGridSquare sq =
                                v.getCell()
                                        .getGridSquare(
                                                (double) (v.getX() + xx),
                                                (double) (v.getY() + yy),
                                                (double) v.getZ());
                        if (sq != null) {
                            sqCount++;
                            objCount += sq.getObjects().size();
                        }
                    }
                }
                long t4 = System.nanoTime();
                boolean e1 = v.shouldCollideWithCharacters();
                boolean e2 = v.shouldCollideWithObjects();
                long t5 = System.nanoTime();
                if (any && e1 && e2) squares += 0;
                drain += t1 - t0;
                drainScan += t2 - t1;
                brk += t3 - t2;
                brkSquares += t4 - t3;
                engineChk += t5 - t4;
                if (r == 0) {
                    squares += sqCount;
                    objects += objCount;
                }
            }
        }
        long calls = (long) reps * Math.max(1, vehicles.size());
        return "RESULT BENCH vehicles="
                + vehicles.size()
                + " reps="
                + reps
                + " running="
                + running
                + " physics="
                + physics
                + " needPartsUpdate="
                + needParts
                + " lightsActive="
                + lightsActive
                + " devicesOn="
                + devicesOn
                + " candidates="
                + candidates
                + " squaresPerVehicle="
                + (squares / Math.max(1, vehicles.size()))
                + " objectsPerVehicle="
                + (objects / Math.max(1, vehicles.size()))
                + " drainNs="
                + (drain / calls)
                + " drainScanOnlyNs="
                + (drainScan / calls)
                + " breakingNs="
                + (brk / calls)
                + " squareLookupOnlyNs="
                + (brkSquares / calls)
                + " collideGatesNs="
                + (engineChk / calls);
    }

    private static String check() throws Exception {
        Class<?> connectionClass = Class.forName("zombie.vehicleNetworkSound.server.Connection");
        Method isRelevant = connectionClass.getDeclaredMethod("isRelevant", BaseVehicle.class);
        isRelevant.setAccessible(true);
        Field connectionsField = Manager.class.getDeclaredField("connections");
        connectionsField.setAccessible(true);
        Object[] connections = (Object[]) connectionsField.get(Manager.getInstance());

        List<BaseVehicle> vehicles = new ArrayList<>(IsoWorld.instance.currentCell.getVehicles());
        int noisy = 0;
        for (BaseVehicle v : vehicles) {
            if (v.vehicleId != -1 && StormVehicleSoundRelevance.radiusFor(v) > 0.0F) {
                noisy++;
            }
        }

        int checked = 0;
        int nullConnections = 0;
        int pairs = 0;
        int mismatches = 0;
        int relevantPairs = 0;
        int fastFilled = 0;
        long failuresBefore = VehicleSoundRelevanceMetrics.failures();
        StormVehicleSoundRelevance.beginTick();
        try {
            for (UdpConnection udp : GameServer.udpEngine.connections) {
                Object connection = connections[udp.getIndex()];
                if (connection == null) {
                    nullConnections++;
                    continue;
                }
                checked++;
                Set<BaseVehicle> truth = new HashSet<>();
                for (BaseVehicle v : vehicles) {
                    pairs++;
                    if (v.vehicleId != -1 && (Boolean) isRelevant.invoke(connection, v)) {
                        truth.add(v);
                    }
                }
                relevantPairs += truth.size();
                Set<BaseVehicle> storm = new HashSet<>();
                if (StormVehicleSoundRelevance.fill(connection, storm)) {
                    fastFilled++;
                    if (!storm.equals(truth)) {
                        mismatches++;
                    }
                }
            }
        } finally {
            StormVehicleSoundRelevance.endTick();
        }

        long alphaSkipped = -1;
        String alphaResult = "none";
        if (!vehicles.isEmpty()) {
            Method couldSee =
                    BaseVehicle.class.getDeclaredMethod("couldSeeIntersectedSquare", int.class);
            couldSee.setAccessible(true);
            long skipsBefore = StormVehicleAlphaCheckSkip.skips;
            alphaResult = String.valueOf(couldSee.invoke(vehicles.get(0), 0));
            alphaSkipped = StormVehicleAlphaCheckSkip.skips - skipsBefore;
        }

        return "RESULT CHECK connections="
                + GameServer.udpEngine.connections.size()
                + " checked="
                + checked
                + " nullConnections="
                + nullConnections
                + " vehicles="
                + vehicles.size()
                + " noisy="
                + noisy
                + " pairs="
                + pairs
                + " mismatches="
                + mismatches
                + " relevantPairs="
                + relevantPairs
                + " fastFilled="
                + fastFilled
                + " fastTotal="
                + VehicleSoundRelevanceMetrics.connectionsFast
                + " vanillaTotal="
                + VehicleSoundRelevanceMetrics.connectionsVanilla
                + " newFailures="
                + (VehicleSoundRelevanceMetrics.failures() - failuresBefore)
                + " soundEnabled="
                + StormVehicleSoundRelevance.isEnabled()
                + " soundFailed="
                + StormVehicleSoundRelevance.isFailed()
                + " alphaEnabled="
                + StormVehicleAlphaCheckSkip.isEnabled()
                + " alphaSkipped="
                + alphaSkipped
                + " alphaResult="
                + alphaResult;
    }
}
