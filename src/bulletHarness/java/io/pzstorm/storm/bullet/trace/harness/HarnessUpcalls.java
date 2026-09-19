package io.pzstorm.storm.bullet.trace.harness;

import io.pzstorm.storm.bullet.trace.SkeletonBoneTable;
import io.pzstorm.storm.bullet.trace.UpcallHandler;

/** Where the stub game classes send the library's upcalls. */
public final class HarnessUpcalls {

    /** Game-equivalent answers, used until a scenario or replayer installs its own handler. */
    public static final UpcallHandler DEFAULT =
            new UpcallHandler() {
                @Override
                public boolean updatePhysicsForLevelIfNeeded(int wx, int wy, int level) {
                    return false;
                }

                @Override
                public void onVehicleConstraintImpulse(int c, int a, int b, float impulse) {}

                @Override
                public void nativeLog(String logType, String logSeverity, String logText) {}

                @Override
                public String getBoneName(int ordinal) {
                    return SkeletonBoneTable.getBoneName(ordinal);
                }

                @Override
                public int getBoneOrdinal(String name) {
                    return SkeletonBoneTable.getBoneOrdinal(name);
                }
            };

    private static volatile UpcallHandler handler = DEFAULT;

    private HarnessUpcalls() {}

    public static UpcallHandler handler() {
        return handler;
    }

    public static void install(UpcallHandler h) {
        handler = h == null ? DEFAULT : h;
    }
}
