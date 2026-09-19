// Port of LinearMath/btMotionState.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/** Synchronises world transforms between physics and the user. */
public abstract class btMotionState {
    /** {@code virtual void getWorldTransform(btTransform& worldTrans) const = 0;} (out-param) */
    public abstract void getWorldTransform(btTransform worldTrans);

    /** Bullet only calls this for active objects. */
    public abstract void setWorldTransform(btTransform worldTrans);
}
