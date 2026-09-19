package io.pzstorm.storm.bullet.trace.scenario;

/**
 * Towing: a pickup hitched to a trailer (6-DOF with the soft-then-stiff ERP of the attach window),
 * a car towing a car on a rope, tows broken by constraint impulses both from the drained queue and
 * from inside the upcall (as the game does), by the vertical-alignment check, by removal, plus the
 * point and hinge constraints and a stale removeConstraint.
 *
 * <p>Game callers: {@code BaseVehicle.addPointConstraint → breakConstraint on both,
 * addRopeConstraint(…, 1.5) when neither is a trailer, else add6DofConstraint(limits ±PI/4, ±PI/2,
 * ±0.05235988)}, {@code setConstraintERP(0.02, axis 0..5)} inside the 2 s trailer-attach window;
 * {@code checkTrailerAttachTime → setConstraintERP(0.2, 0..5)}; {@code
 * checkTrailerVerticalAlignment} and {@code Bullet.onVehicleConstraintImpulse → breakConstraint →
 * removeConstraint} (impulse ≥ 1500, or 500 when not upright, tow older than 2 s); {@code
 * BaseVehicle.removeFromWorld} breaks tows. addPointConstraint / addHingeConstraint have no Java
 * caller in 42.20 and are exercised directly.
 */
public final class TowingScenario implements Scenario {

    @Override
    public String name() {
        return "towing";
    }

    @Override
    public String description() {
        return "trailer 6-DOF hitch with ERP ramp, car-on-rope, impulse breaks (queued and"
                + " re-entrant), alignment break, point/hinge constraints, stale removes";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        VehicleScripts.Script truckS = VehicleScripts.pickUpTruck();
        VehicleScripts.Script trailerS = VehicleScripts.trailer();
        VehicleScripts.Script carS = VehicleScripts.carNormal();
        VehicleScripts.Script sportS = VehicleScripts.sportsCar();
        for (VehicleScripts.Script s :
                new VehicleScripts.Script[] {truckS, trailerS, carS, sportS}) {
            ctx.bullet.defineVehicleScript(s.fullName(), s.toBullet());
        }
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, false);
        ctx.terrain.density = 0.1; // fewer walls in the way of the convoy
        Fleet fleet = new Fleet(ctx, sim);

        // the convoy parks on open ground: every vehicle's square is clear (Terrain.isClear),
        // and so is the lane ahead (yaw 0 drives +y), so the tows are pulled rather than jammed
        // against a wall (a trailer wedged there sinks into the floor once the alignment torque
        // rolls it)
        float[] at =
                fleet.clearSpot(
                        px, py, 0, 0, 0, -4.2F, 9, 0, 9, -5.2F, 0, 4, 0, 8, 0, 12, 9, 4, 9, 8, 9,
                        12);
        px = at[0];
        py = at[1];
        SimVehicle truck = fleet.spawn(truckS, px, py, 0);
        SimVehicle trailer = fleet.spawn(trailerS, px, py - 4.2F, 0);
        SimVehicle car = fleet.spawn(carS, px + 9, py, 0);
        SimVehicle towed = fleet.spawn(sportS, px + 9, py - 5.2F, 0);
        fleet.settle(30);

        ctx.note("attach (queued impulse handling)");
        truck.attach(trailer, VehicleScripts.TRAILER, VehicleScripts.TRAILER, true);
        car.attach(towed, VehicleScripts.TRAILER, VehicleScripts.TRAILER_FRONT, false);
        drive(ctx, fleet, truck, car, 360, 0.6F);

        ctx.note("jerk");
        // a crash into the rope-towed car after the 2 s grace period: BaseVehicle.updatePhysics
        // hit force for one frame (times 30; 40 * mass is ~12 m/s per 10 ms step, a hard crash
        // well under the 1500 * mass cap) yanks the tow hard enough to report
        for (int i = 0; i < 20; i++) {
            if (!towed.removed && i == 1) {
                towed.applyHit(0, 0, -40.0F * towed.script.mass(), 0, 0, 0);
            }
            if (!trailer.removed && i == 1) {
                // rear-ended trailer: the hitch's locked linear rows take the hit
                trailer.applyHit(0, 0, 40.0F * trailer.script.mass(), 0, 0, 0);
            }
            fleet.frame(Fleet.substeps(i));
        }
        fleet.settle(30);

