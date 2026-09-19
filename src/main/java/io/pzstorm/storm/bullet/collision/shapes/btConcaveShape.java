// Port of btConcaveShape.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/** enum PHY_ScalarType lives in {@link PHY_ScalarType}. */
public abstract class btConcaveShape extends btCollisionShape {
    public double m_collisionMargin;

    public btConcaveShape() {
        m_collisionMargin = 0.0;
    }

    public abstract void processAllTriangles(
            btTriangleCallback callback, btVector3 aabbMin, btVector3 aabbMax);

    @Override
    public double getMargin() {
        return m_collisionMargin;
    }

    @Override
    public void setMargin(double collisionMargin) {
        m_collisionMargin = collisionMargin;
    }
}
