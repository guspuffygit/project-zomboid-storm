package io.pzstorm.storm.bullet.trace.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * A player aiming a firearm at a group of walking zombies: the ballistics controller (muzzle,
 * reticle, aim direction, range, target / spread / camera queries) and the ballistics targets (add,
 * axis, transform, skeleton, remove), plus the natives without a Java caller.
 *
 * <p>Game callers: {@code BallisticsController.update → updateBallistics(id, x, z*2.44949, y),
 * updateBallisticsAimReticlePosition(id, isoX, 0, isoY), updateBallisticsMuzzleAimDirection}; first
 * update in debug: {@code setBallisticsSize(0.025), setBallisticsColor(0, 0, 1)}; {@code initialize
 * → updateBallisticsAimReticleQuaternion(rotationYXZ(PI/4, -PI/6, 0))}; {@code setRange →
 * setBallisticsRange}; {@code CombatManager:2145-2154 → getCameraTargets(range + 0.5, true) (10 × 5
 * floats), getSpreadData(range, spread, weightCenter, count) (9 × 4 floats), getTargets(range) (20
 * × 4 floats)}; {@code releaseController → removeBallistics}; {@code BallisticsTarget.addToWorld →
 * addBallisticsTarget}; {@code add / update → setBallisticsTargetAxis(-PI/2, -PI/2 + animAngle,
 * PI), updateBallisticsTarget(id, x, z*2.44949, y, 0, sin(f/2), 0, cos(f/2), false)}; {@code
 * updateSkeleton → updateBallisticsTargetSkeleton(id, numberOfBones, float[245])}; {@code
 * removeFromWorld → removeBallisticsTarget}; {@code PhysicsDebugRenderer →
 * setBallisticsTargetAllPartsColor(white)}; {@code RagdollBuilder.Initialize →
 * setBallisticsTargetAdjustingShapeScale}. No Java caller: getTargetedBodyPart (the controller's
 * method of that name reads the camera-target buffer), updateBallisticsAimReticleRotation,
 * updateBallisticsAimReticleRotate.
 */
public final class BallisticsScenario implements Scenario {

    /** {@code BallisticsTarget.numberOfBones}: assumed to be the 35 skeleton bones. */
    static final int TARGET_BONES = RagdollData.BONES;

    @Override
    public String name() {
        return "ballistics";
    }

    @Override
    public String description() {
        return "a player aims and fires at walking zombies: every ballistics controller and"
                + " target native, spread/camera/target queries, target churn";
    }

    private static final class Target {
        final int id;
        float x;
        float y;
        float forward;
        float animAngle;
        boolean added;
        float phase;

        Target(int id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    @Override
    public void run(ScenarioContext ctx) {
        GameSim sim = new GameSim(ctx);
        sim.boot();
        RagdollData.uploadScripts(ctx.bullet, false);
        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 13, false);
        RagdollData.initializeBuilder(ctx);
        ctx.bullet.setBallisticsTargetAllPartsColor(1.0F, 1.0F, 1.0F);

        int shooter = ctx.random.nextInt(8); // IsoPlayer ids are small
        List<Target> targets = new ArrayList<>();
        int zid = 100 + ctx.random.nextInt(4000);
        for (int i = 0; i < 8; i++) {
            double a = ctx.random.nextDouble() * Math.PI * 2;
            float d = ctx.range(3, 14);
            targets.add(
                    new Target(
                            zid + i * 3,
                            px + (float) Math.cos(a) * d,
                            py + (float) Math.sin(a) * d));
        }

        float[] targetBuf = new float[80];
        float[] spreadBuf = new float[36];
        float[] cameraBuf = new float[50];
        float[] skeleton = new float[245];
        boolean controllerInit = false;
        double aimYaw = ctx.random.nextDouble() * Math.PI * 2;
        float range = ctx.range(12, 20);
        ctx.note("aim");
        for (int f = 0; f < 420; f++) {
            float t = f / 60.0F;
            // targets: shamble toward the player
            for (Target z : targets) {
                if (!z.added && f >= (z.id % 5) * 10) {
                    addTarget(ctx, z, skeleton);
                }
                if (z.added) {
                    float dx = px - z.x, dy = py - z.y;
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    if (len > 1.2F) {
                        z.x += dx / len * 0.012F;
                        z.y += dy / len * 0.012F;
                    }
                    z.forward = (float) Math.atan2(dy, dx);
                    z.animAngle = 0.2F * (float) Math.sin(t * 3 + z.phase);
                    z.phase += 0.05F;
                    updateTarget(ctx, z, skeleton);
                }
            }
            // controller: the aim sweeps across the group
            aimYaw += 0.01 * Math.sin(t * 0.7);
            float cos = (float) Math.cos(aimYaw), sin = (float) Math.sin(aimYaw);
            float mx = px + cos * 0.45F, my = py + sin * 0.45F, mz = 0.55F;
            ctx.bullet.updateBallistics(shooter, mx, mz * 2.44949F, my);
            float reticleX = px + cos * range * 0.6F, reticleY = py + sin * range * 0.6F;
            ctx.bullet.updateBallisticsAimReticlePosition(shooter, reticleX, 0.0F, reticleY);
            float dirY = -0.02F * 2.44949F;
            float n = (float) Math.sqrt(cos * cos + dirY * dirY + sin * sin);
            ctx.bullet.updateBallisticsMuzzleAimDirection(shooter, cos / n, dirY / n, sin / n);
            if (!controllerInit) {
                ctx.bullet.setBallisticsSize(shooter, 0.025F);
                ctx.bullet.setBallisticsColor(shooter, 0.0F, 0.0F, 1.0F);
                float[] q = cameraRotation();
                ctx.bullet.updateBallisticsAimReticleQuaternion(shooter, q[0], q[1], q[2], q[3]);
                controllerInit = true;
            }
            ctx.bullet.setBallisticsRange(shooter, range);
            // CombatManager: every aiming frame the camera targets, then spread or single
            boolean shotgun = (f / 60) % 2 == 1;
            int cams =
                    ctx.bullet.getBallisticsCameraTargets(
                            shooter, range + 0.5F, 10, true, cameraBuf);
            if (shotgun) {
                ctx.bullet.getBallisticsTargetsSpreadData(
                        shooter,
                        range,
                        ctx.range(0.05F, 0.3F),
                        ctx.range(0.2F, 1.0F),
                        6,
                        9,
                        spreadBuf);
            } else {
                ctx.bullet.getBallisticsTargets(shooter, range, 20, targetBuf);
            }
            if (cams > 0 && f % 15 == 0) {
                ctx.bullet.getTargetedBodyPart(shooter);
                ctx.bullet.getTargetedBodyPart((int) cameraBuf[0]);
            }
            if (f % 30 == 0) {
                ctx.bullet.getBallisticsCameraTargets(shooter, range, 10, false, cameraBuf);
            }
            scheduled(ctx, f, shooter, targets, skeleton);
            sim.frame(Fleet.substeps(f));
        }

        ctx.note("teardown");
        for (Target z : targets) {
            removeTarget(ctx, z);
        }
        ctx.bullet.removeBallistics(shooter);
        ctx.bullet.removeBallistics(shooter); // released twice: second is a no-op lookup
        sim.frame(1);
        sim.destroyWorld();
    }

