// Port of BulletCollision/CollisionDispatch/btDefaultCollisionConfiguration.h/.cpp (Bullet 2.82).
// USE_BUGGY_SPHERE_BOX_ALGORITHM is not defined.
package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.collision.narrowphase.btConvexPenetrationDepthSolver;
import io.pzstorm.storm.bullet.collision.narrowphase.btGjkEpaPenetrationDepthSolver;
import io.pzstorm.storm.bullet.collision.narrowphase.btMinkowskiPenetrationDepthSolver;
import io.pzstorm.storm.bullet.collision.narrowphase.btVoronoiSimplexSolver;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btPoolAllocator;

/**
 * btCollisionConfiguration allows to configure Bullet collision detection stack allocator, pool
 * memory allocators.
 *
 * <p>Every C++ {@code btAlignedAlloc} of the constructor is replayed (same order and sizes, taken
 * from the decomp of {@code btDefaultCollisionConfiguration::btDefaultCollisionConfiguration} @
 * 001d06e0) so the allocation counters and emulated addresses match. {@link #destroy()} replays the
 * destructor's frees.
 */
public class btDefaultCollisionConfiguration extends btCollisionConfiguration {
    // sizeof(...) in the double-precision x86-64 build (decomp @001d06e0)
    public static final int SIZEOF_btVoronoiSimplexSolver = 0x2d0;
    public static final int SIZEOF_btGjkEpaPenetrationDepthSolver = 8;
    public static final int SIZEOF_btMinkowskiPenetrationDepthSolver = 8;
    public static final int SIZEOF_btConvexConvexAlgorithm_CreateFunc = 0x28;
    public static final int SIZEOF_CreateFunc = 0x10;
    public static final int SIZEOF_btConvexPlaneCollisionAlgorithm_CreateFunc = 0x18;
    public static final int SIZEOF_btPoolAllocator = 0x20;
    public static final int SIZEOF_btPersistentManifold = 0x5c0;

    /**
     * max(sizeof(btConvexConvexAlgorithm), sizeof(btConvexConcaveCollisionAlgorithm),
     * sizeof(btCompoundCollisionAlgorithm)) = 0xa0 (decomp @001d06e0).
     */
    public static final int SIZEOF_maxCollisionAlgorithm = 0xa0;

    /** struct btDefaultCollisionConstructionInfo */
    public static class btDefaultCollisionConstructionInfo {
        public btPoolAllocator m_persistentManifoldPool;
        public btPoolAllocator m_collisionAlgorithmPool;
        public int m_defaultMaxPersistentManifoldPoolSize;
        public int m_defaultMaxCollisionAlgorithmPoolSize;
        public int m_customCollisionAlgorithmMaxElementSize;
        public int m_useEpaPenetrationAlgorithm;

        public btDefaultCollisionConstructionInfo() {
            m_persistentManifoldPool = null;
            m_collisionAlgorithmPool = null;
            m_defaultMaxPersistentManifoldPoolSize = 4096;
            m_defaultMaxCollisionAlgorithmPoolSize = 4096;
            m_customCollisionAlgorithmMaxElementSize = 0;
            m_useEpaPenetrationAlgorithm = 1;
        }
    }

    public int m_persistentManifoldPoolSize;

    public btPoolAllocator m_persistentManifoldPool;
    public boolean m_ownsPersistentManifoldPool;

    public btPoolAllocator m_collisionAlgorithmPool;
    public boolean m_ownsCollisionAlgorithmPool;

    // default simplex/penetration depth solvers
    public btVoronoiSimplexSolver m_simplexSolver;
    public btConvexPenetrationDepthSolver m_pdSolver;

    // default CreationFunctions, filling the m_doubleDispatch table
    public btCollisionAlgorithmCreateFunc m_convexConvexCreateFunc;
    public btCollisionAlgorithmCreateFunc m_convexConcaveCreateFunc;
    public btCollisionAlgorithmCreateFunc m_swappedConvexConcaveCreateFunc;
    public btCollisionAlgorithmCreateFunc m_compoundCreateFunc;
    public btCollisionAlgorithmCreateFunc m_compoundCompoundCreateFunc;

