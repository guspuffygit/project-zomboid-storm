// Port of BulletCollision/CollisionDispatch/btCollisionDispatcher.h/.cpp (Bullet 2.82).
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphasePair;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithmConstructionInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btOverlapCallback;
import io.pzstorm.storm.bullet.collision.broadphase.btOverlappingPairCache;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btPoolAllocator;
import java.util.IdentityHashMap;

/**
 * btCollisionDispatcher supports algorithms that handle ConvexConvex and ConvexConcave collision
 * pairs. Time of Impact, Closest Points and Penetration Depth.
 *
 * <p>Memory: the persistent manifold pool and the collision algorithm pool are emulated with {@link
 * btPoolAllocator} addresses so the pool-vs-btAlignedAlloc decisions and the allocation counters
 * match C++. The emulated address of each live manifold is kept in {@link #m_manifoldAddr} (Java
 * only; C++ uses the pointer itself). Algorithms are allocated with {@link
 * #allocateCollisionAlgorithm} (returns the address) and freed with {@link
 * #freeCollisionAlgorithm(long)} given that address.
 */
public class btCollisionDispatcher extends btDispatcher {
    /**
     * user can override this nearcallback for collision filtering and more finegrained control over
     * collision detection ({@code typedef void (*btNearCallback)(...)}).
     */
    @FunctionalInterface
    public interface btNearCallback {
        void call(
                btBroadphasePair collisionPair,
                btCollisionDispatcher dispatcher,
                btDispatcherInfo dispatchInfo);
    }

    // enum DispatcherFlags
    public static final int CD_STATIC_STATIC_REPORTED = 1;
    public static final int CD_USE_RELATIVE_CONTACT_BREAKING_THRESHOLD = 2;
    public static final int CD_DISABLE_CONTACTPOOL_DYNAMIC_ALLOCATION = 4;

    public int m_dispatcherFlags;

    public final btAlignedObjectArray<btPersistentManifold> m_manifoldsPtr =
            new btAlignedObjectArray<>();

    public final btManifoldResult m_defaultManifoldResult = new btManifoldResult();

    public btNearCallback m_nearCallback;

    public btPoolAllocator m_collisionAlgorithmPoolAllocator;

    public btPoolAllocator m_persistentManifoldPoolAllocator;

    public final btCollisionAlgorithmCreateFunc[][] m_doubleDispatch =
            new btCollisionAlgorithmCreateFunc[BroadphaseNativeTypes.MAX_BROADPHASE_COLLISION_TYPES]
                    [BroadphaseNativeTypes.MAX_BROADPHASE_COLLISION_TYPES];

    public btCollisionConfiguration m_collisionConfiguration;

    /** Java only: emulated address of every live manifold (C++: the manifold pointer). */
    public final IdentityHashMap<btPersistentManifold, Long> m_manifoldAddr =
            new IdentityHashMap<>();

    public int getDispatcherFlags() {
        return m_dispatcherFlags;
    }

    public void setDispatcherFlags(int flags) {
        m_dispatcherFlags = flags;
    }

    /**
     * registerCollisionCreateFunc allows registration of custom/alternative collision create
     * functions
     */
    public void registerCollisionCreateFunc(
            int proxyType0, int proxyType1, btCollisionAlgorithmCreateFunc createFunc) {
        m_doubleDispatch[proxyType0][proxyType1] = createFunc;
    }

    @Override
    public int getNumManifolds() {
        return m_manifoldsPtr.size();
    }

    /**
     * {@code btPersistentManifold** getInternalManifoldPointer()}: the manifold array (pointer to
     * element 0), or null when empty as in C++.
     */
    @Override
    public btAlignedObjectArray<btPersistentManifold> getInternalManifoldPointer() {
        return m_manifoldsPtr.size() != 0 ? m_manifoldsPtr : null;
    }

    @Override
    public btPersistentManifold getManifoldByIndexInternal(int index) {
        return m_manifoldsPtr.get(index);
    }

    public btCollisionDispatcher(btCollisionConfiguration collisionConfiguration) {
        m_dispatcherFlags = btCollisionDispatcher.CD_USE_RELATIVE_CONTACT_BREAKING_THRESHOLD;
        m_collisionConfiguration = collisionConfiguration;
        int i;

        setNearCallback(btCollisionDispatcher::defaultNearCallback);

        m_collisionAlgorithmPoolAllocator = collisionConfiguration.getCollisionAlgorithmPool();

        m_persistentManifoldPoolAllocator = collisionConfiguration.getPersistentManifoldPool();

        for (i = 0; i < BroadphaseNativeTypes.MAX_BROADPHASE_COLLISION_TYPES; i++) {
            for (int j = 0; j < BroadphaseNativeTypes.MAX_BROADPHASE_COLLISION_TYPES; j++) {
                m_doubleDispatch[i][j] =
                        m_collisionConfiguration.getCollisionAlgorithmCreateFunc(i, j);
            }
        }
    }

