package io.pzstorm.storm.bullet.trace;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;

/**
 * A tiny deterministic stand-in for the native library, with switchable misbehaviours so the
 * replayer's divergence detection can be exercised without the real {@code .so}.
 *
 * <ul>
 *   <li>{@code initPZBullet}: nativeLog, getBoneName(3), getBoneOrdinal("Bip01_Head")
 *   <li>{@code stepSimulation(dt,..)}: state += dt, then upcall
 *       updatePhysicsForLevelIfNeeded(1,2,0)
 *   <li>{@code ToBullet(bb)}: state += first byte
 *   <li>{@code getVehiclePhysics(id, ff)}: ff[0] = state, ff[1] = id, returns 1
 *   <li>{@code isWorldInit}: throws IllegalStateException("no world") until initWorld
 *   <li>{@code defineVehicleScript(name, ff)}: state += name.length() + ff.length
 *   <li>{@code initializeRagdollSkeleton(id, bones)}: bones[i] += 1
 * </ul>
 */
final class FakeBullet {

    enum Quirk {
        /** getVehiclePhysics writes one ULP off. */
        ULP_OFF,
        /** getPZBulletVersion returns another string. */
        OTHER_VERSION,
        /** stepSimulation makes no upcall. */
        NO_UPCALL,
        /** stepSimulation makes an extra nativeLog upcall first. */
        EXTRA_LOG,
        /** stepSimulation asks for level 1 instead of 0. */
        OTHER_LEVEL,
        /** isWorldInit returns false instead of throwing. */
        NO_THROW,
    }

    float state;
    boolean world;
    UpcallHandler handler;
    final Set<Quirk> quirks;
    int toBulletCalls;

    FakeBullet(Quirk... quirks) {
        this.quirks =
                quirks.length == 0 ? EnumSet.noneOf(Quirk.class) : EnumSet.of(quirks[0], quirks);
    }

    BackendSession session() {
        BulletBackend b =
                (BulletBackend)
                        Proxy.newProxyInstance(
                                BulletBackend.class.getClassLoader(),
                                new Class<?>[] {BulletBackend.class},
                                (proxy, m, args) -> {
                                    if (m.getDeclaringClass() == Object.class) {
                                        return RecordingBackend.objectMethod(
                                                proxy, m, args, "FakeBullet");
                                    }
                                    return dispatch(m.getName(), args);
                                });
        return new BackendSession("fake", b, h -> handler = h);
    }

    private Object dispatch(String name, Object[] a) {
        switch (name) {
            case "getPZBulletVersion":
                return quirks.contains(Quirk.OTHER_VERSION) ? "9.9" : "1.0.0.28";
            case "initPZBullet":
                handler.nativeLog("General", "Debug", "fake init é");
                handler.getBoneName(3);
                handler.getBoneOrdinal("Bip01_Head");
                return null;
            case "initWorld":
                world = true;
                return null;
            case "isWorldInit":
                if (!world && !quirks.contains(Quirk.NO_THROW)) {
                    throw new IllegalStateException("no world");
                }
                return world;
            case "stepSimulation":
                state += (Float) a[0];
                if (quirks.contains(Quirk.EXTRA_LOG)) {
                    handler.nativeLog("General", "Debug", "extra");
                }
                if (!quirks.contains(Quirk.NO_UPCALL)) {
                    handler.updatePhysicsForLevelIfNeeded(
                            1, 2, quirks.contains(Quirk.OTHER_LEVEL) ? 1 : 0);
                }
                return null;
            case "ToBullet":
                toBulletCalls++;
                state += ((ByteBuffer) a[0]).get(0);
                return null;
            case "getVehiclePhysics":
                {
                    float[] ff = (float[]) a[1];
                    ff[0] = quirks.contains(Quirk.ULP_OFF) ? Math.nextUp(state) : state;
                    ff[1] = (Integer) a[0];
                    return 1;
                }
            case "defineVehicleScript":
                state += ((String) a[0]).length() + ((float[]) a[1]).length;
                return null;
            case "initializeRagdollSkeleton":
                {
                    int[] bones = (int[]) a[1];
                    for (int i = 0; i < bones.length; i++) {
                        bones[i] += 1;
                    }
                    return null;
                }
            default:
                return defaultFor(name);
        }
    }

    private static Object defaultFor(String name) {
        for (Sig s : BulletApi.NATIVES) {
            if (s.name.equals(name)) {
                return switch (s.ret) {
                    case BOOLEAN -> false;
                    case INT -> 0;
                    case FLOAT -> 0f;
                    default -> null;
                };
            }
        }
        return null;
    }

    /**
     * The "game": answers upcalls; updatePhysicsForLevelIfNeeded issues a nested ToBullet through
     * {@code bullet} (as the real game does from inside the upcall).
     */
    static UpcallHandler game(BulletBackend bullet) {
        return new UpcallHandler() {
            final ByteBuffer cmd = ByteBuffer.allocateDirect(64);

            @Override
            public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                cmd.clear();
                cmd.put(0, (byte) 5);
                bullet.ToBullet(cmd);
                return true;
            }

            @Override
            public void onVehicleConstraintImpulse(int c, int a, int b, float impulse) {}

            @Override
            public void nativeLog(String t, String s, String text) {}

            @Override
            public String getBoneName(int ordinal) {
                return SkeletonBoneTable.getBoneName(ordinal);
            }

            @Override
            public int getBoneOrdinal(String name) {
                return SkeletonBoneTable.getBoneOrdinal(name);
            }
        };
    }

    /** A standard little session: init, a failing isWorldInit, world, steps, readbacks. */
    static void drive(BackendSession s) {
        BulletBackend b = s.bullet();
        s.installUpcalls(game(b));
        b.getPZBulletVersion();
        b.initPZBullet();
        try {
            b.isWorldInit();
        } catch (IllegalStateException expected) {
            // recorded as an exception outcome
        }
        b.initWorld(0, 0, 10, 10, 0, 0, false);
        b.defineVehicleScript("Base.CarNormal", new float[] {1f, -0f, Float.NaN});
        int[] bones = {1, 2, 3};
        b.initializeRagdollSkeleton(7, bones);
        float[] ff = new float[16];
        for (int i = 0; i < 5; i++) {
            b.stepSimulation(0.01f, 0, 0f);
            b.getVehiclePhysics(i, ff);
        }
    }
}
