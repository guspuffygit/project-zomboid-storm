// Port of btTriangleInfoMap.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btHashInt;
import io.pzstorm.storm.bullet.linearmath.btHashMap;
import io.pzstorm.storm.bullet.linearmath.btScalar;

/**
 * {@code struct btTriangleInfoMap : public btHashMap<btHashInt,btTriangleInfo>}. Only the
 * destructor and the (never called) serialize/calculateSerializeBufferSize are linked; nothing in
 * libPZBullet creates one (btGenerateInternalEdgeInfo / btAdjustInternalEdgeContacts are not
 * linked). Ported for the btBvhTriangleMeshShape field type.
 */
public class btTriangleInfoMap extends btHashMap<btHashInt, btTriangleInfo> {
    /// used to determine if an edge or contact normal is convex, using the dot product
    public double m_convexEpsilon;
    /// used to determine if a triangle edge is planar with zero angle
    public double m_planarEpsilon;
    /// used to compute connectivity
    public double m_equalVertexThreshold;
    /// used to determine edge contacts
    public double m_edgeDistanceThreshold;
    // ignore edges that connect triangles at an angle larger than this m_maxEdgeAngleThreshold
    public double m_maxEdgeAngleThreshold;
    /// used to determine if a triangle is degenerate
    public double m_zeroAreaThreshold;

    public btTriangleInfoMap() {
        super(btTriangleInfo::new, btTriangleInfo::assign);
        m_convexEpsilon = (double) 0.00f;
        m_planarEpsilon = (double) 0.0001f;
        m_equalVertexThreshold = 0.0001 * 0.0001;
        m_edgeDistanceThreshold = 0.1;
        m_zeroAreaThreshold = 0.0001 * 0.0001;
        m_maxEdgeAngleThreshold = btScalar.SIMD_2_PI;
    }
}
