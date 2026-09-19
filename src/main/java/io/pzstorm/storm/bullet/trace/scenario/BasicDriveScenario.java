package io.pzstorm.storm.bullet.trace.scenario;

import java.util.List;

/**
 * The smallest end-to-end session: boot, world + chunk map around Muldraugh, terrain streamed in
 * through the {@code updatePhysicsForLevelIfNeeded} upcall, one CarNormal driven with steering and
 * braking, the chunk map following it, readbacks every frame.
 */
public final class BasicDriveScenario implements Scenario {

    @Override
    public String name() {
        return "basic-drive";
    }

    @Override
    public String description() {
        return "boot, world, chunk map, terrain via ToBullet, one car driven for ~20 s of game time";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, false);

        VehicleScripts.Script car = VehicleScripts.carNormal();
        ctx.bullet.defineVehicleScript(car.fullName(), car.toBullet());
        int id = 1 + ctx.random.nextInt(200);
        double yaw = ctx.random.nextDouble() * Math.PI * 2;
        ctx.note("addVehicle");
        ctx.bullet.addVehicle(
                id,
                px,
                py,
                GameSim.physicsZ(car, 0),
                0.0F,
                (float) Math.sin(yaw / 2),
                0.0F,
                (float) Math.cos(yaw / 2),
                car.fullName());
        ctx.bullet.setVehicleActive(id, true);

        // settle
        for (int i = 0; i < 30; i++) {
            sim.frame(2);
        }
        ctx.note("drive");
        int frames = 1200;
        for (int f = 0; f < frames; f++) {
            float t = f / 60.0F;
            float engine = f < 900 ? car.engineForce() * Math.min(1, t / 2) : 0;
            float braking = f >= 900 ? car.brakingForce() : 0;
            float steer = (float) (0.35 * Math.sin(t * 0.7 + ctx.random.nextFloat() * 0.01));
            ctx.bullet.controlVehicle(id, engine, braking, steer);
            List<GameSim.VehicleState> v = sim.frame(1 + (f % 3 == 0 ? 1 : 0));
            if (!v.isEmpty()) {
                GameSim.VehicleState s = v.get(0);
                sim.follow(s.x() + sim.offsetX, s.z() + sim.offsetY);
            }
            if (f % 120 == 0) {
                ctx.bullet.getOwnVehiclePhysics(id, new float[27]);
            }
        }
        ctx.note("teardown");
        ctx.bullet.removeVehicle(id);
        sim.frame(1);
        ctx.bullet.destroyWorld();
    }
}