    public btCollisionAlgorithmCreateFunc m_swappedCompoundCreateFunc;
    public btCollisionAlgorithmCreateFunc m_emptyCreateFunc;
    public btCollisionAlgorithmCreateFunc m_sphereSphereCF;
    public btCollisionAlgorithmCreateFunc m_sphereBoxCF;
    public btCollisionAlgorithmCreateFunc m_boxSphereCF;

    public btCollisionAlgorithmCreateFunc m_boxBoxCF;
    public btCollisionAlgorithmCreateFunc m_sphereTriangleCF;
    public btCollisionAlgorithmCreateFunc m_triangleSphereCF;
    public btCollisionAlgorithmCreateFunc m_planeConvexCF;
    public btCollisionAlgorithmCreateFunc m_convexPlaneCF;

    // Java only: emulated btAlignedAlloc addresses of the objects above (freed by destroy()).
    private long m_simplexSolverAddr, m_pdSolverAddr;
    private long m_convexConvexAddr, m_convexConcaveAddr, m_swappedConvexConcaveAddr;
    private long m_compoundAddr, m_compoundCompoundAddr, m_swappedCompoundAddr, m_emptyAddr;
    private long m_sphereSphereAddr, m_sphereTriangleAddr, m_triangleSphereAddr, m_boxBoxAddr;
    private long m_convexPlaneAddr, m_planeConvexAddr;
    private long m_persistentManifoldPoolAddr, m_collisionAlgorithmPoolAddr;

    public btDefaultCollisionConfiguration() {
        this(new btDefaultCollisionConstructionInfo());
    }

    public btDefaultCollisionConfiguration(btDefaultCollisionConstructionInfo constructionInfo) {
        long mem = btGlobals.btAlignedAlloc(SIZEOF_btVoronoiSimplexSolver, 16);
        m_simplexSolverAddr = mem;
        m_simplexSolver = new btVoronoiSimplexSolver();

        if (constructionInfo.m_useEpaPenetrationAlgorithm != 0) {
            mem = btGlobals.btAlignedAlloc(SIZEOF_btGjkEpaPenetrationDepthSolver, 16);
            m_pdSolver = new btGjkEpaPenetrationDepthSolver();
        } else {
            mem = btGlobals.btAlignedAlloc(SIZEOF_btMinkowskiPenetrationDepthSolver, 16);
            m_pdSolver = new btMinkowskiPenetrationDepthSolver();
        }
        m_pdSolverAddr = mem;

        // default CreationFunctions, filling the m_doubleDispatch table
        m_convexConvexAddr =
                btGlobals.btAlignedAlloc(SIZEOF_btConvexConvexAlgorithm_CreateFunc, 16);
        m_convexConvexCreateFunc =
                new btConvexConvexAlgorithm.CreateFunc(m_simplexSolver, m_pdSolver);
        m_convexConcaveAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_convexConcaveCreateFunc = new btConvexConcaveCollisionAlgorithm.CreateFunc();
        m_swappedConvexConcaveAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_swappedConvexConcaveCreateFunc =
                new btConvexConcaveCollisionAlgorithm.SwappedCreateFunc();
        m_compoundAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_compoundCreateFunc = new btCompoundCollisionAlgorithm.CreateFunc();

        m_compoundCompoundAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_compoundCompoundCreateFunc = new btCompoundCompoundCollisionAlgorithm.CreateFunc();

        m_swappedCompoundAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_swappedCompoundCreateFunc = new btCompoundCollisionAlgorithm.SwappedCreateFunc();
        m_emptyAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_emptyCreateFunc = new btEmptyAlgorithm.CreateFunc();

        m_sphereSphereAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_sphereSphereCF = new btSphereSphereCollisionAlgorithm.CreateFunc();

        m_sphereTriangleAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_sphereTriangleCF = new btSphereTriangleCollisionAlgorithm.CreateFunc();
        m_triangleSphereAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_triangleSphereCF = new btSphereTriangleCollisionAlgorithm.CreateFunc();
        m_triangleSphereCF.m_swapped = true;

        m_boxBoxAddr = btGlobals.btAlignedAlloc(SIZEOF_CreateFunc, 16);
        m_boxBoxCF = new btBoxBoxCollisionAlgorithm.CreateFunc();

        // convex versus plane
        m_convexPlaneAddr =
                btGlobals.btAlignedAlloc(SIZEOF_btConvexPlaneCollisionAlgorithm_CreateFunc, 16);
        m_convexPlaneCF = new btConvexPlaneCollisionAlgorithm.CreateFunc();
        m_planeConvexAddr =
                btGlobals.btAlignedAlloc(SIZEOF_btConvexPlaneCollisionAlgorithm_CreateFunc, 16);
        m_planeConvexCF = new btConvexPlaneCollisionAlgorithm.CreateFunc();
        m_planeConvexCF.m_swapped = true;

        /// calculate maximum element size, big enough to fit any collision algorithm in the memory
        // pool
        int collisionAlgorithmMaxElementSize =
                btMinMax.btMax(
                        SIZEOF_maxCollisionAlgorithm,
                        constructionInfo.m_customCollisionAlgorithmMaxElementSize);

        if (constructionInfo.m_persistentManifoldPool != null) {
            m_ownsPersistentManifoldPool = false;
            m_persistentManifoldPool = constructionInfo.m_persistentManifoldPool;
        } else {
            m_ownsPersistentManifoldPool = true;
            m_persistentManifoldPoolAddr = btGlobals.btAlignedAlloc(SIZEOF_btPoolAllocator, 16);
            m_persistentManifoldPool =
                    new btPoolAllocator(
                            SIZEOF_btPersistentManifold,
                            constructionInfo.m_defaultMaxPersistentManifoldPoolSize);
        }

        if (constructionInfo.m_collisionAlgorithmPool != null) {
            m_ownsCollisionAlgorithmPool = false;
            m_collisionAlgorithmPool = constructionInfo.m_collisionAlgorithmPool;
        } else {
            m_ownsCollisionAlgorithmPool = true;
            m_collisionAlgorithmPoolAddr = btGlobals.btAlignedAlloc(SIZEOF_btPoolAllocator, 16);
            m_collisionAlgorithmPool =
                    new btPoolAllocator(
                            collisionAlgorithmMaxElementSize,
                            constructionInfo.m_defaultMaxCollisionAlgorithmPoolSize);
        }
    }

