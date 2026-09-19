package io.pzstorm.storm.bullet.trace.scenario;

/**
 * Several vehicle types in one world: driving, a head-on collision, every per-vehicle setter,
 * active/static toggling, remote (network-interpolated) vehicles through {@code
 * set/getOwnVehiclePhysics}, teleports, hit impulses and add/remove churn.
 *
 * <p>Game callers: {@code VehicleScript.Loaded → defineVehicleScript}; {@code CarController.<init>
 * → addVehicle}; {@code CarController.updateControls/stop → controlVehicle}; {@code
 * BaseVehicle.update → updateBulletStats (setVehicleParams float[24]), setVehicleMass
 * (getFudgedMass), updateVelocityMultiplier (setVehicleVelocityMultiplier)}; {@code
 * BaseVehicle.setPhysicsActive → setVehicleStatic + setVehicleActive}; {@code
 * checkSurroundingChunks}; {@code BaseVehicle.setTireInflation/setTireRemoved}; {@code BaseVehicle
 * part mass change → setVehicleMass(getMass())}; {@code BaseVehicle.setWorldTransform →
 * teleportVehicle}; {@code BaseVehicle.updatePhysics hit impulses → applyCentralForceToVehicle /
 * applyTorqueToVehicle (×30)}; {@code VehicleImpulsePacket → apply* (unscaled)}; {@code
 * VehicleManager.updateVehiclePos → setOwnVehiclePhysics(collide) + getOwnVehiclePhysics}; {@code
 * VehicleFullUpdatePacket → setOwnVehiclePhysics(false)}; {@code VehiclePhysicsPacket.set →
 * getOwnVehiclePhysics}; {@code BaseVehicle.removeFromWorld → removeVehicle}; {@code
 * WorldSimulation.updateInternal} readbacks.
 */
public final class VehiclesScenario implements Scenario {

    @Override
    public String name() {
        return "vehicles";
    }

    @Override
    public String description() {
        return "4 vehicle types: driving, head-on crash, all setters, static toggles, remote"
                + " vehicles via own-physics, teleports, impulses, add/remove churn";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        VehicleScripts.Script[] scripts = {
            VehicleScripts.carNormal(),
            VehicleScripts.pickUpTruck(),
            VehicleScripts.sportsCar(),
            VehicleScripts.van()
        };
        for (VehicleScripts.Script s : scripts) {
            ctx.bullet.defineVehicleScript(s.fullName(), s.toBullet());
        }
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, false);
        Fleet fleet = new Fleet(ctx, sim);

        ctx.note("spawn");
        // vehicles spawn on open ground, never inside a tree, a solid or another vehicle (see
        // Terrain.isClear, Fleet.clearSpot)
        float[] at = fleet.clearSpot(px, py);
        SimVehicle player =
                fleet.spawn(scripts[0], at[0], at[1], ctx.random.nextDouble() * Math.PI * 2);
        at = fleet.clearSpot(px + 8, py - 6);
        SimVehicle parked = fleet.spawn(scripts[1], at[0], at[1], Math.PI / 2);
        // the head-on pair faces each other, both ends of the lane clear
        at = fleet.clearSpot(px - 14, py + 12, 0, 0, 0, 14);
        SimVehicle crashA = fleet.spawn(scripts[2], at[0], at[1], 0);
        SimVehicle crashB = fleet.spawn(scripts[3], at[0], at[1] + 14, Math.PI);
        fleet.settle(40);