    /** {@code ~btCollisionDispatcher()} (empty in C++). */
    public void destroy() {}

    @Override
    public btPersistentManifold getNewManifold(btCollisionObject body0, btCollisionObject body1) {
        btGlobals.gNumManifold++;

        // optional relative contact breaking threshold, turned on by default (use
        // setDispatcherFlags to switch off feature for improved performance)

        double contactBreakingThreshold =
                (m_dispatcherFlags
                                        & btCollisionDispatcher
                                                .CD_USE_RELATIVE_CONTACT_BREAKING_THRESHOLD)
                                != 0
                        ? btMinMax.btMin(
                                body0.getCollisionShape()
                                        .getContactBreakingThreshold(
                                                btGlobals.gContactBreakingThreshold),
                                body1.getCollisionShape()
                                        .getContactBreakingThreshold(
                                                btGlobals.gContactBreakingThreshold))
                        : btGlobals.gContactBreakingThreshold;

        double contactProcessingThreshold =
                btMinMax.btMin(
                        body0.getContactProcessingThreshold(),
                        body1.getContactProcessingThreshold());

        long mem = 0;

        if (m_persistentManifoldPoolAllocator.getFreeCount() != 0) {
            mem = m_persistentManifoldPoolAllocator.allocate(SIZEOF_btPersistentManifold);
        } else {
            // we got a pool memory overflow, by default we fallback to dynamically allocate
            // memory. If we require a contiguous contact pool then assert.
            if ((m_dispatcherFlags & CD_DISABLE_CONTACTPOOL_DYNAMIC_ALLOCATION) == 0) {
                mem = btGlobals.btAlignedAlloc(SIZEOF_btPersistentManifold, 16);
            } else {
                // make sure to increase the m_defaultMaxPersistentManifoldPoolSize in the
                // btDefaultCollisionConstructionInfo/btDefaultCollisionConfiguration
                return null;
            }
        }
        btPersistentManifold manifold =
                new btPersistentManifold(
                        body0, body1, 0, contactBreakingThreshold, contactProcessingThreshold);
        m_manifoldAddr.put(manifold, mem);
        manifold.m_index1a = m_manifoldsPtr.size();
        m_manifoldsPtr.push_back(manifold);

        return manifold;
    }

    /** sizeof(btPersistentManifold) in the double-precision x86-64 build (decomp: 0x5c0). */
    public static final int SIZEOF_btPersistentManifold = 0x5c0;

    @Override
    public void releaseManifold(btPersistentManifold manifold) {
        btGlobals.gNumManifold--;

        clearManifold(manifold);

        int findIndex = manifold.m_index1a;
        m_manifoldsPtr.swap(findIndex, m_manifoldsPtr.size() - 1);
        m_manifoldsPtr.get(findIndex).m_index1a = findIndex;
        m_manifoldsPtr.pop_back();

        Long addr = m_manifoldAddr.remove(manifold);
        long ptr = addr != null ? addr : 0L;
        if (m_persistentManifoldPoolAllocator.validPtr(ptr)) {
            m_persistentManifoldPoolAllocator.freeMemory(ptr);
        } else {
            btGlobals.btAlignedFree(ptr);
        }
    }

    @Override
    public void clearManifold(btPersistentManifold manifold) {
        manifold.clearManifold();
    }

    // 2-arg findAlgorithm (C++ default sharedManifold = 0) is the final overload in btDispatcher.
    @Override
    public btCollisionAlgorithm findAlgorithm(
            btCollisionObjectWrapper body0Wrap,
            btCollisionObjectWrapper body1Wrap,
            btPersistentManifold sharedManifold) {
        btCollisionAlgorithmConstructionInfo ci = new btCollisionAlgorithmConstructionInfo();

        ci.m_dispatcher1 = this;
        ci.m_manifold = sharedManifold;
        btCollisionAlgorithm algo =
                m_doubleDispatch[body0Wrap.getCollisionShape().getShapeType()][
                        body1Wrap.getCollisionShape().getShapeType()]
                        .CreateCollisionAlgorithm(ci, body0Wrap, body1Wrap);

        return algo;
    }

    @Override
    public boolean needsCollision(btCollisionObject body0, btCollisionObject body1) {
        boolean needsCollision = true;

        if ((!body0.isActive()) && (!body1.isActive())) needsCollision = false;
        else if (!body0.checkCollideWith(body1)) needsCollision = false;

        return needsCollision;
    }

