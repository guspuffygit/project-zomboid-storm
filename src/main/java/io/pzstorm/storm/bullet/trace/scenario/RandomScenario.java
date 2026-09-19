package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * Seed-driven variety: world bounds, offsets, player position, terrain density, vehicle mix and a
 * random schedule of the game's vehicle / tow / ragdoll operations all come from the seed. Every
 * individual call keeps the game caller of the scenario it is borrowed from ({@link
 * VehiclesScenario}, {@link TowingScenario}, {@link RagdollScenario}); what is random is only which
 * happens when, and where.
 *
 * <p>World: cells min in 0..(player cell), max in (player cell)..66 x ..63, offsets = minCell * 256
 * ({@code IsoWorld.MetaGrid} bounds as {@code WorldSimulation.create} passes them), player square
 * ~3000..15000.
 */
public final class RandomScenario implements Scenario {

    @Override
    public String name() {
        return "random";
    }

    @Override
    public String description() {
        return "seed-driven world bounds/offsets/position and a random schedule of vehicle, tow,"
                + " ragdoll and chunk-map operations";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        VehicleScripts.Script[] scripts = {
            VehicleScripts.carNormal(),
            VehicleScripts.pickUpTruck(),
            VehicleScripts.sportsCar(),
            VehicleScripts.van(),
            VehicleScripts.trailer()
        };
        for (VehicleScripts.Script s : scripts) {
            ctx.bullet.defineVehicleScript(s.fullName(), s.toBullet());
        }
        RagdollData.uploadScripts(ctx.bullet, false);

        float px = ctx.range(3000, 15000);
        float py = ctx.range(3000, 15000);
        int pcx = (int) (px / 256), pcy = (int) (py / 256);
        int minCX = ctx.random.nextInt(pcx + 1);
        int minCY = ctx.random.nextInt(pcy + 1);
        int maxCX = pcx + ctx.random.nextInt(66 - pcx + 1);
        int maxCY = pcy + ctx.random.nextInt(Math.max(1, 63 - pcy + 1));
        int grid = ctx.random.nextBoolean() ? 13 : 11 + 2 * ctx.random.nextInt(4);
        ctx.note(
                "world cells "
                        + minCX
                        + ","
                        + minCY
                        + ".."
                        + maxCX
                        + ","
                        + maxCY
                        + " player "
                        + px
                        + ","
                        + py
                        + " grid "
                        + grid);
        sim.createWorld(minCX, minCY, maxCX, maxCY, px, py, grid, false);
        ctx.terrain.density = 0.05 + ctx.random.nextDouble() * 0.5;
        ctx.note("terrain density " + ctx.terrain.density);

        Fleet fleet = new Fleet(ctx, sim);
        int nv = 2 + ctx.random.nextInt(5);
        for (int i = 0; i < nv; i++) {
            spawnNear(ctx, fleet, scripts, px, py);
        }
        fleet.settle(30);
        SimVehicle.handleImpulses(ctx, fleet.vehicles);
        if (ctx.random.nextBoolean()) {
            SimVehicle.breakInsideUpcall(ctx, fleet.vehicles);
        }

        boolean ragdolls = ctx.random.nextInt(3) != 0;
        List<SimRagdoll> rs = new ArrayList<>();
        if (ragdolls) {
            RagdollData.initializeBuilder(ctx);
            int base = 500 + ctx.random.nextInt(5000);
            int n = 1 + ctx.random.nextInt(4);
            for (int i = 0; i < n; i++) {
                SimRagdoll r =
                        new SimRagdoll(
                                ctx,
                                base + i,
                                px + ctx.range(-4, 4),
                                py + ctx.range(-4, 4),
                                0,
                                ctx.range(0, 6.2831855F));
                r.initialize(1.0F / 60.0F);
                rs.add(r);
            }
        }

        float[] own = new float[27];
        int frames = 500 + ctx.random.nextInt(300);
        ctx.note("random ops");
        for (int f = 0; f < frames; f++) {
            List<SimVehicle> live = fleet.live();
            SimVehicle lead = live.isEmpty() ? null : live.get(0);
            for (SimVehicle v : live) {
                if (v.isTrailer()) {
                    continue;
                }
                float g = (float) Math.sin(f / 90.0 + v.id);
                v.control(
                        g > -0.3F ? v.script.engineForce() * (0.3F + 0.5F * g) : 0,
                        g <= -0.3F ? v.script.brakingForce() : 0,
                        0.35F * (float) Math.sin(f / 50.0 + v.id * 1.7));
            }
            if (ctx.random.nextInt(40) == 0 && !live.isEmpty()) {
                randomOp(ctx, fleet, scripts, live, own, px, py);
            }
            for (SimRagdoll r : rs) {
                if (r.addedToWorld) {
                    r.update();
                    if (ctx.random.nextInt(90) == 0) {
                        r.hit(
                                ctx.random.nextInt(RagdollData.BODY_PARTS),
                                ctx.range(-1, 1),
                                ctx.range(-1, 1),
                                ctx.range(10, 80),
                                ctx.range(10, 40));
                    }
                }
            }
            fleet.frame(Fleet.substeps(f));
            if (lead != null) {
                fleet.follow(lead);
            }
        }

        ctx.note("teardown");
        for (SimRagdoll r : rs) {
            r.remove();
        }
        ctx.onImpulse = null;
        fleet.noteHeights();
        fleet.removeAll();
        fleet.frame(1);
        SimVehicle.handleImpulses(ctx, fleet.vehicles);
        sim.destroyWorld();
    }

