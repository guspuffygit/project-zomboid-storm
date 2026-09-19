package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * The ragdoll lifecycle of zombies falling around a car: script upload, the builder singleton, mass
 * tuning, add / initialise / simulate / hit / vehicle contact / remove, live script re-uploads, the
 * debug toggles, and the ragdoll natives that have no Java caller in 42.20.
 *
 * <p>Game callers: {@code ScriptManager.Load:604 → RagdollScript.toBullet(false)}
 * (defineRagdollBodyDynamics, Constraints, Anchors, BodyPartInfo in that order); {@code
 * RagdollScript.reset*ToDefaultValues → define*(…, true)}; {@code RagdollBuilder.Initialize →
 * initializeRagdollSkeleton(35, int[245]), setBallisticsTargetAdjustingShapeScale,
 * initializeRagdollPose(35, float[245], q)}; {@code RagdollSettingsManager → RagdollBuilder.setMass
 * → setRagdollMass}; {@code RagdollController.addToWorld → addRagdoll}; {@code
 * updateRagdollSkeleton → setRagdollLocalTransformRotation, updateRagdoll,
 * updateRagdollSkeletonTransforms(35, float[245]), updateRagdollSkeletonPreviousTransforms(35, dt,
 * float[245])}; {@code setActive → setRagdollActive}; {@code simulateRagdoll →
 * simulateRagdollWithRigidBodyOutput(float[245], float[77])}; {@code getRagdollSimulationState};
 * {@code simulateHitReaction → applyImpulse(float[6])}; {@code BaseVehicle vehicle contact →
 * setRagdollBodyDynamics / resetRagdollBodyDynamics}; {@code RagdollControllerDebugRenderer →
 * drawDebugRagdollSkeleton / drawDebugRagdollBodyParts / setRagdollActive}; {@code removeFromWorld
 * → removeRagdoll}. No Java caller (exercised directly): detachConstraint (guarded by {@code false
 * &}), simulateRagdoll, updateSkeletonFromNetworkPhysics, getCorrectedWorldSpace,
 * resetSkeletonPose, applyForce, highlightRagdollBodyPart, drawDebugSingleBone,
 * checkWheelCollision.
 */
public final class RagdollScenario implements Scenario {

    @Override
    public String name() {
        return "ragdoll";
    }

    @Override
    public String description() {
        return "zombie ragdolls around a car: every ragdoll native, hits, vehicle contact, live"
                + " script re-upload, debug toggles, network skeleton, add/remove churn";
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        ctx.note("ragdoll scripts");
        RagdollData.uploadScripts(ctx.bullet, false);
        VehicleScripts.Script car = VehicleScripts.carNormal();
        ctx.bullet.defineVehicleScript(car.fullName(), car.toBullet());
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, false);
        Fleet fleet = new Fleet(ctx, sim);
        // the car stands on open ground with a clear run (+x) into the group 10 squares ahead
        float[] at = fleet.clearSpot(px - 10, py, 0, 0, 4, 0, 8, 0, 12, 0);
        px = at[0] + 10;
        py = at[1];
        SimVehicle v = fleet.spawn(car, at[0], at[1], Math.PI / 2);
        fleet.settle(20);

        ctx.note("builder");
        RagdollData.initializeBuilder(ctx);
        RagdollData.setMass(ctx, 70.0F); // unchanged: no native call, like the game
        RagdollData.setMass(ctx, 70.0F + ctx.range(5, 15));

        List<SimRagdoll> ragdolls = new ArrayList<>();
        int baseId = 1000 + ctx.random.nextInt(3000);
        for (int i = 0; i < 6; i++) {
            float x = px + ctx.range(-3, 6);
            float y = py + ctx.range(-2.5F, 2.5F);
            SimRagdoll r = new SimRagdoll(ctx, baseId + i * 7, x, y, 0, ctx.range(0, 6.2831855F));
            // the first one starts with no animation delta (identity velocities)
            r.initialize(i == 0 ? 0.0F : 1.0F / 60.0F);
            ragdolls.add(r);
        }

        ctx.note("simulate");
        float[] rigid = new float[RagdollData.RIGID_BODY_FLOATS];
        float[] skeleton = new float[RagdollData.SKELETON_FLOATS];
        float[] corrected = new float[3];
        float[] force = new float[6];
        boolean detached = false;
        for (int f = 0; f < 480; f++) {
            // the car rolls through the group from frame 120
            if (f >= 120 && f < 300) {
                v.control(v.script.engineForce() * 0.6F, 0, 0);
            } else if (f == 300) {
                v.stop();
            }
            for (SimRagdoll r : ragdolls) {
                if (r.addedToWorld) {
                    r.update();
                }
            }
            if (f % 20 == 5) {
                // CombatManager: a hit on a random zombie's random body part
                SimRagdoll r = ragdolls.get(ctx.random.nextInt(ragdolls.size()));
                if (r.addedToWorld) {
                    int part = ctx.random.nextInt(RagdollData.BODY_PARTS);
                    r.hit(
                            part,
                            ctx.range(-1, 1),
                            ctx.range(-1, 1),
                            ctx.range(10, 80),
                            ctx.range(10, 40));
                }
            }
            vehicleContacts(ctx, v, ragdolls);
            scheduled(ctx, f, ragdolls, rigid, skeleton, corrected, force);
            if (!detached && f == 360) {
                SimRagdoll r = ragdolls.get(1);
                if (r.addedToWorld) {
                    ctx.bullet.detachConstraint(
                            r.id, ctx.random.nextInt(RagdollData.JOINTS)); // dead code in game
                    detached = true;
                }
            }
            fleet.frame(Fleet.substeps(f));
        }

