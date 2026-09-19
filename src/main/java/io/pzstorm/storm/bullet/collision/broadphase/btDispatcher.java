// Port of BulletCollision/BroadphaseCollision/btDispatcher.h and btDispatcher.cpp (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObjectWrapper;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btPoolAllocator;

/**
 * The btDispatcher interface class can be used in combination with broadphase to dispatch
 * calculations for overlapping pairs. For example for pairwise collision detection, calculating
 * contact points stored in btPersistentManifold or user callbacks (game logic).
 *
 * <p>Java mapping: {@code void* allocateCollisionAlgorithm(int)} returns the fake heap address
 * ({@code long}); {@code freeCollisionAlgorithm(void*)} takes that address, i.e. callers pass
 * {@link btCollisionAlgorithm#m_allocAddress} after calling {@link btCollisionAlgorithm#destroy()}
 * (the C++ explicit destructor call). {@code getInternalManifoldPointer()} returns the manifold
 * array itself instead of {@code &m_manifoldsPtr[0]}.
 */
public abstract class btDispatcher {
    /** {@code btDispatcher::~btDispatcher()} (btDispatcher.cpp, empty). */
    public void destroy() {}

    /** Default argument {@code sharedManifold = 0}. */
    public final btCollisionAlgorithm findAlgorithm(
            btCollisionObjectWrapper body0Wrap, btCollisionObjectWrapper body1Wrap) {
        return findAlgorithm(body0Wrap, body1Wrap, null);
    }

    public abstract btCollisionAlgorithm findAlgorithm(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btPersistentManifold sharedManifold);

    public abstract btPersistentManifold getNewManifold(btCollisionObject b0, btCollisionObject b1);

    public abstract void releaseManifold(btPersistentManifold manifold);

    public abstract void clearManifold(btPersistentManifold manifold);

    public abstract boolean needsCollision(btCollisionObject body0, btCollisionObject body1);

    public abstract boolean needsResponse(btCollisionObject body0, btCollisionObject body1);

    public abstract void dispatchAllCollisionPairs(
            btOverlappingPairCache pairCache,
            btDispatcherInfo dispatchInfo,
            btDispatcher dispatcher);

    public abstract int getNumManifolds();

    public abstract btPersistentManifold getManifoldByIndexInternal(int index);

    public abstract btAlignedObjectArray<btPersistentManifold> getInternalManifoldPointer();

    public abstract btPoolAllocator getInternalManifoldPool();

    public abstract long allocateCollisionAlgorithm(int size);

    public abstract void freeCollisionAlgorithm(long ptr);
}
