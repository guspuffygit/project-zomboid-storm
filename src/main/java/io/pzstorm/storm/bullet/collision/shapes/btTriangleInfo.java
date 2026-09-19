// Port of btTriangleInfoMap.h struct btTriangleInfo (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btScalar;

/**
 * The btTriangleInfo structure stores information to adjust collision normals to avoid collisions
 * against internal edges.
 */
public class btTriangleInfo {
    /// for btTriangleInfo m_flags
    public static final int TRI_INFO_V0V1_CONVEX = 1;
    public static final int TRI_INFO_V1V2_CONVEX = 2;
    public static final int TRI_INFO_V2V0_CONVEX = 4;

    public static final int TRI_INFO_V0V1_SWAP_NORMALB = 8;
    public static final int TRI_INFO_V1V2_SWAP_NORMALB = 16;
    public static final int TRI_INFO_V2V0_SWAP_NORMALB = 32;

    public int m_flags;

    public double m_edgeV0V1Angle;
    public double m_edgeV1V2Angle;
    public double m_edgeV2V0Angle;

    public btTriangleInfo() {
        m_edgeV0V1Angle = btScalar.SIMD_2_PI;
        m_edgeV1V2Angle = btScalar.SIMD_2_PI;
        m_edgeV2V0Angle = btScalar.SIMD_2_PI;
        m_flags = 0;
    }

    /** implicit {@code operator=} */
    public void assign(btTriangleInfo o) {
        m_flags = o.m_flags;
        m_edgeV0V1Angle = o.m_edgeV0V1Angle;
        m_edgeV1V2Angle = o.m_edgeV1V2Angle;
        m_edgeV2V0Angle = o.m_edgeV2V0Angle;
    }
}
