// Port of PZ glue SkeletonBone (SkeletonBone.cpp; SkeletonBone::* @0017ec20..0017f270).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.BulletUpcalls;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code SkeletonBone::Type} is a plain int enum 0..0x22 (35 bones) with 0x23 as the end sentinel.
 * JNI lookups of {@code zombie.core.skinnedmodel.model.SkeletonBone.getBoneName/getBoneOrdinal} go
 * through {@link BulletUpcalls}.
 */
public final class SkeletonBone {

    public static final int COUNT = 0x23;

    private SkeletonBone() {}

    /** SkeletonBone::next @0017ec20: t+1, or 0x23 once t > 0x22. */
    public static int next(int t) {
        int r = t + 1;
        if (Integer.compareUnsigned(0x22, t) < 0) {
            r = 0x23;
        }
        return r;
    }

    /**
     * @0017ec30
     */
    public static int GetBoneIndex(int t) {
        if (t < 0x23) {
            return t;
        }
        return -1;
    }

    /**
     * @0017ec50
     */
    public static int Count() {
        return 0x23;
    }

    /**
     * @0017ec60
     */
    public static int GetOrdinal(int t) {
        return t;
    }

    /**
     * @0017ed00: the Java getBoneName(ordinal) string; "" when the upcall threw (exception path
     * clears and returns an empty string). A null jstring without exception would crash in C; it
     * maps to "" here as well (gap: BulletUpcalls folds both cases into null).
     */
    public static String GetName(int t) {
        int ord = GetOrdinal(t);
        String s = BulletUpcalls.callGetBoneName(ord);
        if (s == null) {
            return "";
        }
        return s;
    }

    /**
     * @0017eed0
     */
    public static int GetJavaOrdinal(int t) {
        String name = GetName(t);
        return BulletUpcalls.callGetBoneOrdinal(name);
    }

    /** SkeletonBone::AllBones: vector&lt;Type&gt; of 0..0x22 built with next(). */
    public static final class AllBones {
        public final List<Integer> m_bones = new ArrayList<>();

        /**
         * @0017efa0
         */
        public AllBones() {
            int t = 0;
            while (true) {
                m_bones.add(t);
                t = next(t);
                if (t == 0x23) {
                    break;
                }
            }
        }

        /**
         * @0017ef90
         */
        public List<Integer> Get() {
            return m_bones;
        }
    }

    private static AllBones s_allBones;

    /**
     * @0017f020 function-local static.
     */
    public static synchronized List<Integer> all() {
        if (s_allBones == null) {
            s_allBones = new AllBones();
        }
        return s_allBones.Get();
    }

    /**
     * @0017f0b0
     */
    public static void ValidateAgainstJava() {
        final String loc = "/usr/src/pz/pzbullet/SkeletonBone.cpp:";
        PZDebugLog.instance().log("PZBullet", PZDebugType.Debug, loc + "91", "Validating...");
        List<Integer> bones = all();
        boolean ok = true;
        for (int i = 0; i < bones.size(); i++) {
            int b = bones.get(i);
            String name = GetName(b);
            int cpp = GetOrdinal(b);
            int java = GetJavaOrdinal(b);
            if (cpp == java) {
                PZDebugLog.instance()
                        .logf("PZBullet", PZDebugType.Debug, loc + "103", "  MATCH> %s", name);
            } else {
                PZDebugLog.instance()
                        .logf(
                                "PZBullet",
                                PZDebugType.Error,
                                loc + "107",
                                "  FAIL > SkeletonBone: %s, CPP ordinal: %d, Java ordinal: %d",
                                name,
                                cpp,
                                java);
            }
            ok = ok & (cpp == java);
        }
        if (!ok) {
            PZDebugLog.instance()
                    .log("PZBullet", PZDebugType.Error, loc + "119", "Validate result: Failed.");
        } else {
            PZDebugLog.instance()
                    .log("PZBullet", PZDebugType.Debug, loc + "115", "Validate result: Success.");
        }
    }
}
