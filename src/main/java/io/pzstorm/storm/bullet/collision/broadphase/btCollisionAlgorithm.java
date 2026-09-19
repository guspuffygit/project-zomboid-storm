// Port of BulletCollision/BroadphaseCollision/btCollisionAlgorithm.h and btCollisionAlgorithm.cpp
// (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObjectWrapper;
import io.pzstorm.storm.bullet.collision.dispatch.btManifoldResult;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;

/**
 * btCollisionAlgorithm is an collision interface that is compatible with the Broadphase and
 * btDispatcher. It is persistent over frames.
 *
 * <p>{@link #destroy()} stands for the virtual destructor: subclasses override it and call {@code
 * super.destroy()} last. C++ {@code algo->~btCollisionAlgorithm(); dispatcher->
 * freeCollisionAlgorithm(algo);} becomes {@code algo.destroy();
 * dispatcher.freeCollisionAlgorithm(algo.m_allocAddress);}.
 */
public abstract class btCollisionAlgorithm {
    protected btDispatcher m_dispatcher;

    /**
     * Not in C++: the address returned by {@code btDispatcher.allocateCollisionAlgorithm} for this
     * object (set by the creator). Used for the PTR-ORDER emulation in {@link
     * btBroadphasePairSortPredicate} and for {@code freeCollisionAlgorithm}.
     */
    public long m_allocAddress;

    /** {@code btCollisionAlgorithm() {}} (m_dispatcher uninitialized in C++). */
    public btCollisionAlgorithm() {}

    public btCollisionAlgorithm(btCollisionAlgorithmConstructionInfo ci) {
        m_dispatcher = ci.m_dispatcher1;
    }

    /** {@code virtual ~btCollisionAlgorithm() {}} */
    public void destroy() {}

    public abstract void processCollision(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut);

    public abstract double calculateTimeOfImpact(
            btCollisionObject body0,
            btCollisionObject body1,
            btDispatcherInfo dispatchInfo,
            btManifoldResult resultOut);

    /**
     * {@code btManifoldArray} = {@code btAlignedObjectArray<btPersistentManifold*>} (pointer mode).
     */
    public abstract void getAllContactManifolds(
            btAlignedObjectArray<btPersistentManifold> manifoldArray);
}
