// Port of btTriangleCallback.cpp (Bullet 2.82) class btInternalTriangleIndexCallback
package io.pzstorm.storm.bullet.collision.shapes;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public abstract class btInternalTriangleIndexCallback {
    public abstract void internalProcessTriangleIndex(
            btVector3[] triangle, int partId, int triangleIndex);
}