        ctx.note("drive");
        float[] own = new float[27];
        Remote remote = new Remote();
        float steerBias = ctx.range(-0.4F, 0.4F);
        for (int f = 0; f < 900; f++) {
            float t = f / 60.0F;
            // the local player's car: accelerate, weave, brake hard at the end
            if (f < 700) {
                float gas = player.script.engineForce() * Math.min(1, t / 1.5F);
                float steer = steerBias + (float) (0.3 * Math.sin(t * 0.9));
                player.control(gas, 0, steer);
            } else if (f == 700) {
                player.stop();
            }
            // head-on: the two meet in the middle
            if (f >= 30 && f < 400) {
                crashA.control(crashA.script.engineForce() * 0.8F, 0, 0);
                crashB.control(crashB.script.engineForce() * 0.8F, 0, 0);
            } else if (f == 400) {
                crashA.stop();
                crashB.stop();
            }
            setters(ctx, fleet, player, parked, crashA, f);
            // remote vehicle: crashB is taken over by the network for 3 s
            if (f >= 450 && f < 630 && crashB.state != null && !crashB.removed) {
                remoteUpdate(ctx, fleet, crashB, remote, own, f);
            }
            // VehiclePhysicsPacket: the driver's client sends its own physics ~ every 10 frames
            if (f % 10 == 0) {
                ctx.bullet.getOwnVehiclePhysics(player.id, own);
            }
            fleet.frame(Fleet.substeps(f));
            fleet.follow(player);
        }

        ctx.note("churn");
        crashA.remove();
        fleet.settle(2);
        for (int i = 0; i < 6; i++) {
            VehicleScripts.Script s = scripts[ctx.random.nextInt(scripts.length)];
            float ox = ctx.range(-30, 30);
            float oy = ctx.range(-30, 30);
            float cx = player.state == null ? px : player.worldX();
            float cy = player.state == null ? py : player.worldY();
            float[] spot = fleet.clearSpot(cx + ox, cy + oy);
            SimVehicle v = fleet.spawn(s, spot[0], spot[1], ctx.random.nextDouble() * Math.PI * 2);
            fleet.settle(5 + ctx.random.nextInt(20));
            if (ctx.random.nextBoolean()) {
                v.control(v.script.engineForce(), 0, ctx.range(-0.5F, 0.5F));
                fleet.settle(20);
            }
            if (i % 2 == 0) {
                v.remove();
                fleet.settle(1);
            }
        }