    /** joml {@code rotationYXZ(PI/4, -PI/6, 0)} as xyzw. */
    static float[] cameraRotation() {
        double ay = Math.PI / 4, ax = -Math.PI / 6;
        float sx = (float) Math.sin(ax * 0.5), cx = (float) Math.cos(ax * 0.5);
        float sy = (float) Math.sin(ay * 0.5), cy = (float) Math.cos(ay * 0.5);
        return new float[] {cy * sx, sy * cx, -sy * sx, cy * cx};
    }

    /** {@code BallisticsTarget.add}: addToWorld, axis, transform, skeleton. */
    private static void addTarget(ScenarioContext ctx, Target z, float[] skeleton) {
        ctx.bullet.addBallisticsTarget(z.id);
        z.added = true;
        updateTarget(ctx, z, skeleton);
    }

    /** {@code BallisticsTarget.update} (initialised, not releasing). */
    private static void updateTarget(ScenarioContext ctx, Target z, float[] skeleton) {
        ctx.bullet.setBallisticsTargetAxis(
                z.id,
                (float) (-Math.PI / 2),
                (float) (-Math.PI / 2) + z.animAngle,
                (float) Math.PI);
        ctx.bullet.updateBallisticsTarget(
                z.id,
                z.x,
                0.0F * 2.44949F,
                z.y,
                0.0F,
                (float) Math.sin(z.forward * 0.5F),
                0.0F,
                (float) Math.cos(z.forward * 0.5F),
                false);
        float[] pose = RagdollData.animatedPose(z.phase, 0.5F);
        System.arraycopy(pose, 0, skeleton, 0, pose.length);
        ctx.bullet.updateBallisticsTargetSkeleton(z.id, TARGET_BONES, skeleton);
    }

    private static void removeTarget(ScenarioContext ctx, Target z) {
        if (z.added) {
            ctx.bullet.removeBallisticsTarget(z.id);
            z.added = false;
        }
    }

    private static void scheduled(
            ScenarioContext ctx, int f, int shooter, List<Target> targets, float[] skeleton) {
        switch (f) {
            case 90 -> {
                // no Java caller: reticle orientation as Euler angles / axis-angle
                ctx.bullet.updateBallisticsAimReticleRotation(
                        shooter, ctx.range(-0.5F, 0.5F), ctx.range(0, 6.28F), 0.0F, 1.0F);
                ctx.bullet.updateBallisticsAimReticleRotate(
                        shooter, 0.0F, 1.0F, 0.0F, ctx.range(-0.3F, 0.3F));
            }
            case 150, 300 -> {
                // a target dies and is released, another zombie reuses the pooled id later
                Target z = targets.get(ctx.random.nextInt(targets.size()));
                removeTarget(ctx, z);
            }
            case 200 -> {
                float[] q = cameraRotation();
                ctx.bullet.updateBallisticsAimReticleQuaternion(shooter, q[0], q[1], q[2], q[3]);
            }
            case 240, 360 -> {
                for (Target z : targets) {
                    if (!z.added) {
                        addTarget(ctx, z, skeleton);
                    }
                }
            }
            case 330 -> ctx.bullet.removeBallisticsTarget(60000); // id never added
            default -> {}
        }
    }
}
