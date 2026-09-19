package io.pzstorm.storm.bullet.trace;

import java.util.List;

/**
 * Copy of {@code zombie.core.skinnedmodel.model.SkeletonBone} (PZ 42.20.4) names and the two
 * statics the native calls, so harness code can answer those upcalls without the game jar. {@code
 * SkeletonBoneTableTest} checks it against the real enum.
 */
public final class SkeletonBoneTable {

    public static final List<String> NAMES =
            List.of(
                    "Dummy01",
                    "Bip01",
                    "Bip01_Pelvis",
                    "Bip01_Spine",
                    "Bip01_Spine1",
                    "Bip01_Neck",
                    "Bip01_Head",
                    "Bip01_L_Clavicle",
                    "Bip01_L_UpperArm",
                    "Bip01_L_Forearm",
                    "Bip01_L_Hand",
                    "Bip01_L_Finger0",
                    "Bip01_L_Finger1",
                    "Bip01_R_Clavicle",
                    "Bip01_R_UpperArm",
                    "Bip01_R_Forearm",
                    "Bip01_R_Hand",
                    "Bip01_R_Finger0",
                    "Bip01_R_Finger1",
                    "Bip01_BackPack",
                    "Bip01_L_Thigh",
                    "Bip01_L_Calf",
                    "Bip01_L_Foot",
                    "Bip01_L_Toe0",
                    "Bip01_R_Thigh",
                    "Bip01_R_Calf",
                    "Bip01_R_Foot",
                    "Bip01_R_Toe0",
                    "Bip01_DressFront",
                    "Bip01_DressFront02",
                    "Bip01_DressBack",
                    "Bip01_DressBack02",
                    "Bip01_Prop1",
                    "Bip01_Prop2",
                    "Translation_Data",
                    "BONE_COUNT",
                    "None");

    /** {@code SkeletonBone.count()} == ordinal of BONE_COUNT. */
    public static final int COUNT = NAMES.indexOf("BONE_COUNT");

    private SkeletonBoneTable() {}

    public static String getBoneName(int ordinal) {
        return ordinal >= 0 && ordinal < COUNT
                ? NAMES.get(ordinal)
                : "~IndexOutOfBounds:" + ordinal + "~";
    }

    /** {@code Enum.valueOf} with fallback to {@code None}; a null name also yields None. */
    public static int getBoneOrdinal(String name) {
        int i = name == null ? -1 : NAMES.indexOf(name);
        return i >= 0 ? i : NAMES.indexOf("None");
    }
}