        ctx.note("churn");
        for (int i = 0; i < 4; i++) {
            SimRagdoll old = ragdolls.get(i);
            old.remove();
            fleet.frame(1);
            // the same character ragdolls again later (same id)
            SimRagdoll r =
                    new SimRagdoll(
                            ctx,
                            old.id,
                            old.x + ctx.range(-1, 1),
                            old.y + ctx.range(-1, 1),
                            0,
                            ctx.range(0, 6.2831855F));
            r.initialize(1.0F / 60.0F);
            ragdolls.set(i, r);
            for (int f = 0; f < 30; f++) {
                for (SimRagdoll q : ragdolls) {
                    if (q.addedToWorld) {
                        q.update();
                    }
                }
                fleet.frame(Fleet.substeps(f));
            }
        }

        ctx.note("teardown");
        StringBuilder states = new StringBuilder("ragdoll states");
        for (SimRagdoll r : ragdolls) {
            states.append(' ').append(r.simulationState);
            r.remove();
        }
        ctx.note(states.toString());
        RagdollData.setMass(ctx, 70.0F);
        fleet.noteHeights();
        fleet.removeAll();
        fleet.frame(1);
        sim.destroyWorld();
    }

    /**
     * {@code BaseVehicle} / {@code RagdollController.vehicleCollision}: contact dynamics while the
     * car is within 2 squares of the pelvis, plus the wheel check the library offers for it.
     */
    private static void vehicleContacts(
            ScenarioContext ctx, SimVehicle v, List<SimRagdoll> ragdolls) {
        if (v.state == null || v.removed) {
            return;
        }
        for (SimRagdoll r : ragdolls) {
            if (!r.addedToWorld) {
                continue;
            }
            float dx = v.worldX() - r.x, dy = v.worldY() - r.y;
            boolean near = dx * dx + dy * dy < 4.0F;
            r.vehicleContact(near);
            if (near) {
                ctx.bullet.checkWheelCollision(
                        v.id, r.id, ctx.random.nextInt(RagdollData.BODY_PARTS));
            }
        }
    }

    /** Debug toggles, live re-uploads and the natives without a Java caller, at fixed frames. */
    private static void scheduled(
            ScenarioContext ctx,
            int f,
            List<SimRagdoll> ragdolls,
            float[] rigid,
            float[] skeleton,
            float[] corrected,
            float[] force) {
        SimRagdoll a = ragdolls.get(2);
        SimRagdoll b = ragdolls.get(3);
        switch (f) {
            case 40 -> {
                ctx.bullet.drawDebugRagdollSkeleton(a.id, true, false);
                ctx.bullet.drawDebugRagdollBodyParts(a.id, true, true);
            }
            case 60 -> ctx.bullet.drawDebugSingleBone(a.id, true);
            case 70 -> ctx.bullet.highlightRagdollBodyPart(a.id, 3);
            case 90 -> {
                ctx.bullet.drawDebugRagdollSkeleton(a.id, false, false);
                ctx.bullet.drawDebugRagdollBodyParts(a.id, false, false);
                ctx.bullet.drawDebugSingleBone(a.id, false);
            }
            case 100 -> {
                // RagdollControllerDebugRenderer.updateDebug: simulation paused, then resumed
                a.setActive(false);
            }
            case 130 -> a.setActive(true);
            case 150 -> {
                // live tuning: RagdollScript.resetBodyDynamicsToDefaultValues etc.
                RagdollData.uploadBodyDynamics(ctx.bullet, true, 1.2F);
            }
            case 170 -> {
                RagdollData.uploadConstraints(ctx.bullet, true);
                RagdollData.uploadAnchors(ctx.bullet, true);
                RagdollData.uploadBodyPartInfo(ctx.bullet, true);
            }
            case 190 -> RagdollData.uploadBodyDynamics(ctx.bullet, true, 1.0F);
            case 210 -> {
                int part = ctx.random.nextInt(RagdollData.BODY_PARTS);
                force[0] = ctx.range(-400, 400);
                force[1] = ctx.range(0, 600);
                force[2] = ctx.range(-400, 400);
                force[3] = 0;
                force[4] = 0;
                force[5] = 0;
                ctx.bullet.applyForce(b.id, part, force);
            }
            case 230 -> ctx.bullet.simulateRagdoll(b.id, skeleton);
            case 250 -> {
                // a remote copy of ragdoll a driven from its rigid-body output
                if (a.addedToWorld && b.addedToWorld) {
                    System.arraycopy(a.rigidBodyBuffer, 0, rigid, 0, rigid.length);
                    ctx.bullet.updateSkeletonFromNetworkPhysics(b.id, rigid, skeleton);
                }
            }
            case 270 -> ctx.bullet.getCorrectedWorldSpace(b.id, corrected);
            case 290 -> ctx.bullet.resetSkeletonPose(b.id);
            case 400 -> ctx.bullet.highlightRagdollBodyPart(a.id, RagdollData.BODY_PARTS);
            default -> {}
        }
    }
}
