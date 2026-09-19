package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BulletBackend;
import java.util.List;

/**
 * The physics-facing half of a game {@code BaseVehicle} + {@code CarController}: every call the
 * game makes on the library for one vehicle, with the game's arguments, fed from the latest {@code
 * getVehiclePhysics} readback. Towing mirrors {@code BaseVehicle.addPointConstraint}, {@code
 * breakConstraint}, {@code checkTrailerAttachTime}, {@code checkTrailerVerticalAlignment} and
 * {@code Bullet.onVehicleConstraintImpulse}, using game time instead of the wall clock.
 */
public final class SimVehicle {

    public final ScenarioContext ctx;
    public final GameSim sim;
    public final VehicleScripts.Script script;
    public final int id;
    private final BulletBackend b;

    /** {@code physics.isEnable}, {@code isStatic}, {@code isActive}. */
    public boolean enabled;

    public boolean isStatic;
    public boolean isActive;

    public float mass;
    public float breakingSlowFactor;
    public boolean burnt;

    /** Tire present and inflation (0..1) per wheel; drives {@code updateBulletStats}. */
    public final boolean[] tire;

    public final float[] inflation;

    /** Suspension part present per wheel ({@code Suspension*} part). */
    public final boolean[] suspension;

    public boolean offroad;
    public boolean raining;

    public float engine;
    public float braking;
    public float steer;

    /** Latest readback, or null before the first frame. */
    public GameSim.VehicleState state;

    public int constraintTowing = -1;
    public SimVehicle towing;
    public SimVehicle towedBy;

    /** Game time the tow was last (dis)connected ({@code constraintChangedTime}). */
    public float constraintChangedTime = -100;

    /** Game time a trailer attach began ({@code beginAttachTrailerMS}), 0 = none. */
    public float beginAttachTrailer;

    public boolean removed;

    private SimVehicle(ScenarioContext ctx, GameSim sim, VehicleScripts.Script script, int id) {
        this.ctx = ctx;
        this.sim = sim;
        this.script = script;
        this.id = id;
        this.b = ctx.bullet;
        this.mass = script.mass();
        int n = script.wheels().length;
        this.tire = new boolean[n];
        this.inflation = new float[n];
        this.suspension = new boolean[n];
        for (int i = 0; i < n; i++) {
            tire[i] = true;
            inflation[i] = 1.0F;
            suspension[i] = true;
        }
    }

    /**
     * {@code new CarController(vehicle)}: {@code Bullet.addVehicle} at world square (x, y) standing
     * on {@code level}, then {@code BaseVehicle.setPhysicsActive(true)}.
     */
    public static SimVehicle spawn(
            ScenarioContext ctx,
            GameSim sim,
            VehicleScripts.Script script,
            int id,
            float x,
            float y,
            int level,
            double yaw) {
        SimVehicle v = new SimVehicle(ctx, sim, script, id);
        float[] q = GameSim.yawQuat(yaw);
        ctx.bullet.addVehicle(
                id,
                x,
                y,
                GameSim.physicsZ(script, level),
                q[0],
                q[1],
                q[2],
                q[3],
                script.fullName());
        v.setPhysicsActive(true);
        return v;
    }

    public boolean isTrailer() {
        return script.fullName().contains("Trailer");
    }

    public float worldX() {
        return state == null ? Float.NaN : state.x() + sim.offsetX;
    }

    public float worldY() {
        return state == null ? Float.NaN : state.z() + sim.offsetY;
    }

    /** {@code BaseVehicle.getUpVectorDot}: the y of the body's local up axis. */
    public float upVectorDot() {
        if (state == null) {
            return 1.0F;
        }
        return 1.0F - 2.0F * (state.qx() * state.qx() + state.qz() * state.qz());
    }

    public boolean desirePhysicsActive;
    public boolean disableSimulationDueToLackOfSurroundingChunks;

    /** {@code BaseVehicle.setPhysicsActive(active, true)}. */
    public void setPhysicsActive(boolean active) {
        desirePhysicsActive = active;
        if (disableSimulationDueToLackOfSurroundingChunks) {
            active = false;
        }
        if (active == enabled) {
            return;
        }
        enabled = active;
        if (isStatic != !active) {
            b.setVehicleStatic(id, !active);
            isStatic = !active;
        }
        if (isActive != active) {
            b.setVehicleActive(id, active);
            isActive = active;
        }
    }

