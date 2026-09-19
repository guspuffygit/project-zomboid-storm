// Port of btConvexPolyhedron.h struct btFace (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btIntArray;

public class btFace {
    public final btIntArray m_indices = new btIntArray();
    public final double[] m_plane = new double[4];

    public btFace() {}

    /** Implicit copy-assignment (btAlignedObjectArray operator= is copyFromArray). */
    public btFace assign(btFace o) {
        m_indices.copyFromArray(o.m_indices);
        m_plane[0] = o.m_plane[0];
        m_plane[1] = o.m_plane[1];
        m_plane[2] = o.m_plane[2];
        m_plane[3] = o.m_plane[3];
        return this;
    }
}