        ctx.note("re-entrant impulse handling");
        SimVehicle.breakInsideUpcall(ctx, fleet.vehicles);
        if (car.constraintTowing == -1) {
            car.attach(towed, VehicleScripts.TRAILER, VehicleScripts.TRAILER_FRONT, false);
        }
        if (truck.constraintTowing == -1) {
            truck.attach(trailer, VehicleScripts.TRAILER, VehicleScripts.TRAILER, true);
        }
        drive(ctx, fleet, truck, car, 180, 1.0F);
        for (int i = 0; i < 25; i++) {
            if (!towed.removed && i == 1) {
                towed.applyHit(
                        ctx.range(-10, 10) * towed.script.mass(),
                        0,
                        -40.0F * towed.script.mass(),
                        0,
                        0,
                        0);
            }
            if (!trailer.removed && i == 1) {
                // again, now broken from inside the upcall
                trailer.applyHit(0, 0, 40.0F * trailer.script.mass(), 0, 0, 0);
            }
            fleet.frame(Fleet.substeps(i));
        }
        fleet.settle(20);

        ctx.note("alignment");
        // the trailer is rolled over by a torque burst: checkTrailerVerticalAlignment breaks it
        // after 5-10 frames of roll (a burst 10x stronger snaps the hitch by impulse on the
        // first frame instead and can spin the trailer through the floor)
        if (truck.constraintTowing == -1) {
            truck.attach(trailer, VehicleScripts.TRAILER, VehicleScripts.TRAILER, false);
        }
        fleet.settle(10);
        for (int i = 0; i < 60 && truck.constraintTowing != -1; i++) {
            if (i < 12) {
                trailer.applyHit(0, 2000.0F, 0, 0, 0, 4000.0F);
            }
            fleet.frame(Fleet.substeps(i));
        }
        fleet.settle(60);

        ctx.note("point/hinge");
        directConstraints(ctx, fleet, car, towed);

        ctx.note("remove while towing");
        if (car.constraintTowing == -1 && !car.removed && !towed.removed) {
            car.attach(towed, VehicleScripts.TRAILER, VehicleScripts.TRAILER_FRONT, false);
        }
        fleet.settle(10);
        towed.remove(); // BaseVehicle.removeFromWorld: the tow goes first
        fleet.settle(10);

        ctx.note("teardown");
        ctx.onImpulse = null;
        fleet.noteHeights();
        fleet.removeAll();
        fleet.frame(1);
        SimVehicle.handleImpulses(ctx, fleet.vehicles);
        sim.destroyWorld();
    }

    /** Both towing vehicles drive with a gentle weave; the chunk map follows the truck. */
    private static void drive(
            ScenarioContext ctx,
            Fleet fleet,
            SimVehicle truck,
            SimVehicle car,
            int frames,
            float throttle) {
        float bias = ctx.range(-0.1F, 0.1F);
        for (int f = 0; f < frames; f++) {
            float steer = bias + 0.25F * (float) Math.sin(f / 70.0);
            if (!truck.removed) {
                truck.control(truck.script.engineForce() * throttle, 0, steer);
            }
            if (!car.removed) {
                car.control(car.script.engineForce() * throttle, 0, -steer);
            }
            fleet.frame(Fleet.substeps(f));
            fleet.follow(truck);
        }
        if (!truck.removed) {
            truck.stop();
        }
        if (!car.removed) {
            car.stop();
        }
        fleet.settle(40);
    }

    /**
     * The constraint natives without a Java caller: a point (ball-socket) and a hinge between the
     * two cars, ERP changes on them, removal, and a second remove of the same (now stale) id.
     */
    private static void directConstraints(
            ScenarioContext ctx, Fleet fleet, SimVehicle a, SimVehicle b) {
        if (a.removed || b.removed) {
            return;
        }
        a.breakConstraint();
        b.breakConstraint();
        float[] pa = a.script.attachmentLocalPos(VehicleScripts.TRAILER);
        float[] pb = b.script.attachmentLocalPos(VehicleScripts.TRAILER_FRONT);
        int point =
                ctx.bullet.addPointConstraint(a.id, b.id, pa[0], pa[1], pa[2], pb[0], pb[1], pb[2]);
        for (int axis = 0; axis < 6; axis++) {
            ctx.bullet.setConstraintERP(point, 0.3F, axis);
        }
        fleet.settle(60);
        ctx.bullet.removeConstraint(point);
        fleet.settle(5);
        int hinge =
                ctx.bullet.addHingeConstraint(
                        a.id, b.id, pa[0], pa[1] + 0.1F, pa[2], pb[0], pb[1] + 0.1F, pb[2]);
        ctx.bullet.setConstraintERP(hinge, 0.8F, 0);
        for (int f = 0; f < 60; f++) {
            if (!a.removed) {
                a.control(a.script.engineForce() * 0.5F, 0, 0.2F);
            }
            fleet.frame(Fleet.substeps(f));
        }
        a.stop();
        ctx.bullet.removeConstraint(hinge);
        ctx.bullet.removeConstraint(hinge); // stale id: the library ignores it
        ctx.bullet.removeConstraint(point);
        fleet.settle(20);
    }
}
