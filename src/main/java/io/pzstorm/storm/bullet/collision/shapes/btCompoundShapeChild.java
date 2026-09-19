// Port of btCompoundShape.h struct btCompoundShapeChild (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.collision.broadphase.btDbvtNode;
import io.pzstorm.storm.bullet.linearmath.btTransform;

public class btCompoundShapeChild {
    public final btTransform m_transform = new btTransform();
    public btCollisionShape m_childShape;
    public int m_childShapeType;
    public double m_childMargin;
    public btDbvtNode m_node;

    /** implicit {@code operator=} */
    public void assign(btCompoundShapeChild o) {
        m_transform.set(o.m_transform);
        m_childShape = o.m_childShape;
        m_childShapeType = o.m_childShapeType;
        m_childMargin = o.m_childMargin;
        m_node = o.m_node;
    }
}
