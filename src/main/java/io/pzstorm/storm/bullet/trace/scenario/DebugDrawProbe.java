package io.pzstorm.storm.bullet.trace.scenario;

import io.pzstorm.storm.bullet.trace.BackendSession;
import java.util.List;
import java.util.function.Consumer;

/**
 * Drives the {@code PhysicsDebugRenderer} natives of the GL library over a small drive session and
 * logs every draw upcall. Run against the native {@code libPZBullet64.so} by {@code
 * GlDebugDrawMain} (bulletHarness; Docker, see docs/re-bullet/pz-gl.md) to produce {@code
 * pz-gl-debugdraw.txt.gz}, and against the Java port by {@code
 * io.pzstorm.storm.bullet.pz.DebugDrawGroundTruthTest}; the two logs must be equal. JDK + trace
 * package only, so it also compiles into the Docker driver.
 */
public final class DebugDrawProbe {

    /**
     * The five instance natives the game class declares (renderRagdollByID is not declared, so
     * never bound).
     */
    public interface Renderer {
        void n_debugDrawWorld(int ddwX, int ddwY, int minLevel, int maxLevel);

        void renderVehicle(int id, int x, int y);

        void renderRagdoll(int id, int x, int y);

        void renderBallistics(int id, int x, int y);

        void renderBallisticsTarget(int id, int x, int y);
    }

    private DebugDrawProbe() {}

