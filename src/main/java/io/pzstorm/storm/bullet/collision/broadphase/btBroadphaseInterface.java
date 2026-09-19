// Port of BulletCollision/BroadphaseCollision/btBroadphaseInterface.h (Bullet 2.82), class
// btBroadphaseInterface (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btBroadphaseInterface class provides an interface to detect aabb-overlapping object pairs.
 * The actual overlapping pair management, storage, adding and removing of pairs is dealt by the
 * btOverlappingPairCache class.
 */
public abstract class btBroadphaseInterface {
    /** {@code virtual ~btBroadphaseInterface() {}} */
    public void destroy() {}

    public abstract btBroadphaseProxy createProxy(
            btVector3 aabbMin,
            btVector3 aabbMax,
            int shapeType,
            Object userPtr,
            short collisionFilterGroup,
            short collisionFilterMask,
            btDispatcher dispatcher,
            Object multiSapProxy);

    public abstract void destroyProxy(btBroadphaseProxy proxy, btDispatcher dispatcher);

    public abstract void setAabb(
            btBroadphaseProxy proxy, btVector3 aabbMin, btVector3 aabbMax, btDispatcher dispatcher);

    public abstract void getAabb(btBroadphaseProxy proxy, btVector3 aabbMin, btVector3 aabbMax);

    /** Default arguments {@code aabbMin = btVector3(0,0,0), aabbMax = btVector3(0,0,0)}. */
    public final void rayTest(
            btVector3 rayFrom, btVector3 rayTo, btBroadphaseRayCallback rayCallback) {
        rayTest(rayFrom, rayTo, rayCallback, new btVector3(0, 0, 0), new btVector3(0, 0, 0));
    }

    public abstract void rayTest(
            btVector3 rayFrom,
            btVector3 rayTo,
            btBroadphaseRayCallback rayCallback,
            btVector3 aabbMin,
            btVector3 aabbMax);

    public abstract void aabbTest(
            btVector3 aabbMin, btVector3 aabbMax, btBroadphaseAabbCallback callback);

    /**
     * calculateOverlappingPairs is optional: incremental algorithms might do it during the set
     * aabb.
     */
    public abstract void calculateOverlappingPairs(btDispatcher dispatcher);

    public abstract btOverlappingPairCache getOverlappingPairCache();

    /** getAabb returns the axis aligned bounding box in the 'global' coordinate frame. */
    public abstract void getBroadphaseAabb(btVector3 aabbMin, btVector3 aabbMax);

    /** Reset broadphase internal structures, to ensure determinism/reproducability. */
    public void resetPool(btDispatcher dispatcher) {}

    public abstract void printStats();
}