        ctx.note("teardown");
        fleet.noteHeights();
        fleet.removeAll();
        fleet.frame(1);
        sim.destroyWorld();
    }

    /** Scheduled uses of every per-vehicle setter, at fixed frames. */
    private static void setters(
            ScenarioContext ctx,
            Fleet fleet,
            SimVehicle player,
            SimVehicle parked,
            SimVehicle crashA,
            int f) {
        switch (f) {
            case 60 -> player.raining = true;
            case 90 -> player.setTireInflation(0, 0.3F);
            case 120 -> player.offroad = true;
            case 150 -> parked.setPhysicsActive(false); // parked: the player walked away
            case 180 -> player.setMass(player.script.mass() + 150); // trunk filled
            case 210 -> player.setTireRemoved(2, true);
            case 260 -> {
                player.setTireRemoved(2, false);
                player.setTireInflation(2, 0.8F);
            }
            case 280 -> player.suspension[1] = false; // suspension part removed
            case 300 -> player.raining = false;
            case 330 -> {
                // collision with a hit object (zombie/tree) pushes the car
                float a = ctx.range(0, (float) Math.PI * 2);
                player.applyHit(
                        (float) Math.cos(a) * 3000,
                        200,
                        (float) Math.sin(a) * 3000,
                        0,
                        ctx.range(-800, 800),
                        0);
            }
            case 360 -> parked.setPhysicsActive(true);
            case 380 -> {
                // server-sent impulse (VehicleImpulsePacket)
                parked.applyNetImpulse(0, 4000, 1500, 200, 0, 0);
            }
            case 420 -> player.breakingSlowFactor = 10;
            case 500 -> {
                // BaseVehicle.setWorldTransform: the parked truck is moved (e.g. by an admin)
                // onto open ground, clear of the other vehicles (a body placed into a box is
                // pushed out through the floor)
                if (parked.state != null) {
                    float[] to =
                            fleet.clearSpotFor(parked, parked.worldX() + 3, parked.worldY() - 2);
                    parked.teleport(
                            to[0],
                            to[1],
                            GameSim.physicsZ(parked.script, 0),
                            ctx.random.nextDouble() * Math.PI * 2);
                }
            }
            case 540 -> {
                player.offroad = false;
                player.breakingSlowFactor = 0;
                player.suspension[1] = true;
                player.setTireInflation(0, 1.0F);
            }
            case 580 -> parked.setPhysicsActive(false);
            case 600 -> parked.setPhysicsActive(true);
            case 650 -> player.setMass(player.script.mass());
            case 680 -> crashA.burnt = true;
            default -> {}
        }
    }

    /** The remote owner's view of its vehicle: where its client has it. */
    private static final class Remote {
        boolean started;
        float x;
        float y;
        float height;
        double yaw;
        boolean stopped;
    }

    /**
     * {@code VehicleManager.updateVehiclePos} for a remotely-owned vehicle: interpolated
     * position/rotation/velocity (world squares) handed to {@code setOwnVehiclePhysics}; with
     * {@code collide} (another remote vehicle within 10 squares) the library resolves and the
     * result is read back with {@code getOwnVehiclePhysics}. The owner's client drives straight on
     * at its own (steady) height and brakes before a tree / solid / wall, as a client whose own
     * physics collides would; feeding our body's height back would let it sink.
     */
    private static void remoteUpdate(
            ScenarioContext ctx, Fleet fleet, SimVehicle v, Remote r, float[] dd, int f) {
        GameSim.VehicleState s = v.state;
        if (!r.started) {
            r.started = true;
            r.x = v.worldX();
            r.y = v.worldY();
            r.height = s.y();
            r.yaw = Math.atan2(2 * (s.qw() * s.qy()), 1 - 2 * s.qy() * s.qy());
        }
        float speed = r.stopped ? 0.0F : 4.0F;
        float sin = (float) Math.sin(r.yaw), cos = (float) Math.cos(r.yaw);
        if (!r.stopped
                && !ctx.terrain.isClear(
                        (int) Math.floor(r.x + sin * 3), (int) Math.floor(r.y + cos * 3), 1, 1)) {
            r.stopped = true;
            speed = 0;
        }
        float vx = sin * speed;
        float vz = cos * speed;
        r.x += vx / 60.0F;
        r.y += vz / 60.0F;
        float[] q = GameSim.yawQuat(r.yaw);
        int i = 0;
        dd[i++] = r.x;
        dd[i++] = r.y;
        dd[i++] = r.height;
        dd[i++] = q[0];
        dd[i++] = q[1];
        dd[i++] = q[2];
        dd[i++] = q[3];
        dd[i++] = vx;
        dd[i++] = 0.0F;
        dd[i++] = vz;
        int wheels = Math.min(4, s.wheels().length / 4);
        dd[i++] = wheels + 0.1F; // getOwnVehiclePhysics writes count + 0.1
        for (int w = 0; w < wheels * 4; w++) {
            dd[i++] = s.wheels()[w];
        }
        while (i < dd.length) {
            dd[i++] = 0;
        }
        boolean collide = false;
        for (SimVehicle o : fleet.live()) {
            if (o != v && o.state != null) {
                float dx = o.worldX() - v.worldX(), dy = o.worldY() - v.worldY();
                if (dx * dx + dy * dy < 100) {
                    collide = true;
                }
            }
        }
        if (f % 40 == 0) {
            // VehicleFullUpdatePacket: a full snapshot, never with collision
            ctx.bullet.setOwnVehiclePhysics(v.id, dd, false);
        } else if (collide) {
            ctx.bullet.setOwnVehiclePhysics(v.id, dd, true);
            ctx.bullet.getOwnVehiclePhysics(v.id, dd);
            r.x = dd[0];
            r.y = dd[1];
        } else {
            ctx.bullet.setOwnVehiclePhysics(v.id, dd, false);
        }
    }
}
