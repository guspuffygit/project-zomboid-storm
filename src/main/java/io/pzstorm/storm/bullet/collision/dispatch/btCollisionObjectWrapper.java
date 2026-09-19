// Port of BulletCollision/CollisionDispatch/btCollisionObjectWrapper.h (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.linearmath.btTransform;

/**
 * btCollisionObjectWrapper (stack-only in C++). {@code m_worldTransform} is a C++ reference: the
 * wrapper stores the caller's transform object itself, never a copy.
 */
public class btCollisionObjectWrapper {
    public btCollisionObjectWrapper m_parent;
    public btCollisionShape m_shape;
    public btCollisionObject m_collisionObject;

    /** {@code const btTransform&} -- the referenced object, not a copy. */
    public final btTransform m_worldTransform;

    public int m_partId;
    public int m_index;

    public btCollisionObjectWrapper(
            btCollisionObjectWrapper parent,
            btCollisionShape shape,
            btCollisionObject collisionObject,
            btTransform worldTransform,
            int partId,
            int index) {
        m_parent = parent;
        m_shape = shape;
        m_collisionObject = collisionObject;
        m_worldTransform = worldTransform;
        m_partId = partId;
        m_index = index;
    }

    public btTransform getWorldTransform() {
        return m_worldTransform;
    }

    public btCollisionObject getCollisionObject() {
        return m_collisionObject;
    }

    public btCollisionShape getCollisionShape() {
        return m_shape;
    }
}
