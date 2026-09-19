package io.pzstorm.storm.bullet.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.model.SkeletonBone;

/** The JDK-only bone table answers bone upcalls exactly as the game's enum does. */
class SkeletonBoneTableTest implements UnitTest {

    @Test
    void matchesTheGameEnum() {
        assertEquals(SkeletonBone.count(), SkeletonBoneTable.COUNT);
        for (int i = -3; i < SkeletonBone.values().length + 3; i++) {
            assertEquals(
                    SkeletonBone.getBoneName(i), SkeletonBoneTable.getBoneName(i), "ordinal " + i);
        }
        for (SkeletonBone b : SkeletonBone.values()) {
            assertEquals(
                    SkeletonBone.getBoneOrdinal(b.name()),
                    SkeletonBoneTable.getBoneOrdinal(b.name()),
                    b.name());
        }
        for (String odd : new String[] {"", "bip01", "Bip01_Nope", "BONE_COUNT", "None"}) {
            assertEquals(
                    SkeletonBone.getBoneOrdinal(odd), SkeletonBoneTable.getBoneOrdinal(odd), odd);
        }
    }
}
