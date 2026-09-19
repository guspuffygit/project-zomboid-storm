package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The vehicles of one world plus the frame loop the game runs over them: every {@code
 * BaseVehicle.update} (client half), then {@code WorldSimulation.update} (steps + readbacks), with
 * a free-fall check on every readback.
 */
public final class Fleet {

    /** A vehicle below this physics height has fallen through the terrain. */
    public static final float FALL_LIMIT = -GameSim.LEVEL_HEIGHT - 1.0F;

    public final ScenarioContext ctx;
    public final GameSim sim;
    public final List<SimVehicle> vehicles = new ArrayList<>();
    public float minHeight = Float.MAX_VALUE;
    public float maxHeight = -Float.MAX_VALUE;
    private int nextId;

    public Fleet(ScenarioContext ctx, GameSim sim) {
        this.ctx = ctx;
        this.sim = sim;
        this.nextId = 1 + ctx.random.nextInt(200);
    }

    /** Vehicle ids are allocated upward like {@code VehicleIDMap}; a random start per world. */
    public int nextId() {
        return nextId++;
    }

    public SimVehicle spawn(VehicleScripts.Script s, float x, float y, double yaw) {
        SimVehicle v = SimVehicle.spawn(ctx, sim, s, nextId(), x, y, 0, yaw);
        vehicles.add(v);
        spawnedAt.put(v, new float[] {x, y});
        return v;
    }

    /** Where each vehicle was spawned: its position until the first readback. */
    private final Map<SimVehicle, float[]> spawnedAt = new HashMap<>();

    /**
     * {@link Terrain#clearSpot(float, float, float[])} that also keeps {@link #SPACING} squares
     * from every live vehicle: a body created overlapping another is pushed out through the floor,
     * and the game never spawns vehicles into each other.
     */
    public float[] clearSpot(float x, float y, float... offsets) {
        return clearSpotFor(null, x, y, offsets);
    }

    /** {@link #clearSpot} for moving {@code self} (a teleport): it does not block itself. */
    public float[] clearSpotFor(SimVehicle self, float x, float y, float... offsets) {
        return ctx.terrain.clearSpot(
                x,
                y,
                offsets.length == 0 ? new float[] {0, 0} : offsets,
                (px, py) -> occupied(self, px, py));
    }

    /** Minimum distance in squares between a new vehicle and any live one. */
    public static final float SPACING = 6.0F;

    /**
     * Whether a live vehicle other than {@code self} stands within {@link #SPACING} squares of (x,
     * y).
     */
    public boolean occupied(SimVehicle self, float x, float y) {
        for (SimVehicle v : vehicles) {
            if (v.removed || v == self) {
                continue;
            }
            float vx, vy;
            if (v.state != null) {
                vx = v.worldX();
                vy = v.worldY();
            } else {
                float[] at = spawnedAt.get(v);
                vx = at[0];
                vy = at[1];
            }
            float dx = vx - x, dy = vy - y;
            if (dx * dx + dy * dy < SPACING * SPACING) {
                return true;
            }
        }
        return false;
    }

    public List<SimVehicle> live() {
        List<SimVehicle> l = new ArrayList<>();
        for (SimVehicle v : vehicles) {
            if (!v.removed) {
                l.add(v);
            }
        }
        return l;
    }

    /** 60 fps game frames alternate one and two 10 ms steps (5 steps per 3 frames). */
    public static int substeps(int frame) {
        return frame % 3 == 0 ? 2 : 1;
    }

    /** One game frame. Returns the readback. */
    public List<GameSim.VehicleState> frame(int substeps) {
        for (SimVehicle v : vehicles) {
            v.gameUpdate();
        }
        List<GameSim.VehicleState> states = sim.frame(substeps);
        SimVehicle.applyStates(vehicles, states);
        SimVehicle.handleImpulses(ctx, vehicles);
        for (GameSim.VehicleState s : states) {
            minHeight = Math.min(minHeight, s.y());
            maxHeight = Math.max(maxHeight, s.y());
            if (s.y() < FALL_LIMIT || Float.isNaN(s.y())) {
                float wx = s.x() + sim.offsetX, wy = s.z() + sim.offsetY;
                int sqx = (int) Math.floor(wx), sqy = (int) Math.floor(wy);
                byte[] shapes = new byte[4];
                int n =
                        ctx.terrain.shapesAt(
                                Math.floorDiv(sqx, 8),
                                Math.floorDiv(sqy, 8),
                                0,
                                Math.floorMod(sqx, 8),
                                Math.floorMod(sqy, 8),
                                shapes);
                throw new IllegalStateException(
                        "vehicle "
                                + s.id()
                                + " fell through the world: y="
                                + s.y()
                                + " at "
                                + wx
                                + ","
                                + wy
                                + " frame "
                                + sim.frames
                                + " chunk loaded="
                                + sim.chunkLoaded(Math.floorDiv(sqx, 8), Math.floorDiv(sqy, 8))
                                + " level-0 shapes="
                                + java.util.Arrays.toString(java.util.Arrays.copyOf(shapes, n)));
            }
        }
        return states;
    }

    /** Frames of the vehicles doing nothing: the settle after spawning. */
    public void settle(int frames) {
        for (int f = 0; f < frames; f++) {
            frame(substeps(f));
        }
    }

    /** Keeps chunk map 0 centred on {@code v}, as the local player's map follows its car. */
    public void follow(SimVehicle v) {
        if (v.state != null && !v.removed) {
            sim.follow(v.worldX(), v.worldY());
        }
    }

    public void removeAll() {
        for (SimVehicle v : vehicles) {
            v.remove();
        }
    }

    /** Scenario footer: a note with the height envelope the free-fall check saw. */
    public void noteHeights() {
        ctx.note("vehicle heights min=" + minHeight + " max=" + maxHeight);
    }
}
