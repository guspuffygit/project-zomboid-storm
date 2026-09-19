package zombie.core.skinnedmodel.model;

import io.pzstorm.storm.bullet.trace.harness.HarnessUpcalls;

/**
 * HARNESS STUB mirroring the game enum (same constants, same order) with the two statics the
 * physics library calls (SkeletonBone::GetName / GetJavaOrdinal), routed to {@link HarnessUpcalls}.
 */
public enum SkeletonBone {
    Dummy01,
    Bip01,
    Bip01_Pelvis,
    Bip01_Spine,
    Bip01_Spine1,
    Bip01_Neck,
    Bip01_Head,
    Bip01_L_Clavicle,
    Bip01_L_UpperArm,
    Bip01_L_Forearm,
    Bip01_L_Hand,
    Bip01_L_Finger0,
    Bip01_L_Finger1,
    Bip01_R_Clavicle,
    Bip01_R_UpperArm,
    Bip01_R_Forearm,
    Bip01_R_Hand,
    Bip01_R_Finger0,
    Bip01_R_Finger1,
    Bip01_BackPack,
    Bip01_L_Thigh,
    Bip01_L_Calf,
    Bip01_L_Foot,
    Bip01_L_Toe0,
    Bip01_R_Thigh,
    Bip01_R_Calf,
    Bip01_R_Foot,
    Bip01_R_Toe0,
    Bip01_DressFront,
    Bip01_DressFront02,
    Bip01_DressBack,
    Bip01_DressBack02,
    Bip01_Prop1,
    Bip01_Prop2,
    Translation_Data,
    BONE_COUNT,
    None;

    public static int count() {
        return BONE_COUNT.ordinal();
    }

    public static String getBoneName(int enumOrdinal) {
        return HarnessUpcalls.handler().getBoneName(enumOrdinal);
    }

    public static int getBoneOrdinal(String boneName) {
        return HarnessUpcalls.handler().getBoneOrdinal(boneName);
    }
}