    @Override
    public boolean needsResponse(btCollisionObject body0, btCollisionObject body1) {
        // here you can do filtering
        boolean hasResponse = (body0.hasContactResponse() && body1.hasContactResponse());
        // no response between two static/kinematic bodies:
        hasResponse =
                hasResponse
                        && ((!body0.isStaticOrKinematicObject())
                                || (!body1.isStaticOrKinematicObject()));
        return hasResponse;
    }

    /**
     * interface for iterating all overlapping collision pairs, no matter how those pairs are stored
     * (array, set, map etc) this is useful for the collision dispatcher.
     */
    public static class btCollisionPairCallback extends btOverlapCallback {
        public final btDispatcherInfo m_dispatchInfo;
        public btCollisionDispatcher m_dispatcher;

        public btCollisionPairCallback(
                btDispatcherInfo dispatchInfo, btCollisionDispatcher dispatcher) {
            m_dispatchInfo = dispatchInfo;
            m_dispatcher = dispatcher;
        }

        @Override
        public boolean processOverlap(btBroadphasePair pair) {
            m_dispatcher.getNearCallback().call(pair, m_dispatcher, m_dispatchInfo);

            return false;
        }
    }

    @Override
    public void dispatchAllCollisionPairs(
            btOverlappingPairCache pairCache,
            btDispatcherInfo dispatchInfo,
            btDispatcher dispatcher) {
        // m_blockedForChanges = true;

        btCollisionPairCallback collisionCallback = new btCollisionPairCallback(dispatchInfo, this);

        pairCache.processAllOverlappingPairs(collisionCallback, dispatcher);

        // m_blockedForChanges = false;
    }

    public void setNearCallback(btNearCallback nearCallback) {
        m_nearCallback = nearCallback;
    }

    public btNearCallback getNearCallback() {
        return m_nearCallback;
    }

    /** by default, Bullet will use this near callback */
    public static void defaultNearCallback(
            btBroadphasePair collisionPair,
            btCollisionDispatcher dispatcher,
            btDispatcherInfo dispatchInfo) {
        btCollisionObject colObj0 = (btCollisionObject) collisionPair.m_pProxy0.m_clientObject;
        btCollisionObject colObj1 = (btCollisionObject) collisionPair.m_pProxy1.m_clientObject;

        if (dispatcher.needsCollision(colObj0, colObj1)) {
            btCollisionObjectWrapper obj0Wrap =
                    new btCollisionObjectWrapper(
                            null,
                            colObj0.getCollisionShape(),
                            colObj0,
                            colObj0.getWorldTransform(),
                            -1,
                            -1);
            btCollisionObjectWrapper obj1Wrap =
                    new btCollisionObjectWrapper(
                            null,
                            colObj1.getCollisionShape(),
                            colObj1,
                            colObj1.getWorldTransform(),
                            -1,
                            -1);

            // dispatcher will keep algorithms persistent in the collision pair
            if (collisionPair.m_algorithm == null) {
                collisionPair.m_algorithm = dispatcher.findAlgorithm(obj0Wrap, obj1Wrap);
            }

            if (collisionPair.m_algorithm != null) {
                btManifoldResult contactPointResult = new btManifoldResult(obj0Wrap, obj1Wrap);

                if (dispatchInfo.m_dispatchFunc == btDispatcherInfo.DISPATCH_DISCRETE) {
                    // discrete collision detection query

                    collisionPair.m_algorithm.processCollision(
                            obj0Wrap, obj1Wrap, dispatchInfo, contactPointResult);
                } else {
                    // continuous collision detection query, time of impact (toi)
                    double toi =
                            collisionPair.m_algorithm.calculateTimeOfImpact(
                                    colObj0, colObj1, dispatchInfo, contactPointResult);
                    if (dispatchInfo.m_timeOfImpact > toi) dispatchInfo.m_timeOfImpact = toi;
                }
            }
        }
    }

    /** Returns the emulated address of the algorithm memory ({@code void*}). */
    @Override
    public long allocateCollisionAlgorithm(int size) {
        if (m_collisionAlgorithmPoolAllocator.getFreeCount() != 0) {
            return m_collisionAlgorithmPoolAllocator.allocate(size);
        }

        // warn user for overflow?
        return btGlobals.btAlignedAlloc((long) size, 16);
    }

    @Override
    public void freeCollisionAlgorithm(long ptr) {
        if (m_collisionAlgorithmPoolAllocator.validPtr(ptr)) {
            m_collisionAlgorithmPoolAllocator.freeMemory(ptr);
        } else {
            btGlobals.btAlignedFree(ptr);
        }
    }

    public btCollisionConfiguration getCollisionConfiguration() {
        return m_collisionConfiguration;
    }

    public void setCollisionConfiguration(btCollisionConfiguration config) {
        m_collisionConfiguration = config;
    }

    @Override
    public btPoolAllocator getInternalManifoldPool() {
        return m_persistentManifoldPoolAllocator;
    }
}
