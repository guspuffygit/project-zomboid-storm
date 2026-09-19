package io.pzstorm.storm.bullet.trace.scenario;

/**
 * Physics-shape meshes (terrain furniture and a vehicle mesh shape), debug physics objects and
 * server cells, with a car driving among the mesh-bearing squares.
 *
 * <p>Game callers: {@code Bullet.initPhysicsMeshes} (after {@code WorldSimulation.create}) → {@code
 * clearPhysicsMeshes}, {@code definePhysicsMesh(shapeIndex, meshes > 1, points)} for every mesh of
 * every physics-shape script (meshes of one shape share its index), then {@code
 * defineVehiclePhysicsMesh(fullName, 1 + j, points * modelScale)} for each type-3 shape j of each
 * vehicle script; {@code IsoChunk.calcPhysics} then emits the mesh shapes (terrain.meshCount);
 * {@code VehicleScript.Loaded → defineVehicleScript}; Lua debug {@code addPhysicsObject() →
 * Bullet.addPhysicsObject(player x, y)}; {@code WorldSimulation.updateInternal → getObjectPhysics}
 * (id, x, height, y per object). createServerCell / removeServerCell have no Java caller.
 */
public final class PhysicsObjectsScenario implements Scenario {

    @Override
    public String name() {
        return "physics-objects";
    }

    @Override
    public String description() {
        return "physics meshes (single, compound, vehicle type-3), terrain mesh shapes, debug"
                + " physics objects, server cells, a mesh-shaped wreck hit by a car";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        VehicleScripts.Script car = VehicleScripts.carNormal();
        VehicleScripts.Script wreck = VehicleScripts.meshWreck();

        for (int round = 0; round < 2; round++) {
            ctx.note("world " + round);
            // WorldSimulation's destructor frees every PZVehicleScript, so a second world needs
            // the scripts again (the game reloads scripts on every game load: VehicleScript.Loaded
            // runs before WorldSimulation.create)
            ctx.bullet.defineVehicleScript(car.fullName(), car.toBullet());
            ctx.bullet.defineVehicleScript(wreck.fullName(), wreck.toBullet());
            float px = 10620.5F + ctx.range(-60, 60);
            float py = 9860.5F + ctx.range(-60, 60);
            sim.createWorld(0, 0, 66, 63, px, py, 13, false);
            int shapes = initPhysicsMeshes(ctx, wreck);
            ctx.terrain.meshCount = shapes;
            ctx.terrain.density = round == 0 ? 0.25 : 0.35;

            Fleet fleet = new Fleet(ctx, sim);
            // both on open ground (Terrain.isClear), the car 14 squares west of the wreck
            float[] at = fleet.clearSpot(px + 6, py, 0, 0, -14, 0);
            SimVehicle w = fleet.spawn(wreck, at[0], at[1], ctx.random.nextDouble() * Math.PI);
            SimVehicle c = fleet.spawn(car, at[0] - 14, at[1], Math.PI / 2);
            fleet.settle(30);

            // server cells around the player (40-square native cells)
            int cx = (int) Math.floor(px / 40), cy = (int) Math.floor(py / 40);
            ctx.bullet.createServerCell(cx, cy);
            ctx.bullet.createServerCell(cx, cy); // duplicate: found, ignored
            ctx.bullet.createServerCell(cx + 1, cy);
            ctx.bullet.createServerCell(cx, cy - 1);

            int objects = 0;
            for (int f = 0; f < 360; f++) {
                if (f < 240) {
                    c.control(c.script.engineForce() * 0.7F, 0, 0.05F * (float) Math.sin(f / 30.0));
                } else if (f == 240) {
                    c.stop();
                }
                if (f % 45 == 10 && objects < 6) {
                    // Lua debug addPhysicsObject at the (walking) player position
                    float ox = px + ctx.range(-4, 4), oy = py + ctx.range(-4, 4);
                    ctx.bullet.addPhysicsObject(ox, oy);
                    objects++;
                }
                if (f == 120) {
                    ctx.bullet.removeServerCell(cx + 1, cy);
                }
                if (f == 200) {
                    ctx.bullet.removeServerCell(cx + 5, cy + 5); // never created
                }
                fleet.frame(Fleet.substeps(f));
                if (f % 30 == 0) {
                    int n = ctx.bullet.getObjectPhysics(sim.ff);
                    ctx.note("objects " + n);
                }
                fleet.follow(c);
            }
            ctx.bullet.removeServerCell(cx, cy);
            ctx.bullet.removeServerCell(cx, cy - 1);
            fleet.noteHeights();
            w.remove();
            fleet.removeAll();
            fleet.frame(1);
            ctx.terrain.meshCount = 0;
            sim.destroyWorld();
        }
    }

    /**
     * {@code Bullet.initPhysicsMeshes}: three physics-shape scripts (a single-mesh crate, a
     * two-mesh compound counter, a single-mesh barrel), then the wreck's type-3 shape as a two-mesh
     * asset (two defineVehiclePhysicsMesh calls with the same index). Returns the shape count.
     */
    static int initPhysicsMeshes(ScenarioContext ctx, VehicleScripts.Script wreck) {
        ctx.bullet.clearPhysicsMeshes();
        int shapeCount = 0;
        ctx.bullet.definePhysicsMesh(shapeCount, false, box(0, 0.25F, 0, 0.3F, 0.25F, 0.3F));
        shapeCount++;
        ctx.bullet.definePhysicsMesh(shapeCount, true, box(-0.2F, 0.45F, 0, 0.25F, 0.45F, 0.45F));
        ctx.bullet.definePhysicsMesh(shapeCount, true, box(0.25F, 0.9F, 0, 0.2F, 0.05F, 0.45F));
        shapeCount++;
        ctx.bullet.definePhysicsMesh(shapeCount, false, cylinder(0, 0.4F, 0, 0.28F, 0.4F, 12));
        shapeCount++;
        float s = wreck.modelScale();
        ctx.bullet.defineVehiclePhysicsMesh(
                wreck.fullName(), 1, scale(box(0, 0.1F, 0.2F, 0.4F, 0.2F, 0.6F), s));
        ctx.bullet.defineVehiclePhysicsMesh(
                wreck.fullName(), 1, scale(cylinder(0, 0.35F, -0.5F, 0.3F, 0.15F, 8), s));
        return shapeCount;
    }

    /** The 8 corners of a box centred at (x, y, z) with half extents (hx, hy, hz). */
    static float[] box(float x, float y, float z, float hx, float hy, float hz) {
        float[] p = new float[24];
        int i = 0;
        for (int c = 0; c < 8; c++) {
            p[i++] = x + ((c & 1) == 0 ? -hx : hx);
            p[i++] = y + ((c & 2) == 0 ? -hy : hy);
            p[i++] = z + ((c & 4) == 0 ? -hz : hz);
        }
        return p;
    }

    /** An n-sided upright prism: 2n points. */
    static float[] cylinder(float x, float y, float z, float r, float hy, int n) {
        float[] p = new float[n * 6];
        int i = 0;
        for (int k = 0; k < n; k++) {
            double a = 2 * Math.PI * k / n;
            float px = x + r * (float) Math.cos(a), pz = z + r * (float) Math.sin(a);
            p[i++] = px;
            p[i++] = y - hy;
            p[i++] = pz;
            p[i++] = px;
            p[i++] = y + hy;
            p[i++] = pz;
        }
        return p;
    }

    static float[] scale(float[] p, float s) {
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            out[i] = p[i] * s;
        }
        return out;
    }
}