    private static SimVehicle spawnNear(
            ScenarioContext ctx, Fleet fleet, VehicleScripts.Script[] scripts, float x, float y) {
        VehicleScripts.Script s = scripts[ctx.random.nextInt(scripts.length)];
        // vehicles spawn on open ground (road/parking zones), never inside a tree or a solid
        float[] at = fleet.clearSpot(x + ctx.range(-20, 20), y + ctx.range(-20, 20));
        return fleet.spawn(s, at[0], at[1], ctx.random.nextDouble() * Math.PI * 2);
    }

    private static void randomOp(
            ScenarioContext ctx,
            Fleet fleet,
            VehicleScripts.Script[] scripts,
            List<SimVehicle> live,
            float[] own,
            float px,
            float py) {
        SimVehicle v = live.get(ctx.random.nextInt(live.size()));
        int op = ctx.random.nextInt(12);
        ctx.note("op " + op + " on " + v.id);
        switch (op) {
            case 0 -> {
                if (live.size() < 8) {
                    float cx = v.state == null ? px : v.worldX();
                    float cy = v.state == null ? py : v.worldY();
                    spawnNear(ctx, fleet, scripts, cx, cy);
                }
            }
            case 1 -> {
                if (live.size() > 1) {
                    v.remove();
                }
            }
            case 2 -> v.setTireInflation(ctx.random.nextInt(v.tire.length), ctx.range(0, 1));
            case 3 -> {
                int w = ctx.random.nextInt(v.tire.length);
                v.setTireRemoved(w, v.tire[w]);
            }
            case 4 -> v.setPhysicsActive(!v.desirePhysicsActive);
            case 5 ->
                    v.applyHit(
                            ctx.range(-4000, 4000),
                            ctx.range(0, 800),
                            ctx.range(-4000, 4000),
                            0,
                            ctx.range(-600, 600),
                            0);
            case 6 -> v.setMass(v.script.mass() + ctx.range(0, 300));
            case 7 -> {
                v.offroad = !v.offroad;
                v.raining = ctx.random.nextBoolean();
            }
            case 8 -> {
                // tow the nearest other vehicle within reach (ISVehicleMenu only offers close
                // ones); trailer rules as in addPointConstraint
                SimVehicle o = null;
                float best = 64.0F;
                for (SimVehicle c : live) {
                    if (c == v || c.state == null || v.state == null) {
                        continue;
                    }
                    float dx = c.worldX() - v.worldX(), dy = c.worldY() - v.worldY();
                    if (dx * dx + dy * dy < best) {
                        best = dx * dx + dy * dy;
                        o = c;
                    }
                }
                if (o != null && !v.isTrailer()) {
                    String attachB =
                            o.isTrailer() ? VehicleScripts.TRAILER : VehicleScripts.TRAILER_FRONT;
                    v.attach(o, VehicleScripts.TRAILER, attachB, o.isTrailer());
                }
            }
            case 9 -> v.breakConstraint();
            case 10 -> {
                if (v.state != null) {
                    ctx.bullet.getOwnVehiclePhysics(v.id, own);
                    ctx.bullet.setOwnVehiclePhysics(v.id, own, ctx.random.nextBoolean());
                }
            }
            default -> {
                if (v.state != null && v.towing == null && v.towedBy == null) {
                    float[] at =
                            fleet.clearSpotFor(
                                    v,
                                    v.worldX() + ctx.range(-3, 3),
                                    v.worldY() + ctx.range(-3, 3));
                    v.teleport(
                            at[0],
                            at[1],
                            GameSim.physicsZ(v.script, 0),
                            ctx.random.nextDouble() * Math.PI * 2);
                }
            }
        }
    }
}