    /** {@code ~btDefaultCollisionConfiguration()} */
    public void destroy() {
        if (m_ownsCollisionAlgorithmPool) {
            m_collisionAlgorithmPool.destroy();
            btGlobals.btAlignedFree(m_collisionAlgorithmPoolAddr);
        }
        if (m_ownsPersistentManifoldPool) {
            m_persistentManifoldPool.destroy();
            btGlobals.btAlignedFree(m_persistentManifoldPoolAddr);
        }

        btGlobals.btAlignedFree(m_convexConvexAddr);
        btGlobals.btAlignedFree(m_convexConcaveAddr);
        btGlobals.btAlignedFree(m_swappedConvexConcaveAddr);
        btGlobals.btAlignedFree(m_compoundAddr);
        btGlobals.btAlignedFree(m_compoundCompoundAddr);
        btGlobals.btAlignedFree(m_swappedCompoundAddr);
        btGlobals.btAlignedFree(m_emptyAddr);
        btGlobals.btAlignedFree(m_sphereSphereAddr);
        btGlobals.btAlignedFree(m_sphereTriangleAddr);
        btGlobals.btAlignedFree(m_triangleSphereAddr);
        btGlobals.btAlignedFree(m_boxBoxAddr);
        btGlobals.btAlignedFree(m_convexPlaneAddr);
        btGlobals.btAlignedFree(m_planeConvexAddr);
        btGlobals.btAlignedFree(m_simplexSolverAddr);
        btGlobals.btAlignedFree(m_pdSolverAddr);
    }

    /** memory pools */
    @Override
    public btPoolAllocator getPersistentManifoldPool() {
        return m_persistentManifoldPool;
    }

    @Override
    public btPoolAllocator getCollisionAlgorithmPool() {
        return m_collisionAlgorithmPool;
    }