    /** Upcall line format shared by the native stub and the Java capture sink. */
    public static String line(String name, Object... args) {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            if (args[i] instanceof Float fl) {
                sb.append(Integer.toHexString(Float.floatToRawIntBits(fl)));
            } else {
                sb.append(args[i]);
            }
        }
        return sb.append(')').toString();
    }

    private static void call(Consumer<String> out, String what, Runnable r) {
        out.accept("> " + what);
        try {
            r.run();
        } catch (RuntimeException e) {
            out.accept("! " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public static void run(BackendSession session, long seed, Renderer r, Consumer<String> out) {
        ScenarioContext ctx = new ScenarioContext(session.bullet(), seed, null);
        session.installUpcalls(ctx.gameUpcalls());
        GameSim sim = new GameSim(ctx);
        sim.boot();

        // gDynamicsWorld == NULL: every declared entry throws RuntimeException before init
        call(out, "debugDrawWorld no world", () -> r.n_debugDrawWorld(0, 0, 0, 7));
        call(out, "renderVehicle no world", () -> r.renderVehicle(1, 0, 0));
        call(out, "renderRagdoll no world", () -> r.renderRagdoll(1, 0, 0));
        call(out, "renderBallistics no world", () -> r.renderBallistics(1, 0, 0));
        call(out, "renderBallisticsTarget no world", () -> r.renderBallisticsTarget(1, 0, 0));

        float px = 10620.5F + ctx.range(-40, 40);
        float py = 9860.5F + ctx.range(-40, 40);
        sim.createWorld(0, 0, 66, 63, px, py, 3, false);
        VehicleScripts.Script car = VehicleScripts.carNormal();
        ctx.bullet.defineVehicleScript(car.fullName(), car.toBullet());
        int id = 7;
        double yaw = ctx.random.nextDouble() * Math.PI * 2;
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
        for (int i = 0; i < 20; i++) {
            sim.frame(2);
        }
        for (int f = 0; f < 90; f++) {
            ctx.bullet.controlVehicle(id, car.engineForce(), 0, 0.2F);
            List<GameSim.VehicleState> v = sim.frame(1);
            if (!v.isEmpty()) {
                sim.follow(v.get(0).x() + sim.offsetX, v.get(0).z() + sim.offsetY);
            }
        }
        // one ballistics controller (PZBallistics::render draws unconditionally)
        int shooter = 3;
        ctx.bullet.updateBallistics(shooter, px + 0.45F, 0.55F * 2.44949F, py);
        ctx.bullet.updateBallisticsAimReticlePosition(shooter, px + 6.0F, 0.0F, py + 2.0F);
        ctx.bullet.updateBallisticsMuzzleAimDirection(shooter, 0.94F, -0.05F, 0.33F);
        ctx.bullet.setBallisticsSize(shooter, 0.025F);
        ctx.bullet.setBallisticsColor(shooter, 0.0F, 0.0F, 1.0F);
        // camera 45 deg yaw, -30 deg pitch (BallisticsScenario.cameraRotation)
        ctx.bullet.updateBallisticsAimReticleQuaternion(
                shooter, -0.23911762F, 0.36964381F, 0.09904576F, 0.8923991F);
        ctx.bullet.setBallisticsRange(shooter, 14.0F);
        ctx.bullet.getBallisticsCameraTargets(shooter, 14.5F, 10, true, new float[50]);
        sim.frame(1);
        List<GameSim.VehicleState> v = sim.readVehicles();
        for (GameSim.VehicleState s : v) {
            out.accept(
                    line("vehicle", s.id(), s.x(), s.y(), s.z(), s.qx(), s.qy(), s.qz(), s.qw()));
        }
        int ox = -(int) sim.offsetX;
        int oy = -(int) sim.offsetY;
        int[][] levels = {{0, 0}, {0, 7}, {1, 7}, {-1, -1}, {-32, 31}, {5, 0}};
        for (int[] lv : levels) {
            call(
                    out,
                    "debugDrawWorld " + lv[0] + " " + lv[1],
                    () -> r.n_debugDrawWorld(ox, oy, lv[0], lv[1]));
        }
        call(out, "renderVehicle", () -> r.renderVehicle(id, 3, -5));
        call(out, "renderVehicle missing", () -> r.renderVehicle(id + 1, 3, -5));
        call(out, "renderRagdoll missing", () -> r.renderRagdoll(1, 3, -5));
        call(out, "renderBallistics missing", () -> r.renderBallistics(1, 3, -5));
        call(out, "renderBallistics", () -> r.renderBallistics(shooter, 3, -5));
        call(out, "renderBallistics 2", () -> r.renderBallistics(shooter, -100000, 100000));
        call(out, "renderBallisticsTarget missing", () -> r.renderBallisticsTarget(1, 3, -5));
        // renderVehicle again after the offset changed: the drawer offset is per-entry
        call(out, "renderVehicle 2", () -> r.renderVehicle(id, -100000, 100000));
        realTargets(ctx, sim, r, out, v.get(0), shooter, ox, oy);
        out.accept("> end");
    }

    /** Aims a ray from 10 squares west of (x, y) at height h toward (x, h2, y): the muzzle. */
    private static void aim(
            ScenarioContext ctx,
            int shooter,
            float fx,
            float fh,
            float fy,
            float tx,
            float th,
            float ty) {
        float dx = tx - fx, dh = th - fh, dy = ty - fy;
        float n = (float) Math.sqrt(dx * dx + dh * dh + dy * dy);
        ctx.bullet.updateBallistics(shooter, fx, fh, fy);
        ctx.bullet.updateBallisticsMuzzleAimDirection(shooter, dx / n, dh / n, dy / n);
    }

    private static void floats(Consumer<String> out, String name, int n, float[] buf, int per) {
        Object[] a = new Object[1 + Math.max(0, Math.min(n * per, buf.length))];
        a[0] = n;
        for (int i = 1; i < a.length; i++) {
            a[i] = buf[i - 1];
        }
        out.accept(line(name, a));
    }

    /**
     * Second phase: a real ragdoll, a ballistics target, a second car joined by point and hinge
     * constraints, and ballistics queries that highlight vehicle parts, ragdoll parts and target
     * parts, so every render body and the constraint drawer produce output.
     */
    private static void realTargets(
            ScenarioContext ctx,
            GameSim sim,
            Renderer r,
            Consumer<String> out,
            GameSim.VehicleState car,
            int shooter,
            int ox,
            int oy) {
        out.accept("> phase 2");
        RagdollData.uploadScripts(ctx.bullet, false);
        RagdollData.initializeBuilder(ctx);
        ctx.bullet.setBallisticsTargetAllPartsColor(1.0F, 1.0F, 1.0F);
        float cx = car.x() + sim.offsetX;
        float cy = car.z() + sim.offsetY;
        float ch = car.y();

        // a second car 7 squares away, tied to the first by a point and a hinge constraint
        VehicleScripts.Script script = VehicleScripts.carNormal();
        int car2 = 9;
        ctx.bullet.addVehicle(
                car2,
                cx + 5.0F,
                cy + 5.0F,
                GameSim.physicsZ(script, 0),
                0.0F,
                0.38268343F,
                0.0F,
                0.9238795F,
                script.fullName());
        ctx.bullet.setVehicleActive(car2, true);
        int point = ctx.bullet.addPointConstraint(car.id(), car2, 0, 0.3F, -2.4F, 0, 0.3F, 2.4F);
        int hinge = ctx.bullet.addHingeConstraint(car.id(), car2, 0, 0.5F, -2.4F, 0, 0.5F, 2.4F);
        out.accept(line("constraints", point, hinge));

        // a ragdoll (RagdollController.initialize) and a ballistics target (BallisticsTarget.add)
        SimRagdoll rag = new SimRagdoll(ctx, 21, cx - 4.0F, cy + 4.0F, 0, 1.0F);
        rag.initialize(1.0F / 60.0F);
        int target = 31;
        float tx = cx - 4.0F, ty = cy - 4.0F;
        ctx.bullet.addBallisticsTarget(target);
        float[] skeleton = RagdollData.animatedPose(0.3F, 0.5F);
        for (int f = 0; f < 40; f++) {
            ctx.bullet.setBallisticsTargetAxis(
                    target, (float) (-Math.PI / 2), (float) (-Math.PI / 2), (float) Math.PI);
            ctx.bullet.updateBallisticsTarget(
                    target, tx, 0.0F, ty, 0.0F, 0.38268343F, 0.0F, 0.9238795F, false);
            ctx.bullet.updateBallisticsTargetSkeleton(target, RagdollData.BONES, skeleton);
            rag.update();
            sim.frame(1);
        }
        out.accept(line("ragdoll", rag.simulationState, rag.lastBoneCount));
        float[] pelvis = rag.pelvis();
        out.accept(line("pelvis", pelvis[0], pelvis[1], pelvis[2]));
        for (GameSim.VehicleState s : sim.readVehicles()) {
            out.accept(
                    line("vehicle", s.id(), s.x(), s.y(), s.z(), s.qx(), s.qy(), s.qz(), s.qw()));
        }
        float[] buf = new float[80];
        float[] cam = new float[50];
        float[] spread = new float[40];

        // the world with bodies, ragdoll joints and the two car constraints
        int[][] levels = {{0, 0}, {-32, 31}};
        for (int[] lv : levels) {
            call(
                    out,
                    "phase2 debugDrawWorld " + lv[0] + " " + lv[1],
                    () -> r.n_debugDrawWorld(ox, oy, lv[0], lv[1]));
        }

        // highlight vehicle parts: a compound ray through the first car's chassis
        cx = sim.readVehicles().get(0).x() + sim.offsetX;
        cy = sim.readVehicles().get(0).z() + sim.offsetY;
        ch = sim.readVehicles().get(0).y();
        aim(ctx, shooter, cx - 10.0F, ch + 0.3F, cy + 0.1F, cx, ch + 0.3F, cy);
        floats(
                out,
                "targets car",
                ctx.bullet.getBallisticsTargets(shooter, 30.0F, 20, buf),
                buf,
                4);
        call(out, "renderVehicle highlighted", () -> r.renderVehicle(car.id(), 3, -5));
        call(out, "renderVehicle highlighted again", () -> r.renderVehicle(car.id(), 3, -5));
        ctx.bullet.setBallisticsColor(shooter, 1.0F, 0.5F, 0.0F);
        floats(
                out,
                "spread car",
                ctx.bullet.getBallisticsTargetsSpreadData(shooter, 30.0F, 0.3F, 0.5F, 8, 8, spread),
                spread,
                4);
        call(out, "renderVehicle spread", () -> r.renderVehicle(car.id(), 0, 0));

        // the ragdoll: its bodies carry no BulletObject, so ballistics rays never flag them; the
        // body-part, highlighted-part and skeleton drawers are driven by the debug toggles
        float px = pelvis[0], ph = pelvis[1], pz = pelvis[2];
        aim(ctx, shooter, px - 6.0F, ph + 0.2F, pz, px, ph, pz);
        int ragHits = ctx.bullet.getBallisticsTargets(shooter, 30.0F, 20, buf);
        floats(out, "targets ragdoll", ragHits, buf, 4);
        call(out, "renderRagdoll plain", () -> r.renderRagdoll(rag.id, 3, -5));
        ctx.bullet.drawDebugRagdollBodyParts(rag.id, true, false);
        call(out, "renderRagdoll bodyParts", () -> r.renderRagdoll(rag.id, 3, -5));
        ctx.bullet.highlightRagdollBodyPart(rag.id, 3);
        ctx.bullet.drawDebugRagdollBodyParts(rag.id, true, true);
        call(out, "renderRagdoll highlighted", () -> r.renderRagdoll(rag.id, 3, -5));
        ctx.bullet.drawDebugRagdollBodyParts(rag.id, false, false);
        ctx.bullet.drawDebugRagdollSkeleton(rag.id, true, false);
        call(out, "renderRagdoll skeleton", () -> r.renderRagdoll(rag.id, -100000, 100000));
        ctx.bullet.drawDebugRagdollSkeleton(rag.id, true, true);
        ctx.bullet.drawDebugSingleBone(rag.id, true);
        call(out, "renderRagdoll singleBone", () -> r.renderRagdoll(rag.id, 3, -5));
        ctx.bullet.drawDebugSingleBone(rag.id, false);
        ctx.bullet.drawDebugRagdollSkeleton(rag.id, true, false);
        ctx.bullet.drawDebugRagdollBodyParts(rag.id, true, false);
        call(out, "renderRagdoll all", () -> r.renderRagdoll(rag.id, 3, -5));
        ctx.bullet.highlightRagdollBodyPart(rag.id, RagdollData.BODY_PARTS);
        ctx.bullet.drawDebugRagdollBodyParts(rag.id, true, true);
        call(out, "renderRagdoll no part", () -> r.renderRagdoll(rag.id, 3, -5));
        call(out, "phase2 debugDrawWorld ragdoll", () -> r.n_debugDrawWorld(ox, oy, -32, 31));

        // the ballistics target: the synthetic pose lies flat, so its targeting box is centred
        // about 1.6 squares +x of the target position and 0.22 high; the camera reticle and the
        // muzzle ray go through (tx + 1.6, 0.1, ty + 0.1)
        call(
                out,
                "renderBallisticsTarget unflagged",
                () -> r.renderBallisticsTarget(target, 3, -5));
        ctx.bullet.setBallisticsColor(shooter, 0.0F, 1.0F, 0.0F);
        ctx.bullet.updateBallisticsAimReticlePosition(shooter, tx + 1.6F, 0.1F, ty + 0.1F);
        aim(ctx, shooter, tx - 6.0F, 0.1F, ty + 0.1F, tx + 1.6F, 0.1F, ty + 0.1F);
        int camHits = ctx.bullet.getBallisticsCameraTargets(shooter, 30.0F, 10, true, cam);
        floats(out, "camera target", camHits, cam, 5);
        out.accept(line("targeted part", ctx.bullet.getTargetedBodyPart(target)));
        call(out, "renderBallisticsTarget camera", () -> r.renderBallisticsTarget(target, 3, -5));
        ctx.bullet.setBallisticsColor(shooter, 1.0F, 0.0F, 1.0F);
        int tgtHits = ctx.bullet.getBallisticsTargets(shooter, 30.0F, 20, buf);
        floats(out, "targets target", tgtHits, buf, 4);
        call(out, "renderBallisticsTarget", () -> r.renderBallisticsTarget(target, 3, -5));
        call(out, "renderBallisticsTarget again", () -> r.renderBallisticsTarget(target, 0, 0));
        call(out, "renderBallistics phase2", () -> r.renderBallistics(shooter, 3, -5));

        // a few more steps with everything in contact, then the full world again
        for (int f = 0; f < 10; f++) {
            rag.update();
            sim.frame(1);
        }
        call(out, "phase2 debugDrawWorld final", () -> r.n_debugDrawWorld(ox, oy, -32, 31));
        ctx.bullet.removeConstraint(hinge);
        ctx.bullet.removeConstraint(point);
        call(out, "phase2 debugDrawWorld unconstrained", () -> r.n_debugDrawWorld(ox, oy, 0, 0));
    }
}