    /**
     * {@code BaseVehicle.checkSurroundingChunks}: the body is frozen while any valid chunk of the
     * 3x3 around it is not loaded, so it never drives off the streamed terrain.
     */
    public void checkSurroundingChunks() {
        if (state == null) {
            return;
        }
        int wx = (int) Math.floor(worldX()) / 8;
        int wy = (int) Math.floor(worldY()) / 8;
        boolean all = true;
        for (int dy = -1; dy <= 1 && all; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (ctx.terrain.isValidChunk(wx + dx, wy + dy)
                        && !sim.chunkLoaded(wx + dx, wy + dy)) {
                    all = false;
                    break;
                }
            }
        }
        if (all) {
            if (disableSimulationDueToLackOfSurroundingChunks) {
                disableSimulationDueToLackOfSurroundingChunks = false;
                setPhysicsActive(desirePhysicsActive);
            }
        } else if (!disableSimulationDueToLackOfSurroundingChunks) {
            disableSimulationDueToLackOfSurroundingChunks = true;
            setPhysicsActive(desirePhysicsActive);
        }
    }

    /** {@code CarController.updateControls}: gas/brake/steer to the library. */
    public void control(float engine, float braking, float steer) {
        this.engine = engine;
        this.braking = braking;
        this.steer = steer;
        b.controlVehicle(id, engine, braking, steer);
    }

    /** {@code CarController.stop}-style: no engine, full brakes, wheels straight. */
    public void stop() {
        control(0.0F, script.brakingForce(), 0.0F);
    }

    /** {@code BaseVehicle.getFudgedMass}. */
    public float fudgedMass() {
        if (isTrailer() && towedBy != null && towedBy.beginAttachTrailer != 0) {
            return mass * 0.2F;
        }
        if (burnt && !isTrailer()) {
            if (towedBy != null && towedBy.engine > 0) {
                float m = Math.max(250.0F, towedBy.mass / 3.7F);
                if (script.wheels().length == 0) {
                    m = Math.min(m, 200.0F);
                }
                return m;
            }
        }
        return mass;
    }

    /**
     * The per-frame block of {@code BaseVehicle.update} (client): {@code updateBulletStats}, {@code
     * setVehicleMass(getFudgedMass())}, {@code updateVelocityMultiplier}, then the trailer checks.
     */
    public void gameUpdate() {
        if (removed) {
            return;
        }
        checkSurroundingChunks();
        updateBulletStats();
        b.setVehicleMass(id, fudgedMass());
        updateVelocityMultiplier();
        checkTrailerVerticalAlignment();
        checkTrailerAttachTime();
    }

    /** {@code BaseVehicle.updateBulletStats} / {@code updateBulletStatsWheel}: float[24]. */
    public void updateBulletStats() {
        if (burnt) {
            return;
        }
        float[] data = new float[24];
        int chanceOfBump = 100;
        float frictionMul = 1.0F;
        float speed = state == null ? 0 : Math.abs(state.speedKmh());
        double susp;
        if (offroad && speed > 1.0F) {
            chanceOfBump = 25;
            susp = ctx.range(0.02F, 0.1F);
            frictionMul = 0.7F;
        } else if (speed > 1.0F && ctx.random.nextInt(100) < 10) {
            susp = ctx.range(0.01F, 0.05F);
        } else {
            susp = 0.0;
        }
        if (raining) {
            frictionMul -= 0.3F;
        }
        double period = 2.4;
        float wx = worldX(), wy = worldY();
        for (int i = 0; i < script.wheels().length && i < 4; i++) {
            int o = i * 6;
            VehicleScripts.Wheel w = script.wheels()[i];
            double px = (Float.isNaN(wx) ? 0 : wx) + w.offsetX();
            double py = (Float.isNaN(wy) ? 0 : wy) + w.offsetZ();
            float bump =
                    ctx.random.nextInt(chanceOfBump) == 0
                            ? (float) (Math.sin(period * px) * Math.sin(period * py) * susp)
                            : 0.0F;
            if (tire[i]) {
                data[o] = 1.0F;
                data[o + 1] = inflation[i];
                data[o + 2] = frictionMul * script.wheelFriction();
                data[o + 3] = suspension[i] ? script.suspensionDamping() : 0.1F;
                data[o + 4] = suspension[i] ? script.suspensionCompression() : 0.1F;
            } else {
                data[o] = 0.0F;
                data[o + 1] = 30.0F;
                data[o + 2] = 0.0F;
                data[o + 3] = 2.88F;
                data[o + 4] = 3.83F;
            }
            data[o + 5] = bump;
        }
        b.setVehicleParams(id, data);
    }

    /** {@code BaseVehicle.updateVelocityMultiplier}. */
    public void updateVelocityMultiplier() {
        float speed = 0;
        if (state != null) {
            speed = (float) Math.sqrt(state.vx() * state.vx() + state.vz() * state.vz());
        }
        float maxSpeed = 100000.0F;
        float multiplier = 1.0F;
        if (script.wheels().length > 0) {
            if (speed > 0.0F && speed > 34.0F - breakingSlowFactor) {
                maxSpeed = 34.0F - breakingSlowFactor;
                multiplier = (34.0F - breakingSlowFactor) / speed;
            }
        } else if (towedBy == null) {
            maxSpeed = 0.0F;
            multiplier = 0.1F;
        }
        b.setVehicleVelocityMultiplier(id, maxSpeed, multiplier);
    }

    /** {@code BaseVehicle.setTireInflation} → {@code Bullet.setTireInflation}. */
    public void setTireInflation(int wheel, float value) {
        inflation[wheel] = value;
        b.setTireInflation(id, wheel, value);
    }

    /** {@code BaseVehicle.setTireRemoved} → {@code Bullet.setTireRemoved}. */
    public void setTireRemoved(int wheel, boolean removedTire) {
        tire[wheel] = !removedTire;
        b.setTireRemoved(id, wheel, removedTire);
    }

    /** {@code BaseVehicle} part change → {@code Bullet.setVehicleMass(getMass())}. */
    public void setMass(float m) {
        mass = m;
        b.setVehicleMass(id, m);
    }

    /**
     * {@code BaseVehicle.setWorldTransform}: teleport to world square (x, y) at physics height
     * {@code physY}.
     */
    public void teleport(float x, float y, float physY, double yaw) {
        float[] q = GameSim.yawQuat(yaw);
        b.teleportVehicle(id, x, y, physY, q[0], q[1], q[2], q[3]);
    }

    /** {@code BaseVehicle.updatePhysics} hit-object impulses: force and torque times 30. */
    public void applyHit(float fx, float fy, float fz, float tx, float ty, float tz) {
        float limit = 1500.0F * fudgedMass();
        float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (len > limit) {
            float s = limit / len;
            fx *= s;
            fy *= s;
            fz *= s;
            tx *= s;
            ty *= s;
            tz *= s;
        }
        b.applyCentralForceToVehicle(id, fx * 30.0F, fy * 30.0F, fz * 30.0F);
        b.applyTorqueToVehicle(id, tx * 30.0F, ty * 30.0F, tz * 30.0F);
    }

    /** {@code VehicleImpulsePacket.process}: a server-sent impulse, applied unscaled. */
    public void applyNetImpulse(float fx, float fy, float fz, float tx, float ty, float tz) {
        b.applyCentralForceToVehicle(id, fx, fy, fz);
        b.applyTorqueToVehicle(id, tx, ty, tz);
    }

    /** {@code BaseVehicle.removeFromWorld}: tows broken, then {@code Bullet.removeVehicle}. */
    public void remove() {
        if (removed) {
            return;
        }
        breakConstraint();
        if (towedBy != null) {
            towedBy.breakConstraint();
        }
        b.removeVehicle(id);
        removed = true;
    }

    // ---- towing ----

    /**
     * {@code BaseVehicle.addPointConstraint(player, vehicleB, attachmentA, attachmentB)}: this
     * vehicle tows {@code other}. Rope when neither is a trailer, otherwise the 6-DOF hitch; {@code
     * attaching} = inside the 2 s trailer-attach window (soft ERP 0.02).
     */
    public boolean attach(SimVehicle other, String attachA, String attachB, boolean attaching) {
        breakConstraint();
        other.breakConstraint();
        float[] v1 = script.attachmentLocalPos(attachA);
        float[] v2 = other.script.attachmentLocalPos(attachB);
        if (v1 == null || v2 == null) {
            return false;
        }
        if (attaching) {
            beginAttachTrailer = Math.max(sim.time, 0.001F);
        }
        if (!isTrailer() && !other.isTrailer()) {
            constraintTowing =
                    b.addRopeConstraint(
                            id, other.id, v1[0], v1[1], v1[2], v2[0], v2[1], v2[2], 1.5F);
        } else {
            constraintTowing =
                    b.add6DofConstraint(
                            id,
                            other.id,
                            v1[0],
                            v1[1],
                            v1[2],
                            v2[0],
                            v2[1],
                            v2[2],
                            0.0F,
                            0.0F,
                            0.0F,
                            0.0F,
                            0.0F,
                            0.0F,
                            (float) (-Math.PI / 4),
                            (float) (-Math.PI / 2),
                            -0.05235988F,
                            (float) (Math.PI / 4),
                            (float) (Math.PI / 2),
                            0.05235988F);
        }
        if (beginAttachTrailer != 0 && beginAttachTrailer + 2.0F > sim.time) {
            for (int axis = 0; axis < 6; axis++) {
                b.setConstraintERP(constraintTowing, 0.02F, axis);
            }
        }
        other.constraintTowing = constraintTowing;
        towing = other;
        other.towedBy = this;
        constraintChangedTime = sim.time;
        other.constraintChangedTime = sim.time;
        return true;
    }

    /** {@code BaseVehicle.breakConstraint(true, false)} on the client. */
    public void breakConstraint() {
        if (constraintTowing == -1) {
            return;
        }
        b.removeConstraint(constraintTowing);
        constraintTowing = -1;
        if (towing != null) {
            towing.towedBy = null;
            towing.constraintTowing = -1;
            towing.constraintChangedTime = sim.time;
            towing = null;
        }
        if (towedBy != null) {
            towedBy.towing = null;
            towedBy.constraintTowing = -1;
            towedBy.constraintChangedTime = sim.time;
            towedBy = null;
        }
        constraintChangedTime = sim.time;
    }

    /** {@code BaseVehicle.checkTrailerVerticalAlignment}. */
    private void checkTrailerVerticalAlignment() {
        if (constraintTowing != -1 && upVectorDot() < 0.8F) {
            ctx.note("tow broken: vertical alignment of " + id);
            breakConstraint();
        }
    }

    /** {@code BaseVehicle.checkTrailerAttachTime}: stiffen the hitch after 2 s. */
    private void checkTrailerAttachTime() {
        if (constraintTowing == -1) {
            return;
        }
        if (beginAttachTrailer <= 0) {
            beginAttachTrailer = 0;
        } else if (beginAttachTrailer + 2.0F <= sim.time) {
            for (int axis = 0; axis < 6; axis++) {
                b.setConstraintERP(constraintTowing, 0.2F, axis);
            }
            beginAttachTrailer = 0;
        }
    }

    /**
     * {@code Bullet.onVehicleConstraintImpulse} for every impulse reported since the last call:
     * break the tow when the impulse exceeds 1500 (500 when either vehicle is not upright) and the
     * tow is older than 2 s.
     */
    public static void handleImpulses(ScenarioContext ctx, List<SimVehicle> vehicles) {
        for (ScenarioContext.Impulse im : ctx.drainImpulses()) {
            handleImpulse(ctx, vehicles, im);
        }
    }

    /**
     * Routes impulses straight into {@link #handleImpulse} from inside the upcall, like the game:
     * the break's {@code removeConstraint} is then nested in {@code stepSimulation} (the library
     * iterates a copy of its constraint map for exactly this).
     */
    public static void breakInsideUpcall(ScenarioContext ctx, List<SimVehicle> vehicles) {
        ctx.onImpulse = im -> handleImpulse(ctx, vehicles, im);
    }

    private static void handleImpulse(
            ScenarioContext ctx, List<SimVehicle> vehicles, ScenarioContext.Impulse im) {
        SimVehicle a = find(vehicles, im.vehicleIdA());
        SimVehicle bv = find(vehicles, im.vehicleIdB());
        if (a == null || bv == null) {
            return;
        }
        float breakImpulse = a.upVectorDot() > 0.8F && bv.upVectorDot() > 0.8F ? 1500.0F : 500.0F;
        if (!(im.impulse() < breakImpulse)) {
            boolean recent = a.constraintChangedTime + 2.0F > a.sim.time;
            if (a.constraintTowing == im.constraintId() && !recent) {
                ctx.note("tow broken: impulse");
                a.breakConstraint();
            }
        }
    }

    private static SimVehicle find(List<SimVehicle> vehicles, int id) {
        for (SimVehicle v : vehicles) {
            if (v.id == id && !v.removed) {
                return v;
            }
        }
        return null;
    }

    /** Copies the matching readback into each vehicle's {@link #state}. */
    public static void applyStates(List<SimVehicle> vehicles, List<GameSim.VehicleState> states) {
        for (GameSim.VehicleState s : states) {
            SimVehicle v = find(vehicles, s.id());
            if (v != null) {
                v.state = s;
            }
        }
    }
}