    public btVoronoiSimplexSolver getSimplexSolver() {
        return m_simplexSolver;
    }

    @Override
    public btCollisionAlgorithmCreateFunc getCollisionAlgorithmCreateFunc(
            int proxyType0, int proxyType1) {

        if ((proxyType0 == BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE)
                && (proxyType1 == BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE)) {
            return m_sphereSphereCF;
        }
        if ((proxyType0 == BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE)
                && (proxyType1 == BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE)) {
            return m_sphereTriangleCF;
        }

        if ((proxyType0 == BroadphaseNativeTypes.TRIANGLE_SHAPE_PROXYTYPE)
                && (proxyType1 == BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE)) {
            return m_triangleSphereCF;
        }

        if ((proxyType0 == BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE)
                && (proxyType1 == BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE)) {
            return m_boxBoxCF;
        }

        if (btBroadphaseProxy.isConvex(proxyType0)
                && (proxyType1 == BroadphaseNativeTypes.STATIC_PLANE_PROXYTYPE)) {
            return m_convexPlaneCF;
        }

        if (btBroadphaseProxy.isConvex(proxyType1)
                && (proxyType0 == BroadphaseNativeTypes.STATIC_PLANE_PROXYTYPE)) {
            return m_planeConvexCF;
        }

        if (btBroadphaseProxy.isConvex(proxyType0) && btBroadphaseProxy.isConvex(proxyType1)) {
            return m_convexConvexCreateFunc;
        }

        if (btBroadphaseProxy.isConvex(proxyType0) && btBroadphaseProxy.isConcave(proxyType1)) {
            return m_convexConcaveCreateFunc;
        }

        if (btBroadphaseProxy.isConvex(proxyType1) && btBroadphaseProxy.isConcave(proxyType0)) {
            return m_swappedConvexConcaveCreateFunc;
        }

        if (btBroadphaseProxy.isCompound(proxyType0) && btBroadphaseProxy.isCompound(proxyType1)) {
            return m_compoundCompoundCreateFunc;
        }

        if (btBroadphaseProxy.isCompound(proxyType0)) {
            return m_compoundCreateFunc;
        } else {
            if (btBroadphaseProxy.isCompound(proxyType1)) {
                return m_swappedCompoundCreateFunc;
            }
        }

        // failed to find an algorithm
        return m_emptyCreateFunc;
    }

    /**
     * Use this method to allow to generate multiple contact points between at once, between two
     * objects using the generic convex-convex algorithm. By default, this feature is disabled for
     * best performance.
     */
    public void setConvexConvexMultipointIterations() {
        setConvexConvexMultipointIterations(3, 3);
    }

    public void setConvexConvexMultipointIterations(
            int numPerturbationIterations, int minimumPointsPerturbationThreshold) {
        btConvexConvexAlgorithm.CreateFunc convexConvex =
                (btConvexConvexAlgorithm.CreateFunc) m_convexConvexCreateFunc;
        convexConvex.m_numPerturbationIterations = numPerturbationIterations;
        convexConvex.m_minimumPointsPerturbationThreshold = minimumPointsPerturbationThreshold;
    }

    public void setPlaneConvexMultipointIterations() {
        setPlaneConvexMultipointIterations(3, 3);
    }

    public void setPlaneConvexMultipointIterations(
            int numPerturbationIterations, int minimumPointsPerturbationThreshold) {
        btConvexPlaneCollisionAlgorithm.CreateFunc cpCF =
                (btConvexPlaneCollisionAlgorithm.CreateFunc) m_convexPlaneCF;
        cpCF.m_numPerturbationIterations = numPerturbationIterations;
        cpCF.m_minimumPointsPerturbationThreshold = minimumPointsPerturbationThreshold;

        btConvexPlaneCollisionAlgorithm.CreateFunc pcCF =
                (btConvexPlaneCollisionAlgorithm.CreateFunc) m_planeConvexCF;
        pcCF.m_numPerturbationIterations = numPerturbationIterations;
        pcCF.m_minimumPointsPerturbationThreshold = minimumPointsPerturbationThreshold;
    }
}
