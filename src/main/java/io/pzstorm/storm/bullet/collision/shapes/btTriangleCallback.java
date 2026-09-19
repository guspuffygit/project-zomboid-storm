// Port of btTriangleCallback.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btTriangleCallback {
    /**
     * {@code processTriangle(btVector3* triangle, int partId, int triangleIndex)}; triangle[0..2].
     */
    public abstract void processTriangle(btVector3[] triangle, int partId, int triangleIndex);

    /** Virtual destructor {@code ~btTriangleCallback()} (empty). */
    public void destroy() {}
}
