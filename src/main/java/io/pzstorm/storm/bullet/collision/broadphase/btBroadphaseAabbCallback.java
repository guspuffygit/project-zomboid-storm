// Port of BulletCollision/BroadphaseCollision/btBroadphaseInterface.h (Bullet 2.82), struct
// btBroadphaseAabbCallback (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

public abstract class btBroadphaseAabbCallback {
    /** {@code virtual ~btBroadphaseAabbCallback() {}} */
    public void destroy() {}

    public abstract boolean process(btBroadphaseProxy proxy);
}
