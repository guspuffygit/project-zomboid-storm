// Port of BulletCollision/CollisionDispatch/btCollisionWorld.h/.cpp (Bullet 2.82).
// USE_BRUTEFORCE_RAYBROADPHASE, RECALCULATE_AABB_RAYCAST, USE_SUBSIMPLEX_CONVEX_CAST and
// DISABLE_DBVT_COMPOUNDSHAPE_RAYCAST_ACCELERATION are not defined.
package io.pzstorm.storm.bullet.collision.dispatch;

import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.BOX_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.CAPSULE_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.COMPOUND_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.CONE_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.CONVEX_TRIANGLEMESH_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.CYLINDER_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.GIMPACT_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.MULTI_SPHERE_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.SPHERE_SHAPE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.STATIC_PLANE_PROXYTYPE;
import static io.pzstorm.storm.bullet.collision.broadphase.BroadphaseNativeTypes.TRIANGLE_MESH_SHAPE_PROXYTYPE;

import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseAabbCallback;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseInterface;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseProxy;
import io.pzstorm.storm.bullet.collision.broadphase.btBroadphaseRayCallback;
import io.pzstorm.storm.bullet.collision.broadphase.btCollisionAlgorithm;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvt;
import io.pzstorm.storm.bullet.collision.broadphase.btDbvtNode;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.broadphase.btDispatcherInfo;
import io.pzstorm.storm.bullet.collision.broadphase.btOverlappingPairCache;
import io.pzstorm.storm.bullet.collision.narrowphase.btContinuousConvexCollision;
import io.pzstorm.storm.bullet.collision.narrowphase.btConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btGjkConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btGjkEpaPenetrationDepthSolver;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.collision.narrowphase.btSubsimplexConvexCast;
import io.pzstorm.storm.bullet.collision.narrowphase.btTriangleConvexcastCallback;
import io.pzstorm.storm.bullet.collision.narrowphase.btTriangleRaycastCallback;
import io.pzstorm.storm.bullet.collision.narrowphase.btVoronoiSimplexSolver;
import io.pzstorm.storm.bullet.collision.shapes.btBoxShape;
import io.pzstorm.storm.bullet.collision.shapes.btBvhTriangleMeshShape;
import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;
import io.pzstorm.storm.bullet.collision.shapes.btCollisionShape;
import io.pzstorm.storm.bullet.collision.shapes.btCompoundShape;
import io.pzstorm.storm.bullet.collision.shapes.btConcaveShape;
import io.pzstorm.storm.bullet.collision.shapes.btConvexPolyhedron;
import io.pzstorm.storm.bullet.collision.shapes.btConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btPolyhedralConvexShape;
import io.pzstorm.storm.bullet.collision.shapes.btSphereShape;
import io.pzstorm.storm.bullet.collision.shapes.btStaticPlaneShape;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleCallback;
import io.pzstorm.storm.bullet.collision.shapes.btTriangleMeshShape;
import io.pzstorm.storm.bullet.linearmath.CProfileSample;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btScalarArray;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** CollisionWorld is interface and container for the collision detection */
public class btCollisionWorld {
    /** btCollisionObjectArray (typedef btAlignedObjectArray&lt;btCollisionObject*&gt;) */
    public final btAlignedObjectArray<btCollisionObject> m_collisionObjects =
            new btAlignedObjectArray<>();

    public btDispatcher m_dispatcher1;

    public final btDispatcherInfo m_dispatchInfo = new btDispatcherInfo();

    public btBroadphaseInterface m_broadphasePairCache;

    public btIDebugDraw m_debugDrawer;

    /// m_forceUpdateAllAabbs can be set to false as an optimization to only update active object
    // AABBs it is true by default, because it is error-prone (setting the position of static
    // objects wouldn't update their AABB)
    public boolean m_forceUpdateAllAabbs;

    /** {@code static bool reportMe = true;} in updateSingleAabb. */
    public static boolean updateSingleAabb_reportMe = true;

    // this constructor doesn't own the dispatcher and paircache/broadphase
    public btCollisionWorld(
            btDispatcher dispatcher,
            btBroadphaseInterface pairCache,
            btCollisionConfiguration collisionConfiguration) {
        m_dispatcher1 = dispatcher;
        m_broadphasePairCache = pairCache;
        m_debugDrawer = null;
        m_forceUpdateAllAabbs = true;
    }

    /** {@code virtual ~btCollisionWorld()} */
    public void destroy() {
        // clean up remaining objects
        int i;
        for (i = 0; i < m_collisionObjects.size(); i++) {
            btCollisionObject collisionObject = m_collisionObjects.get(i);

            btBroadphaseProxy bp = collisionObject.getBroadphaseHandle();
            if (bp != null) {
                //
                // only clear the cached algorithms
                //
                getBroadphase().getOverlappingPairCache().cleanProxyFromPairs(bp, m_dispatcher1);
                getBroadphase().destroyProxy(bp, m_dispatcher1);
                collisionObject.setBroadphaseHandle(null);
            }
        }
        // member btAlignedObjectArray destructor
        m_collisionObjects.clear();
    }

    public void setBroadphase(btBroadphaseInterface pairCache) {
        m_broadphasePairCache = pairCache;
    }

    public btBroadphaseInterface getBroadphase() {
        return m_broadphasePairCache;
    }

    public btOverlappingPairCache getPairCache() {
        return m_broadphasePairCache.getOverlappingPairCache();
    }

    public btDispatcher getDispatcher() {
        return m_dispatcher1;
    }

    public void updateSingleAabb(btCollisionObject colObj) {
        btVector3 minAabb = new btVector3(), maxAabb = new btVector3();
        colObj.getCollisionShape().getAabb(colObj.getWorldTransform(), minAabb, maxAabb);
        // need to increase the aabb for contact thresholds
        btVector3 contactThreshold =
                new btVector3(
                        btGlobals.gContactBreakingThreshold,
                        btGlobals.gContactBreakingThreshold,
                        btGlobals.gContactBreakingThreshold);
        minAabb.subLocal(contactThreshold);
        maxAabb.addLocal(contactThreshold);

        if (getDispatchInfo().m_useContinuous
                && colObj.getInternalType() == btCollisionObject.CO_RIGID_BODY
                && !colObj.isStaticOrKinematicObject()) {
            btVector3 minAabb2 = new btVector3(), maxAabb2 = new btVector3();
            colObj.getCollisionShape()
                    .getAabb(colObj.getInterpolationWorldTransform(), minAabb2, maxAabb2);
            minAabb2.subLocal(contactThreshold);
            maxAabb2.addLocal(contactThreshold);
            minAabb.setMin(minAabb2);
            maxAabb.setMax(maxAabb2);
        }

        btBroadphaseInterface bp = m_broadphasePairCache;

        // moving objects should be moderately sized, probably something wrong if not
        if (colObj.isStaticObject() || (maxAabb.sub(minAabb).length2() < 1e12)) {
            bp.setAabb(colObj.getBroadphaseHandle(), minAabb, maxAabb, m_dispatcher1);
        } else {
            // something went wrong, investigate
            // this assert is unwanted in 3D modelers (danger of loosing work)
            colObj.setActivationState(btCollisionObject.DISABLE_SIMULATION);

            if (updateSingleAabb_reportMe && m_debugDrawer != null) {
                updateSingleAabb_reportMe = false;
                m_debugDrawer.reportErrorWarning(
                        "Overflow in AABB, object removed from simulation");
                m_debugDrawer.reportErrorWarning(
                        "If you can reproduce this, please email bugs@continuousphysics.com\n");
                m_debugDrawer.reportErrorWarning(
                        "Please include above information, your Platform, version of OS.\n");
                m_debugDrawer.reportErrorWarning("Thanks.\n");
            }
        }
    }

    public void updateAabbs() {
        try (CProfileSample __profile = new CProfileSample("updateAabbs")) {
            for (int i = 0; i < m_collisionObjects.size(); i++) {
                btCollisionObject colObj = m_collisionObjects.get(i);

                // only update aabb of active objects
                if (m_forceUpdateAllAabbs || colObj.isActive()) {
                    updateSingleAabb(colObj);
                }
            }
        }
    }

    /// the computeOverlappingPairs is usually already called by performDiscreteCollisionDetection
    // (or stepSimulation) it can be useful to use if you perform ray tests without collision
    // detection/simulation
    public void computeOverlappingPairs() {
        try (CProfileSample __profile = new CProfileSample("calculateOverlappingPairs")) {
            m_broadphasePairCache.calculateOverlappingPairs(m_dispatcher1);
        }
    }

    public void setDebugDrawer(btIDebugDraw debugDrawer) {
        m_debugDrawer = debugDrawer;
    }

    public btIDebugDraw getDebugDrawer() {
        return m_debugDrawer;
    }

    // ---------------------------------------------------------------------------------------
    // Nested result structs / callbacks
    // ---------------------------------------------------------------------------------------

    /// LocalShapeInfo gives extra information for complex shapes
    /// Currently, only btTriangleMeshShape is available, so it just contains triangleIndex and
    // subpart
    public static class LocalShapeInfo {
        public int m_shapePart;
        public int m_triangleIndex;
    }

    public static class LocalRayResult {
        public btCollisionObject m_collisionObject;
        public LocalShapeInfo m_localShapeInfo;
        public final btVector3 m_hitNormalLocal = new btVector3();
        public double m_hitFraction;

        public LocalRayResult(
                btCollisionObject collisionObject,
                LocalShapeInfo localShapeInfo,
                btVector3 hitNormalLocal,
                double hitFraction) {
            m_collisionObject = collisionObject;
            m_localShapeInfo = localShapeInfo;
            m_hitNormalLocal.set(hitNormalLocal);
            m_hitFraction = hitFraction;
        }
    }

    /// RayResultCallback is used to report new raycast results
    public abstract static class RayResultCallback {
        public double m_closestHitFraction;
        public btCollisionObject m_collisionObject;
        public short m_collisionFilterGroup;
        public short m_collisionFilterMask;

        // @BP Mod - Custom flags, currently used to enable backface culling on tri-meshes, see
        // btRaycastCallback.h. Apply any of the EFlags defined there on m_flags here to invoke.
        /** unsigned int */
        public int m_flags;

        /** {@code virtual ~RayResultCallback()} */
        public void destroy() {}

        public boolean hasHit() {
            return (m_collisionObject != null);
        }

        public RayResultCallback() {
            m_closestHitFraction = 1.;
            m_collisionObject = null;
            m_collisionFilterGroup = (short) btBroadphaseProxy.DefaultFilter;
            m_collisionFilterMask = (short) btBroadphaseProxy.AllFilter;
            // @BP Mod
            m_flags = 0;
        }

        public boolean needsCollision(btBroadphaseProxy proxy0) {
            boolean collides = (proxy0.m_collisionFilterGroup & m_collisionFilterMask) != 0;
            collides = collides && ((m_collisionFilterGroup & proxy0.m_collisionFilterMask) != 0);
            return collides;
        }

        public abstract double addSingleResult(
                LocalRayResult rayResult, boolean normalInWorldSpace);
    }

    public static class ClosestRayResultCallback extends RayResultCallback {
        public final btVector3 m_rayFromWorld = new btVector3(); // used to calculate
        // hitPointWorld from hitFraction
        public final btVector3 m_rayToWorld = new btVector3();

        public final btVector3 m_hitNormalWorld = new btVector3();
        public final btVector3 m_hitPointWorld = new btVector3();

        public ClosestRayResultCallback(btVector3 rayFromWorld, btVector3 rayToWorld) {
            m_rayFromWorld.set(rayFromWorld);
            m_rayToWorld.set(rayToWorld);
        }

        @Override
        public double addSingleResult(LocalRayResult rayResult, boolean normalInWorldSpace) {
            // caller already does the filter on the m_closestHitFraction
            m_closestHitFraction = rayResult.m_hitFraction;
            m_collisionObject = rayResult.m_collisionObject;
            if (normalInWorldSpace) {
                m_hitNormalWorld.set(rayResult.m_hitNormalLocal);
            } else {
                /// need to transform normal into worldspace
                m_hitNormalWorld.set(
                        m_collisionObject
                                .getWorldTransform()
                                .getBasis()
                                .mul(rayResult.m_hitNormalLocal));
            }
            m_hitPointWorld.setInterpolate3(m_rayFromWorld, m_rayToWorld, rayResult.m_hitFraction);
            return rayResult.m_hitFraction;
        }
    }

    public static class AllHitsRayResultCallback extends RayResultCallback {
        public final btAlignedObjectArray<btCollisionObject> m_collisionObjects =
                new btAlignedObjectArray<>();

        public final btVector3 m_rayFromWorld = new btVector3(); // used to calculate
        // hitPointWorld from hitFraction
        public final btVector3 m_rayToWorld = new btVector3();

        public final btAlignedObjectArray<btVector3> m_hitNormalWorld =
                btAlignedObjectArray.ofVector3();
        public final btAlignedObjectArray<btVector3> m_hitPointWorld =
                btAlignedObjectArray.ofVector3();
        public final btScalarArray m_hitFractions = new btScalarArray();

        public AllHitsRayResultCallback(btVector3 rayFromWorld, btVector3 rayToWorld) {
            m_rayFromWorld.set(rayFromWorld);
            m_rayToWorld.set(rayToWorld);
        }

        /** {@code ~AllHitsRayResultCallback()}: member arrays destroyed in reverse order. */
        @Override
        public void destroy() {
            m_hitFractions.clear();
            m_hitPointWorld.clear();
            m_hitNormalWorld.clear();
            m_collisionObjects.clear();
            super.destroy();
        }

        @Override
        public double addSingleResult(LocalRayResult rayResult, boolean normalInWorldSpace) {
            m_collisionObject = rayResult.m_collisionObject;
            m_collisionObjects.push_back(rayResult.m_collisionObject);
            btVector3 hitNormalWorld;
            if (normalInWorldSpace) {
                hitNormalWorld = new btVector3(rayResult.m_hitNormalLocal);
            } else {
                /// need to transform normal into worldspace
                hitNormalWorld =
                        m_collisionObject
                                .getWorldTransform()
                                .getBasis()
                                .mul(rayResult.m_hitNormalLocal);
            }
            m_hitNormalWorld.push_back(hitNormalWorld);
            btVector3 hitPointWorld = new btVector3();
            hitPointWorld.setInterpolate3(m_rayFromWorld, m_rayToWorld, rayResult.m_hitFraction);
            m_hitPointWorld.push_back(hitPointWorld);
            m_hitFractions.push_back(rayResult.m_hitFraction);
            return m_closestHitFraction;
        }
    }

    public static class LocalConvexResult {
        public btCollisionObject m_hitCollisionObject;
        public LocalShapeInfo m_localShapeInfo;
        public final btVector3 m_hitNormalLocal = new btVector3();
        public final btVector3 m_hitPointLocal = new btVector3();
        public double m_hitFraction;

        public LocalConvexResult(
                btCollisionObject hitCollisionObject,
                LocalShapeInfo localShapeInfo,
                btVector3 hitNormalLocal,
                btVector3 hitPointLocal,
                double hitFraction) {
            m_hitCollisionObject = hitCollisionObject;
            m_localShapeInfo = localShapeInfo;
            m_hitNormalLocal.set(hitNormalLocal);
            m_hitPointLocal.set(hitPointLocal);
            m_hitFraction = hitFraction;
        }
    }

    /// RayResultCallback is used to report new raycast results
    public abstract static class ConvexResultCallback {
        public double m_closestHitFraction;
        public short m_collisionFilterGroup;
        public short m_collisionFilterMask;

        public ConvexResultCallback() {
            m_closestHitFraction = 1.;
            m_collisionFilterGroup = (short) btBroadphaseProxy.DefaultFilter;
            m_collisionFilterMask = (short) btBroadphaseProxy.AllFilter;
        }

        /** {@code virtual ~ConvexResultCallback()} */
        public void destroy() {}

        public boolean hasHit() {
            return (m_closestHitFraction < 1.);
        }

        public boolean needsCollision(btBroadphaseProxy proxy0) {
            boolean collides = (proxy0.m_collisionFilterGroup & m_collisionFilterMask) != 0;
            collides = collides && ((m_collisionFilterGroup & proxy0.m_collisionFilterMask) != 0);
            return collides;
        }

        public abstract double addSingleResult(
                LocalConvexResult convexResult, boolean normalInWorldSpace);
    }

    public static class ClosestConvexResultCallback extends ConvexResultCallback {
        public final btVector3 m_convexFromWorld = new btVector3(); // used to calculate
        // hitPointWorld from hitFraction
        public final btVector3 m_convexToWorld = new btVector3();

        public final btVector3 m_hitNormalWorld = new btVector3();
        public final btVector3 m_hitPointWorld = new btVector3();
        public btCollisionObject m_hitCollisionObject;

        public ClosestConvexResultCallback(btVector3 convexFromWorld, btVector3 convexToWorld) {
            m_convexFromWorld.set(convexFromWorld);
            m_convexToWorld.set(convexToWorld);
            m_hitCollisionObject = null;
        }

        @Override
        public double addSingleResult(LocalConvexResult convexResult, boolean normalInWorldSpace) {
            // caller already does the filter on the m_closestHitFraction
            m_closestHitFraction = convexResult.m_hitFraction;
            m_hitCollisionObject = convexResult.m_hitCollisionObject;
            if (normalInWorldSpace) {
                m_hitNormalWorld.set(convexResult.m_hitNormalLocal);
            } else {
                /// need to transform normal into worldspace
                m_hitNormalWorld.set(
                        m_hitCollisionObject
                                .getWorldTransform()
                                .getBasis()
                                .mul(convexResult.m_hitNormalLocal));
            }
            m_hitPointWorld.set(convexResult.m_hitPointLocal);
            return convexResult.m_hitFraction;
        }
    }

    /// ContactResultCallback is used to report contact points
    public abstract static class ContactResultCallback {
        public short m_collisionFilterGroup;
        public short m_collisionFilterMask;

        public ContactResultCallback() {
            m_collisionFilterGroup = (short) btBroadphaseProxy.DefaultFilter;
            m_collisionFilterMask = (short) btBroadphaseProxy.AllFilter;
        }

        /** {@code virtual ~ContactResultCallback()} */
        public void destroy() {}

        public boolean needsCollision(btBroadphaseProxy proxy0) {
            boolean collides = (proxy0.m_collisionFilterGroup & m_collisionFilterMask) != 0;
            collides = collides && ((m_collisionFilterGroup & proxy0.m_collisionFilterMask) != 0);
            return collides;
        }

        public abstract double addSingleResult(
                btManifoldPoint cp,
                btCollisionObjectWrapper colObj0Wrap,
                int partId0,
                int index0,
                btCollisionObjectWrapper colObj1Wrap,
                int partId1,
                int index1);
    }

    public int getNumCollisionObjects() {
        return m_collisionObjects.size();
    }

    // ---------------------------------------------------------------------------------------
    // Object management
    // ---------------------------------------------------------------------------------------

    public void addCollisionObject(btCollisionObject collisionObject) {
        addCollisionObject(
                collisionObject,
                (short) btBroadphaseProxy.DefaultFilter,
                (short) btBroadphaseProxy.AllFilter);
    }

    public void addCollisionObject(btCollisionObject collisionObject, short collisionFilterGroup) {
        addCollisionObject(
                collisionObject, collisionFilterGroup, (short) btBroadphaseProxy.AllFilter);
    }

    public void addCollisionObject(
            btCollisionObject collisionObject,
            short collisionFilterGroup,
            short collisionFilterMask) {

        // check that the object isn't already added

        m_collisionObjects.push_back(collisionObject);

        // calculate new AABB
        btTransform trans = new btTransform(collisionObject.getWorldTransform());

        btVector3 minAabb = new btVector3();
        btVector3 maxAabb = new btVector3();
        collisionObject.getCollisionShape().getAabb(trans, minAabb, maxAabb);

        int type = collisionObject.getCollisionShape().getShapeType();
        collisionObject.setBroadphaseHandle(
                getBroadphase()
                        .createProxy(
                                minAabb,
                                maxAabb,
                                type,
                                collisionObject,
                                collisionFilterGroup,
                                collisionFilterMask,
                                m_dispatcher1,
                                null));
    }

    public btAlignedObjectArray<btCollisionObject> getCollisionObjectArray() {
        return m_collisionObjects;
    }

    public void removeCollisionObject(btCollisionObject collisionObject) {
        // bool removeFromBroadphase = false;

        {
            btBroadphaseProxy bp = collisionObject.getBroadphaseHandle();
            if (bp != null) {
                //
                // only clear the cached algorithms
                //
                getBroadphase().getOverlappingPairCache().cleanProxyFromPairs(bp, m_dispatcher1);
                getBroadphase().destroyProxy(bp, m_dispatcher1);
                collisionObject.setBroadphaseHandle(null);
            }
        }

        // swapremove
        m_collisionObjects.remove(collisionObject);
    }

    public void performDiscreteCollisionDetection() {
        try (CProfileSample __profile = new CProfileSample("performDiscreteCollisionDetection")) {
            btDispatcherInfo dispatchInfo = getDispatchInfo();

            updateAabbs();

            computeOverlappingPairs();

            btDispatcher dispatcher = getDispatcher();
            {
                try (CProfileSample __profile2 = new CProfileSample("dispatchAllCollisionPairs")) {
                    if (dispatcher != null)
                        dispatcher.dispatchAllCollisionPairs(
                                m_broadphasePairCache.getOverlappingPairCache(),
                                dispatchInfo,
                                m_dispatcher1);
                }
            }
        }
    }

    public btDispatcherInfo getDispatchInfo() {
        return m_dispatchInfo;
    }

    public boolean getForceUpdateAllAabbs() {
        return m_forceUpdateAllAabbs;
    }

    public void setForceUpdateAllAabbs(boolean forceUpdateAllAabbs) {
        m_forceUpdateAllAabbs = forceUpdateAllAabbs;
    }

    // ---------------------------------------------------------------------------------------
    // rayTestSingle / rayTestSingleInternal
    // ---------------------------------------------------------------------------------------

    /// rayTestSingle performs a raycast call and calls the resultCallback. It is used internally by
    // rayTest.
    public static void rayTestSingle(
            btTransform rayFromTrans,
            btTransform rayToTrans,
            btCollisionObject collisionObject,
            btCollisionShape collisionShape,
            btTransform colObjWorldTransform,
            RayResultCallback resultCallback) {
        btCollisionObjectWrapper colObWrap =
                new btCollisionObjectWrapper(
                        null, collisionShape, collisionObject, colObjWorldTransform, -1, -1);
        btCollisionWorld.rayTestSingleInternal(rayFromTrans, rayToTrans, colObWrap, resultCallback);
    }

    /**
     * Local struct {@code BridgeTriangleRaycastCallback} of rayTestSingleInternal. C++ has two
     * textually identical local structs (TRIANGLE_MESH branch with {@code const btConcaveShape*
     * m_triangleMesh}, generic-concave branch with {@code btConcaveShape*}); one Java class serves
     * both.
     */
    public static class BridgeTriangleRaycastCallback extends btTriangleRaycastCallback {
        public RayResultCallback m_resultCallback;
        public btCollisionObject m_collisionObject;
        public btConcaveShape m_triangleMesh;

        public final btTransform m_colObjWorldTransform = new btTransform();

        public BridgeTriangleRaycastCallback(
                btVector3 from,
                btVector3 to,
                RayResultCallback resultCallback,
                btCollisionObject collisionObject,
                btConcaveShape triangleMesh,
                btTransform colObjWorldTransform) {
            // @BP Mod
            super(from, to, resultCallback.m_flags);
            m_resultCallback = resultCallback;
            m_collisionObject = collisionObject;
            m_triangleMesh = triangleMesh;
            m_colObjWorldTransform.set(colObjWorldTransform);
        }

        @Override
        public double reportHit(
                btVector3 hitNormalLocal, double hitFraction, int partId, int triangleIndex) {
            LocalShapeInfo shapeInfo = new LocalShapeInfo();
            shapeInfo.m_shapePart = partId;
            shapeInfo.m_triangleIndex = triangleIndex;

            btVector3 hitNormalWorld = m_colObjWorldTransform.getBasis().mul(hitNormalLocal);

            LocalRayResult rayResult =
                    new LocalRayResult(m_collisionObject, shapeInfo, hitNormalWorld, hitFraction);

            boolean normalInWorldSpace = true;
            return m_resultCallback.addSingleResult(rayResult, normalInWorldSpace);
        }
    }

    /** Local struct {@code LocalInfoAdder2} of rayTestSingleInternal. */
    public static class LocalInfoAdder2 extends RayResultCallback {
        public RayResultCallback m_userCallback;
        public int m_i;

        public LocalInfoAdder2(int i, RayResultCallback user) {
            m_userCallback = user;
            m_i = i;
            m_closestHitFraction = m_userCallback.m_closestHitFraction;
            m_flags = m_userCallback.m_flags;
        }

        @Override
        public boolean needsCollision(btBroadphaseProxy p) {
            return m_userCallback.needsCollision(p);
        }

        @Override
        public double addSingleResult(LocalRayResult r, boolean b) {
            LocalShapeInfo shapeInfo = new LocalShapeInfo();
            shapeInfo.m_shapePart = -1;
            shapeInfo.m_triangleIndex = m_i;
            if (r.m_localShapeInfo == null) r.m_localShapeInfo = shapeInfo;

            final double result = m_userCallback.addSingleResult(r, b);
            m_closestHitFraction = m_userCallback.m_closestHitFraction;
            return result;
        }
    }

    /** Local struct {@code RayTester : btDbvt::ICollide} of rayTestSingleInternal. */
    public static class RayTester extends btDbvt.ICollide {
        public btCollisionObject m_collisionObject;
        public btCompoundShape m_compoundShape;
        // const btTransform& members: stored references
        public final btTransform m_colObjWorldTransform;
        public final btTransform m_rayFromTrans;
        public final btTransform m_rayToTrans;
        public final RayResultCallback m_resultCallback;

        public RayTester(
                btCollisionObject collisionObject,
                btCompoundShape compoundShape,
                btTransform colObjWorldTransform,
                btTransform rayFromTrans,
                btTransform rayToTrans,
                RayResultCallback resultCallback) {
            m_collisionObject = collisionObject;
            m_compoundShape = compoundShape;
            m_colObjWorldTransform = colObjWorldTransform;
            m_rayFromTrans = rayFromTrans;
            m_rayToTrans = rayToTrans;
            m_resultCallback = resultCallback;
        }

        public void ProcessLeaf(int i) {
            btCollisionShape childCollisionShape = m_compoundShape.getChildShape(i);
            btTransform childTrans = m_compoundShape.getChildTransform(i);
            btTransform childWorldTrans = m_colObjWorldTransform.mul(childTrans);

            btCollisionObjectWrapper tmpOb =
                    new btCollisionObjectWrapper(
                            null, childCollisionShape, m_collisionObject, childWorldTrans, -1, i);
            // replace collision shape so that callback can determine the triangle

            LocalInfoAdder2 my_cb = new LocalInfoAdder2(i, m_resultCallback);

            rayTestSingleInternal(m_rayFromTrans, m_rayToTrans, tmpOb, my_cb);
        }

        @Override
        public void Process(btDbvtNode leaf) {
            ProcessLeaf(leaf.dataAsInt);
        }
    }

    public static void rayTestSingleInternal(
            btTransform rayFromTrans,
            btTransform rayToTrans,
            btCollisionObjectWrapper collisionObjectWrap,
            RayResultCallback resultCallback) {
        btSphereShape pointShape = new btSphereShape(0.0);
        pointShape.setMargin((double) 0.f);
        final btConvexShape castShape = pointShape;
        final btCollisionShape collisionShape = collisionObjectWrap.getCollisionShape();
        final btTransform colObjWorldTransform = collisionObjectWrap.getWorldTransform();

        if (collisionShape.isConvex()) {
            //		BT_PROFILE("rayTestConvex");
            btConvexCast.CastResult castResult = new btConvexCast.CastResult();
            castResult.m_fraction = resultCallback.m_closestHitFraction;

            btConvexShape convexShape = (btConvexShape) collisionShape;
            btVoronoiSimplexSolver simplexSolver = new btVoronoiSimplexSolver();
            btSubsimplexConvexCast subSimplexConvexCaster =
                    new btSubsimplexConvexCast(castShape, convexShape, simplexSolver);

            btGjkConvexCast gjkConvexCaster =
                    new btGjkConvexCast(castShape, convexShape, simplexSolver);

            // btContinuousConvexCollision convexCaster(castShape,convexShape,&simplexSolver,0);
            btConvexCast convexCasterPtr = null;
            if ((resultCallback.m_flags
                            & btTriangleRaycastCallback.kF_UseSubSimplexConvexCastRaytest)
                    != 0) convexCasterPtr = subSimplexConvexCaster;
            else convexCasterPtr = gjkConvexCaster;

            btConvexCast convexCaster = convexCasterPtr;

            if (convexCaster.calcTimeOfImpact(
                    rayFromTrans,
                    rayToTrans,
                    colObjWorldTransform,
                    colObjWorldTransform,
                    castResult)) {
                // add hit
                if (castResult.m_normal.length2() > 0.0001) {
                    if (castResult.m_fraction < resultCallback.m_closestHitFraction) {
                        castResult.m_normal.normalize();
                        LocalRayResult localRayResult =
                                new LocalRayResult(
                                        collisionObjectWrap.getCollisionObject(),
                                        null,
                                        castResult.m_normal,
                                        castResult.m_fraction);

                        boolean normalInWorldSpace = true;
                        resultCallback.addSingleResult(localRayResult, normalInWorldSpace);
                    }
                }
            }
        } else {
            if (collisionShape.isConcave()) {

                btTransform worldTocollisionObject = colObjWorldTransform.inverse();
                btVector3 rayFromLocal = worldTocollisionObject.transform(rayFromTrans.getOrigin());
                btVector3 rayToLocal = worldTocollisionObject.transform(rayToTrans.getOrigin());

                //			BT_PROFILE("rayTestConcave");
                if (collisionShape.getShapeType() == TRIANGLE_MESH_SHAPE_PROXYTYPE) {
                    /// optimized version for btBvhTriangleMeshShape
                    btBvhTriangleMeshShape triangleMesh = (btBvhTriangleMeshShape) collisionShape;

                    BridgeTriangleRaycastCallback rcb =
                            new BridgeTriangleRaycastCallback(
                                    rayFromLocal,
                                    rayToLocal,
                                    resultCallback,
                                    collisionObjectWrap.getCollisionObject(),
                                    triangleMesh,
                                    colObjWorldTransform);
                    rcb.m_hitFraction = resultCallback.m_closestHitFraction;
                    triangleMesh.performRaycast(rcb, rayFromLocal, rayToLocal);
                } else if (collisionShape.getShapeType() == GIMPACT_SHAPE_PROXYTYPE) {
                    // btGImpactMeshShape::processAllTrianglesRay: GIMPACT is not linked into
                    // PZBullet, so no shape of this type can exist.
                    throw new UnsupportedOperationException(
                            "GIMPACT_SHAPE_PROXYTYPE: GIMPACT is not linked in PZBullet");
                } else {
                    // generic (slower) case
                    btConcaveShape concaveShape = (btConcaveShape) collisionShape;

                    btTransform worldTocollisionObject2 = colObjWorldTransform.inverse();

                    btVector3 rayFromLocal2 =
                            worldTocollisionObject2.transform(rayFromTrans.getOrigin());
                    btVector3 rayToLocal2 =
                            worldTocollisionObject2.transform(rayToTrans.getOrigin());

                    // ConvexCast::CastResult

                    BridgeTriangleRaycastCallback rcb =
                            new BridgeTriangleRaycastCallback(
                                    rayFromLocal2,
                                    rayToLocal2,
                                    resultCallback,
                                    collisionObjectWrap.getCollisionObject(),
                                    concaveShape,
                                    colObjWorldTransform);
                    rcb.m_hitFraction = resultCallback.m_closestHitFraction;

                    btVector3 rayAabbMinLocal = new btVector3(rayFromLocal2);
                    rayAabbMinLocal.setMin(rayToLocal2);
                    btVector3 rayAabbMaxLocal = new btVector3(rayFromLocal2);
                    rayAabbMaxLocal.setMax(rayToLocal2);

                    concaveShape.processAllTriangles(rcb, rayAabbMinLocal, rayAabbMaxLocal);
                }
            } else {
                //			BT_PROFILE("rayTestCompound");
                if (collisionShape.isCompound()) {
                    final btCompoundShape compoundShape = (btCompoundShape) collisionShape;
                    final btDbvt dbvt = compoundShape.getDynamicAabbTree();

                    RayTester rayCB =
                            new RayTester(
                                    collisionObjectWrap.getCollisionObject(),
                                    compoundShape,
                                    colObjWorldTransform,
                                    rayFromTrans,
                                    rayToTrans,
                                    resultCallback);
                    if (dbvt != null) {
                        btVector3 localRayFrom =
                                colObjWorldTransform.inverseTimes(rayFromTrans).getOrigin();
                        btVector3 localRayTo =
                                colObjWorldTransform.inverseTimes(rayToTrans).getOrigin();
                        btDbvt.rayTest(dbvt.m_root, localRayFrom, localRayTo, rayCB);
                    } else {
                        for (int i = 0, n = compoundShape.getNumChildShapes(); i < n; ++i) {
                            rayCB.ProcessLeaf(i);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // objectQuerySingle / objectQuerySingleInternal
    // ---------------------------------------------------------------------------------------

    /// objectQuerySingle performs a collision detection query and calls the resultCallback. It is
    // used internally by rayTest.
    public static void objectQuerySingle(
            btConvexShape castShape,
            btTransform convexFromTrans,
            btTransform convexToTrans,
            btCollisionObject collisionObject,
            btCollisionShape collisionShape,
            btTransform colObjWorldTransform,
            ConvexResultCallback resultCallback,
            double allowedPenetration) {
        btCollisionObjectWrapper tmpOb =
                new btCollisionObjectWrapper(
                        null, collisionShape, collisionObject, colObjWorldTransform, -1, -1);
        btCollisionWorld.objectQuerySingleInternal(
                castShape,
                convexFromTrans,
                convexToTrans,
                tmpOb,
                resultCallback,
                allowedPenetration);
    }

    /**
     * Local struct {@code BridgeTriangleConvexcastCallback} of objectQuerySingleInternal, the
     * TRIANGLE_MESH_SHAPE_PROXYTYPE branch (reports with normalInWorldSpace = true).
     */
    public static class BridgeTriangleConvexcastCallback extends btTriangleConvexcastCallback {
        public ConvexResultCallback m_resultCallback;
        public btCollisionObject m_collisionObject;
        public btTriangleMeshShape m_triangleMesh;

        public BridgeTriangleConvexcastCallback(
                btConvexShape castShape,
                btTransform from,
                btTransform to,
                ConvexResultCallback resultCallback,
                btCollisionObject collisionObject,
                btTriangleMeshShape triangleMesh,
                btTransform triangleToWorld) {
            super(castShape, from, to, triangleToWorld, triangleMesh.getMargin());
            m_resultCallback = resultCallback;
            m_collisionObject = collisionObject;
            m_triangleMesh = triangleMesh;
        }

        @Override
        public double reportHit(
                btVector3 hitNormalLocal,
                btVector3 hitPointLocal,
                double hitFraction,
                int partId,
                int triangleIndex) {
            LocalShapeInfo shapeInfo = new LocalShapeInfo();
            shapeInfo.m_shapePart = partId;
            shapeInfo.m_triangleIndex = triangleIndex;
            if (hitFraction <= m_resultCallback.m_closestHitFraction) {

                LocalConvexResult convexResult =
                        new LocalConvexResult(
                                m_collisionObject,
                                shapeInfo,
                                hitNormalLocal,
                                hitPointLocal,
                                hitFraction);

                boolean normalInWorldSpace = true;

                return m_resultCallback.addSingleResult(convexResult, normalInWorldSpace);
            }
            return hitFraction;
        }
    }

    /**
     * Local struct {@code BridgeTriangleConvexcastCallback} of objectQuerySingleInternal, the
     * generic concave branch (reports with normalInWorldSpace = false). Renamed in Java because it
     * differs from the TRIANGLE_MESH variant.
     */
    public static class BridgeTriangleConvexcastCallbackConcave
            extends btTriangleConvexcastCallback {
        public ConvexResultCallback m_resultCallback;
        public btCollisionObject m_collisionObject;
        public btConcaveShape m_triangleMesh;

        public BridgeTriangleConvexcastCallbackConcave(
                btConvexShape castShape,
                btTransform from,
                btTransform to,
                ConvexResultCallback resultCallback,
                btCollisionObject collisionObject,
                btConcaveShape triangleMesh,
                btTransform triangleToWorld) {
            super(castShape, from, to, triangleToWorld, triangleMesh.getMargin());
            m_resultCallback = resultCallback;
            m_collisionObject = collisionObject;
            m_triangleMesh = triangleMesh;
        }

        @Override
        public double reportHit(
                btVector3 hitNormalLocal,
                btVector3 hitPointLocal,
                double hitFraction,
                int partId,
                int triangleIndex) {
            LocalShapeInfo shapeInfo = new LocalShapeInfo();
            shapeInfo.m_shapePart = partId;
            shapeInfo.m_triangleIndex = triangleIndex;
            if (hitFraction <= m_resultCallback.m_closestHitFraction) {

                LocalConvexResult convexResult =
                        new LocalConvexResult(
                                m_collisionObject,
                                shapeInfo,
                                hitNormalLocal,
                                hitPointLocal,
                                hitFraction);

                boolean normalInWorldSpace = false;

                return m_resultCallback.addSingleResult(convexResult, normalInWorldSpace);
            }
            return hitFraction;
        }
    }

    /** Local struct {@code LocalInfoAdder} of objectQuerySingleInternal. */
    public static class LocalInfoAdder extends ConvexResultCallback {
        public ConvexResultCallback m_userCallback;
        public int m_i;

        public LocalInfoAdder(int i, ConvexResultCallback user) {
            m_userCallback = user;
            m_i = i;
            m_closestHitFraction = m_userCallback.m_closestHitFraction;
        }

        @Override
        public boolean needsCollision(btBroadphaseProxy p) {
            return m_userCallback.needsCollision(p);
        }

        @Override
        public double addSingleResult(LocalConvexResult r, boolean b) {
            LocalShapeInfo shapeInfo = new LocalShapeInfo();
            shapeInfo.m_shapePart = -1;
            shapeInfo.m_triangleIndex = m_i;
            if (r.m_localShapeInfo == null) r.m_localShapeInfo = shapeInfo;
            final double result = m_userCallback.addSingleResult(r, b);
            m_closestHitFraction = m_userCallback.m_closestHitFraction;
            return result;
        }
    }

    public static void objectQuerySingleInternal(
            btConvexShape castShape,
            btTransform convexFromTrans,
            btTransform convexToTrans,
            btCollisionObjectWrapper colObjWrap,
            ConvexResultCallback resultCallback,
            double allowedPenetration) {
        final btCollisionShape collisionShape = colObjWrap.getCollisionShape();
        final btTransform colObjWorldTransform = colObjWrap.getWorldTransform();

        if (collisionShape.isConvex()) {
            // BT_PROFILE("convexSweepConvex");
            btConvexCast.CastResult castResult = new btConvexCast.CastResult();
            castResult.m_allowedPenetration = allowedPenetration;
            castResult.m_fraction = resultCallback.m_closestHitFraction; // btScalar(1.);//??

            btConvexShape convexShape = (btConvexShape) collisionShape;
            btVoronoiSimplexSolver simplexSolver = new btVoronoiSimplexSolver();
            btGjkEpaPenetrationDepthSolver gjkEpaPenetrationSolver =
                    new btGjkEpaPenetrationDepthSolver();

            btContinuousConvexCollision convexCaster1 =
                    new btContinuousConvexCollision(
                            castShape, convexShape, simplexSolver, gjkEpaPenetrationSolver);
            // btGjkConvexCast convexCaster2(castShape,convexShape,&simplexSolver);
            // btSubsimplexConvexCast convexCaster3(castShape,convexShape,&simplexSolver);

            btConvexCast castPtr = convexCaster1;

            if (castPtr.calcTimeOfImpact(
                    convexFromTrans,
                    convexToTrans,
                    colObjWorldTransform,
                    colObjWorldTransform,
                    castResult)) {
                // add hit
                if (castResult.m_normal.length2() > 0.0001) {
                    if (castResult.m_fraction < resultCallback.m_closestHitFraction) {
                        castResult.m_normal.normalize();
                        LocalConvexResult localConvexResult =
                                new LocalConvexResult(
                                        colObjWrap.getCollisionObject(),
                                        null,
                                        castResult.m_normal,
                                        castResult.m_hitPoint,
                                        castResult.m_fraction);

                        boolean normalInWorldSpace = true;
                        resultCallback.addSingleResult(localConvexResult, normalInWorldSpace);
                    }
                }
            }
        } else {
            if (collisionShape.isConcave()) {
                if (collisionShape.getShapeType() == TRIANGLE_MESH_SHAPE_PROXYTYPE) {
                    // BT_PROFILE("convexSweepbtBvhTriangleMesh");
                    btBvhTriangleMeshShape triangleMesh = (btBvhTriangleMeshShape) collisionShape;
                    btTransform worldTocollisionObject = colObjWorldTransform.inverse();
                    btVector3 convexFromLocal =
                            worldTocollisionObject.transform(convexFromTrans.getOrigin());
                    btVector3 convexToLocal =
                            worldTocollisionObject.transform(convexToTrans.getOrigin());
                    // rotation of box in local mesh space = MeshRotation^-1 * ConvexToRotation
                    btTransform rotationXform =
                            new btTransform(
                                    worldTocollisionObject
                                            .getBasis()
                                            .mul(convexToTrans.getBasis()));

                    BridgeTriangleConvexcastCallback tccb =
                            new BridgeTriangleConvexcastCallback(
                                    castShape,
                                    convexFromTrans,
                                    convexToTrans,
                                    resultCallback,
                                    colObjWrap.getCollisionObject(),
                                    triangleMesh,
                                    colObjWorldTransform);
                    tccb.m_hitFraction = resultCallback.m_closestHitFraction;
                    tccb.m_allowedPenetration = allowedPenetration;
                    btVector3 boxMinLocal = new btVector3(), boxMaxLocal = new btVector3();
                    castShape.getAabb(rotationXform, boxMinLocal, boxMaxLocal);
                    triangleMesh.performConvexcast(
                            tccb, convexFromLocal, convexToLocal, boxMinLocal, boxMaxLocal);
                } else {
                    if (collisionShape.getShapeType() == STATIC_PLANE_PROXYTYPE) {
                        btConvexCast.CastResult castResult = new btConvexCast.CastResult();
                        castResult.m_allowedPenetration = allowedPenetration;
                        castResult.m_fraction = resultCallback.m_closestHitFraction;
                        btStaticPlaneShape planeShape = (btStaticPlaneShape) collisionShape;
                        btContinuousConvexCollision convexCaster1 =
                                new btContinuousConvexCollision(castShape, planeShape);
                        btConvexCast castPtr = convexCaster1;

                        if (castPtr.calcTimeOfImpact(
                                convexFromTrans,
                                convexToTrans,
                                colObjWorldTransform,
                                colObjWorldTransform,
                                castResult)) {
                            // add hit
                            if (castResult.m_normal.length2() > 0.0001) {
                                if (castResult.m_fraction < resultCallback.m_closestHitFraction) {
                                    castResult.m_normal.normalize();
                                    LocalConvexResult localConvexResult =
                                            new LocalConvexResult(
                                                    colObjWrap.getCollisionObject(),
                                                    null,
                                                    castResult.m_normal,
                                                    castResult.m_hitPoint,
                                                    castResult.m_fraction);

                                    boolean normalInWorldSpace = true;
                                    resultCallback.addSingleResult(
                                            localConvexResult, normalInWorldSpace);
                                }
                            }
                        }

                    } else {
                        // BT_PROFILE("convexSweepConcave");
                        btConcaveShape concaveShape = (btConcaveShape) collisionShape;
                        btTransform worldTocollisionObject = colObjWorldTransform.inverse();
                        btVector3 convexFromLocal =
                                worldTocollisionObject.transform(convexFromTrans.getOrigin());
                        btVector3 convexToLocal =
                                worldTocollisionObject.transform(convexToTrans.getOrigin());
                        // rotation of box in local mesh space = MeshRotation^-1 * ConvexToRotation
                        btTransform rotationXform =
                                new btTransform(
                                        worldTocollisionObject
                                                .getBasis()
                                                .mul(convexToTrans.getBasis()));

                        BridgeTriangleConvexcastCallbackConcave tccb =
                                new BridgeTriangleConvexcastCallbackConcave(
                                        castShape,
                                        convexFromTrans,
                                        convexToTrans,
                                        resultCallback,
                                        colObjWrap.getCollisionObject(),
                                        concaveShape,
                                        colObjWorldTransform);
                        tccb.m_hitFraction = resultCallback.m_closestHitFraction;
                        tccb.m_allowedPenetration = allowedPenetration;
                        btVector3 boxMinLocal = new btVector3(), boxMaxLocal = new btVector3();
                        castShape.getAabb(rotationXform, boxMinLocal, boxMaxLocal);

                        btVector3 rayAabbMinLocal = new btVector3(convexFromLocal);
                        rayAabbMinLocal.setMin(convexToLocal);
                        btVector3 rayAabbMaxLocal = new btVector3(convexFromLocal);
                        rayAabbMaxLocal.setMax(convexToLocal);
                        rayAabbMinLocal.addLocal(boxMinLocal);
                        rayAabbMaxLocal.addLocal(boxMaxLocal);
                        concaveShape.processAllTriangles(tccb, rayAabbMinLocal, rayAabbMaxLocal);
                    }
                }
            } else {
                /// @todo : use AABB tree or other BVH acceleration structure!
                if (collisionShape.isCompound()) {
                    try (CProfileSample __profile = new CProfileSample("convexSweepCompound")) {
                        final btCompoundShape compoundShape = (btCompoundShape) collisionShape;
                        int i = 0;
                        for (i = 0; i < compoundShape.getNumChildShapes(); i++) {
                            btTransform childTrans =
                                    new btTransform(compoundShape.getChildTransform(i));
                            final btCollisionShape childCollisionShape =
                                    compoundShape.getChildShape(i);
                            btTransform childWorldTrans = colObjWorldTransform.mul(childTrans);

                            LocalInfoAdder my_cb = new LocalInfoAdder(i, resultCallback);

                            btCollisionObjectWrapper tmpObj =
                                    new btCollisionObjectWrapper(
                                            colObjWrap,
                                            childCollisionShape,
                                            colObjWrap.getCollisionObject(),
                                            childWorldTrans,
                                            -1,
                                            i);

                            objectQuerySingleInternal(
                                    castShape,
                                    convexFromTrans,
                                    convexToTrans,
                                    tmpObj,
                                    my_cb,
                                    allowedPenetration);
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // rayTest
    // ---------------------------------------------------------------------------------------

    /** struct btSingleRayCallback (file-local in btCollisionWorld.cpp) */
    public static class btSingleRayCallback extends btBroadphaseRayCallback {
        public final btVector3 m_rayFromWorld = new btVector3();
        public final btVector3 m_rayToWorld = new btVector3();
        public final btTransform m_rayFromTrans = new btTransform();
        public final btTransform m_rayToTrans = new btTransform();
        public final btVector3 m_hitNormal = new btVector3();

        public final btCollisionWorld m_world;
        public final RayResultCallback m_resultCallback;

        public btSingleRayCallback(
                btVector3 rayFromWorld,
                btVector3 rayToWorld,
                btCollisionWorld world,
                RayResultCallback resultCallback) {
            m_rayFromWorld.set(rayFromWorld);
            m_rayToWorld.set(rayToWorld);
            m_world = world;
            m_resultCallback = resultCallback;

            m_rayFromTrans.setIdentity();
            m_rayFromTrans.setOrigin(m_rayFromWorld);
            m_rayToTrans.setIdentity();
            m_rayToTrans.setOrigin(m_rayToWorld);

            btVector3 rayDir = rayToWorld.sub(rayFromWorld);

            rayDir.normalize();
            /// what about division by zero? --> just set rayDirection[i] to INF/BT_LARGE_FLOAT
            m_rayDirectionInverse.setValue(
                    rayDir.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(0),
                    rayDir.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(1),
                    rayDir.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(2));
            m_signs[0] = m_rayDirectionInverse.get(0) < 0.0 ? 1 : 0;
            m_signs[1] = m_rayDirectionInverse.get(1) < 0.0 ? 1 : 0;
            m_signs[2] = m_rayDirectionInverse.get(2) < 0.0 ? 1 : 0;

            m_lambda_max = rayDir.dot(m_rayToWorld.sub(m_rayFromWorld));
        }

        @Override
        public boolean process(btBroadphaseProxy proxy) {
            /// terminate further ray tests, once the closestHitFraction reached zero
            if (m_resultCallback.m_closestHitFraction == (double) 0.f) return false;

            btCollisionObject collisionObject = (btCollisionObject) proxy.m_clientObject;

            // only perform raycast if filterMask matches
            if (m_resultCallback.needsCollision(collisionObject.getBroadphaseHandle())) {
                // culling already done by broadphase
                {
                    btCollisionWorld.rayTestSingle(
                            m_rayFromTrans,
                            m_rayToTrans,
                            collisionObject,
                            collisionObject.getCollisionShape(),
                            collisionObject.getWorldTransform(),
                            m_resultCallback);
                }
            }
            return true;
        }
    }

    /// rayTest performs a raycast on all objects in the btCollisionWorld, and calls the
    // resultCallback This allows for several queries: first hit, all hits, any hit, dependent on
    // the value returned by the callback.
    public void rayTest(
            btVector3 rayFromWorld, btVector3 rayToWorld, RayResultCallback resultCallback) {
        // BT_PROFILE("rayTest");
        /// use the broadphase to accelerate the search for objects, based on their aabb
        /// and for each object with ray-aabb overlap, perform an exact ray test
        btSingleRayCallback rayCB =
                new btSingleRayCallback(rayFromWorld, rayToWorld, this, resultCallback);

        m_broadphasePairCache.rayTest(rayFromWorld, rayToWorld, rayCB);
    }

    // ---------------------------------------------------------------------------------------
    // convexSweepTest
    // ---------------------------------------------------------------------------------------

    /** struct btSingleSweepCallback (file-local in btCollisionWorld.cpp) */
    public static class btSingleSweepCallback extends btBroadphaseRayCallback {
        public final btTransform m_convexFromTrans = new btTransform();
        public final btTransform m_convexToTrans = new btTransform();
        public final btVector3 m_hitNormal = new btVector3();
        public final btCollisionWorld m_world;
        public final ConvexResultCallback m_resultCallback;
        public double m_allowedCcdPenetration;
        public final btConvexShape m_castShape;

        public btSingleSweepCallback(
                btConvexShape castShape,
                btTransform convexFromTrans,
                btTransform convexToTrans,
                btCollisionWorld world,
                ConvexResultCallback resultCallback,
                double allowedPenetration) {
            m_convexFromTrans.set(convexFromTrans);
            m_convexToTrans.set(convexToTrans);
            m_world = world;
            m_resultCallback = resultCallback;
            m_allowedCcdPenetration = allowedPenetration;
            m_castShape = castShape;

            btVector3 unnormalizedRayDir =
                    m_convexToTrans.getOrigin().sub(m_convexFromTrans.getOrigin());
            btVector3 rayDir = unnormalizedRayDir.normalized();
            /// what about division by zero? --> just set rayDirection[i] to INF/BT_LARGE_FLOAT
            m_rayDirectionInverse.setValue(
                    rayDir.get(0) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(0),
                    rayDir.get(1) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(1),
                    rayDir.get(2) == 0.0 ? btScalar.BT_LARGE_FLOAT : 1.0 / rayDir.get(2));
            m_signs[0] = m_rayDirectionInverse.get(0) < 0.0 ? 1 : 0;
            m_signs[1] = m_rayDirectionInverse.get(1) < 0.0 ? 1 : 0;
            m_signs[2] = m_rayDirectionInverse.get(2) < 0.0 ? 1 : 0;

            m_lambda_max = rayDir.dot(unnormalizedRayDir);
        }

        @Override
        public boolean process(btBroadphaseProxy proxy) {
            /// terminate further convex sweep tests, once the closestHitFraction reached zero
            if (m_resultCallback.m_closestHitFraction == (double) 0.f) return false;

            btCollisionObject collisionObject = (btCollisionObject) proxy.m_clientObject;

            // only perform raycast if filterMask matches
            if (m_resultCallback.needsCollision(collisionObject.getBroadphaseHandle())) {
                btCollisionWorld.objectQuerySingle(
                        m_castShape,
                        m_convexFromTrans,
                        m_convexToTrans,
                        collisionObject,
                        collisionObject.getCollisionShape(),
                        collisionObject.getWorldTransform(),
                        m_resultCallback,
                        m_allowedCcdPenetration);
            }

            return true;
        }
    }

    /** {@code allowedCcdPenetration = btScalar(0.)} */
    public final void convexSweepTest(
            btConvexShape castShape,
            btTransform from,
            btTransform to,
            ConvexResultCallback resultCallback) {
        convexSweepTest(castShape, from, to, resultCallback, 0.);
    }

    /// convexTest performs a swept convex cast on all objects in the btCollisionWorld, and calls
    // the resultCallback This allows for several queries: first hit, all hits, any hit, dependent
    // on the value return by the callback.
    public void convexSweepTest(
            btConvexShape castShape,
            btTransform convexFromWorld,
            btTransform convexToWorld,
            ConvexResultCallback resultCallback,
            double allowedCcdPenetration) {
        try (CProfileSample __profile = new CProfileSample("convexSweepTest")) {
            /// use the broadphase to accelerate the search for objects, based on their aabb
            /// and for each object with ray-aabb overlap, perform an exact ray test
            /// unfortunately the implementation for rayTest and convexSweepTest duplicated,
            // albeit practically identical

            btTransform convexFromTrans = new btTransform(), convexToTrans = new btTransform();
            convexFromTrans.set(convexFromWorld);
            convexToTrans.set(convexToWorld);
            btVector3 castShapeAabbMin = new btVector3(), castShapeAabbMax = new btVector3();
            /* Compute AABB that encompasses angular movement */
            {
                btVector3 linVel = new btVector3(), angVel = new btVector3();
                btTransformUtil.calculateVelocity(
                        convexFromTrans, convexToTrans, (double) 1.0f, linVel, angVel);
                btVector3 zeroLinVel = new btVector3();
                zeroLinVel.setValue(0, 0, 0);
                btTransform R = new btTransform();
                R.setIdentity();
                R.setRotation(convexFromTrans.getRotation());
                castShape.calculateTemporalAabb(
                        R, zeroLinVel, angVel, (double) 1.0f, castShapeAabbMin, castShapeAabbMax);
            }

            btSingleSweepCallback convexCB =
                    new btSingleSweepCallback(
                            castShape,
                            convexFromWorld,
                            convexToWorld,
                            this,
                            resultCallback,
                            allowedCcdPenetration);

            m_broadphasePairCache.rayTest(
                    convexFromTrans.getOrigin(),
                    convexToTrans.getOrigin(),
                    convexCB,
                    castShapeAabbMin,
                    castShapeAabbMax);
        }
    }

    // ---------------------------------------------------------------------------------------
    // contactTest / contactPairTest
    // ---------------------------------------------------------------------------------------

    /** struct btBridgedManifoldResult (file-local in btCollisionWorld.cpp) */
    public static class btBridgedManifoldResult extends btManifoldResult {
        public final ContactResultCallback m_resultCallback;

        public btBridgedManifoldResult(
                btCollisionObjectWrapper obj0Wrap,
                btCollisionObjectWrapper obj1Wrap,
                ContactResultCallback resultCallback) {
            super(obj0Wrap, obj1Wrap);
            m_resultCallback = resultCallback;
        }

        @Override
        public void addContactPoint(
                btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {
            boolean isSwapped = m_manifoldPtr.getBody0() != m_body0Wrap.getCollisionObject();
            btVector3 pointA = pointInWorld.add(normalOnBInWorld.mul(depth));
            btVector3 localA;
            btVector3 localB;
            if (isSwapped) {
                localA = m_body1Wrap.getCollisionObject().getWorldTransform().invXform(pointA);
                localB =
                        m_body0Wrap.getCollisionObject().getWorldTransform().invXform(pointInWorld);
            } else {
                localA = m_body0Wrap.getCollisionObject().getWorldTransform().invXform(pointA);
                localB =
                        m_body1Wrap.getCollisionObject().getWorldTransform().invXform(pointInWorld);
            }

            btManifoldPoint newPt = new btManifoldPoint(localA, localB, normalOnBInWorld, depth);
            newPt.m_positionWorldOnA.set(pointA);
            newPt.m_positionWorldOnB.set(pointInWorld);

            // BP mod, store contact triangles.
            if (isSwapped) {
                newPt.m_partId0 = m_partId1;
                newPt.m_partId1 = m_partId0;
                newPt.m_index0 = m_index1;
                newPt.m_index1 = m_index0;
            } else {
                newPt.m_partId0 = m_partId0;
                newPt.m_partId1 = m_partId1;
                newPt.m_index0 = m_index0;
                newPt.m_index1 = m_index1;
            }

            // experimental feature info, for per-triangle material etc.
            btCollisionObjectWrapper obj0Wrap = isSwapped ? m_body1Wrap : m_body0Wrap;
            btCollisionObjectWrapper obj1Wrap = isSwapped ? m_body0Wrap : m_body1Wrap;
            m_resultCallback.addSingleResult(
                    newPt,
                    obj0Wrap,
                    newPt.m_partId0,
                    newPt.m_index0,
                    obj1Wrap,
                    newPt.m_partId1,
                    newPt.m_index1);
        }
    }

    /** struct btSingleContactCallback (file-local in btCollisionWorld.cpp) */
    public static class btSingleContactCallback extends btBroadphaseAabbCallback {
        public btCollisionObject m_collisionObject;
        public btCollisionWorld m_world;
        public final ContactResultCallback m_resultCallback;

        public btSingleContactCallback(
                btCollisionObject collisionObject,
                btCollisionWorld world,
                ContactResultCallback resultCallback) {
            m_collisionObject = collisionObject;
            m_world = world;
            m_resultCallback = resultCallback;
        }

        @Override
        public boolean process(btBroadphaseProxy proxy) {
            btCollisionObject collisionObject = (btCollisionObject) proxy.m_clientObject;
            if (collisionObject == m_collisionObject) return true;

            // only perform raycast if filterMask matches
            if (m_resultCallback.needsCollision(collisionObject.getBroadphaseHandle())) {
                btCollisionObjectWrapper ob0 =
                        new btCollisionObjectWrapper(
                                null,
                                m_collisionObject.getCollisionShape(),
                                m_collisionObject,
                                m_collisionObject.getWorldTransform(),
                                -1,
                                -1);
                btCollisionObjectWrapper ob1 =
                        new btCollisionObjectWrapper(
                                null,
                                collisionObject.getCollisionShape(),
                                collisionObject,
                                collisionObject.getWorldTransform(),
                                -1,
                                -1);

                btCollisionAlgorithm algorithm = m_world.getDispatcher().findAlgorithm(ob0, ob1);
                if (algorithm != null) {
                    btBridgedManifoldResult contactPointResult =
                            new btBridgedManifoldResult(ob0, ob1, m_resultCallback);
                    // discrete collision detection query

                    algorithm.processCollision(
                            ob0, ob1, m_world.getDispatchInfo(), contactPointResult);

                    algorithm.destroy();
                    m_world.getDispatcher().freeCollisionAlgorithm(algorithm.m_allocAddress);
                }
            }
            return true;
        }
    }

    /// contactTest performs a discrete collision test against all objects in the
    // btCollisionWorld, and calls the resultCallback. it reports one or more contact points for
    // every overlapping object (including the one with deepest penetration)
    public void contactTest(btCollisionObject colObj, ContactResultCallback resultCallback) {
        btVector3 aabbMin = new btVector3(), aabbMax = new btVector3();
        colObj.getCollisionShape().getAabb(colObj.getWorldTransform(), aabbMin, aabbMax);
        btSingleContactCallback contactCB =
                new btSingleContactCallback(colObj, this, resultCallback);

        m_broadphasePairCache.aabbTest(aabbMin, aabbMax, contactCB);
    }

    /// contactTest performs a discrete collision test between two collision objects and calls the
    // resultCallback if overlap if detected. it reports one or more contact points (including the
    // one with deepest penetration)
    public void contactPairTest(
            btCollisionObject colObjA,
            btCollisionObject colObjB,
            ContactResultCallback resultCallback) {
        btCollisionObjectWrapper obA =
                new btCollisionObjectWrapper(
                        null,
                        colObjA.getCollisionShape(),
                        colObjA,
                        colObjA.getWorldTransform(),
                        -1,
                        -1);
        btCollisionObjectWrapper obB =
                new btCollisionObjectWrapper(
                        null,
                        colObjB.getCollisionShape(),
                        colObjB,
                        colObjB.getWorldTransform(),
                        -1,
                        -1);

        btCollisionAlgorithm algorithm = getDispatcher().findAlgorithm(obA, obB);
        if (algorithm != null) {
            btBridgedManifoldResult contactPointResult =
                    new btBridgedManifoldResult(obA, obB, resultCallback);
            // discrete collision detection query
            algorithm.processCollision(obA, obB, getDispatchInfo(), contactPointResult);

            algorithm.destroy();
            getDispatcher().freeCollisionAlgorithm(algorithm.m_allocAddress);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Debug drawing
    // ---------------------------------------------------------------------------------------

    /**
     * class DebugDrawcallback : public btTriangleCallback, public btInternalTriangleIndexCallback.
     * Java has single inheritance: this extends btTriangleCallback and provides
     * internalProcessTriangleIndex as a plain method. The only btInternalTriangleIndexCallback use
     * in btCollisionWorld is the CONVEX_TRIANGLEMESH_SHAPE_PROXYTYPE branch of debugDrawObject,
     * which is unreachable in PZBullet (btConvexTriangleMeshShape is not linked).
     */
    public static class DebugDrawcallback extends btTriangleCallback {
        public btIDebugDraw m_debugDrawer;
        public final btVector3 m_color = new btVector3();
        public final btTransform m_worldTrans = new btTransform();

        public DebugDrawcallback(
                btIDebugDraw debugDrawer, btTransform worldTrans, btVector3 color) {
            m_debugDrawer = debugDrawer;
            m_color.set(color);
            m_worldTrans.set(worldTrans);
        }

        public void internalProcessTriangleIndex(
                btVector3[] triangle, int partId, int triangleIndex) {
            processTriangle(triangle, partId, triangleIndex);
        }

        @Override
        public void processTriangle(btVector3[] triangle, int partId, int triangleIndex) {
            btVector3 wv0, wv1, wv2;
            wv0 = m_worldTrans.transform(triangle[0]);
            wv1 = m_worldTrans.transform(triangle[1]);
            wv2 = m_worldTrans.transform(triangle[2]);
            btVector3 center = wv0.add(wv1).add(wv2).mul(1. / 3.);

            if ((m_debugDrawer.getDebugMode() & btIDebugDraw.DBG_DrawNormals) != 0) {
                btVector3 normal = wv1.sub(wv0).cross(wv2.sub(wv0));
                normal.normalize();
                btVector3 normalColor = new btVector3(1, 1, 0);
                m_debugDrawer.drawLine(center, center.add(normal), normalColor);
            }
            m_debugDrawer.drawLine(wv0, wv1, m_color);
            m_debugDrawer.drawLine(wv1, wv2, m_color);
            m_debugDrawer.drawLine(wv2, wv0, m_color);
        }
    }

    public void debugDrawObject(
            btTransform worldTransform, btCollisionShape shape, btVector3 color) {
        // Draw a small simplex at the center of the object
        getDebugDrawer().drawTransform(worldTransform, 1);

        if (shape.getShapeType() == COMPOUND_SHAPE_PROXYTYPE) {
            final btCompoundShape compoundShape = (btCompoundShape) shape;
            for (int i = compoundShape.getNumChildShapes() - 1; i >= 0; i--) {
                btTransform childTrans = new btTransform(compoundShape.getChildTransform(i));
                final btCollisionShape colShape = compoundShape.getChildShape(i);
                debugDrawObject(worldTransform.mul(childTrans), colShape, color);
            }

        } else {

            switch (shape.getShapeType()) {
                case BOX_SHAPE_PROXYTYPE:
                    {
                        final btBoxShape boxShape = (btBoxShape) shape;
                        btVector3 halfExtents = boxShape.getHalfExtentsWithMargin();
                        getDebugDrawer()
                                .drawBox(halfExtents.negate(), halfExtents, worldTransform, color);
                        break;
                    }

                case SPHERE_SHAPE_PROXYTYPE:
                    {
                        final btSphereShape sphereShape = (btSphereShape) shape;
                        double radius =
                                sphereShape
                                        .getMargin(); // radius doesn't include the margin, so draw
                        // with margin

                        getDebugDrawer().drawSphere(radius, worldTransform, color);
                        break;
                    }
                case MULTI_SPHERE_SHAPE_PROXYTYPE:
                case CONE_SHAPE_PROXYTYPE:
                case CYLINDER_SHAPE_PROXYTYPE:
                    {
                        // btMultiSphereShape / btConeShape / btCylinderShape are not linked into
                        // PZBullet, so no shape of these types can exist; the inlined draw code
                        // of these cases is not ported.
                        break;
                    }
                case CAPSULE_SHAPE_PROXYTYPE:
                    {
                        final btCapsuleShape capsuleShape = (btCapsuleShape) shape;

                        double radius = capsuleShape.getRadius();
                        double halfHeight = capsuleShape.getHalfHeight();

                        int upAxis = capsuleShape.getUpAxis();
                        getDebugDrawer()
                                .drawCapsule(radius, halfHeight, upAxis, worldTransform, color);
                        break;
                    }
                case STATIC_PLANE_PROXYTYPE:
                    {
                        final btStaticPlaneShape staticPlaneShape = (btStaticPlaneShape) shape;
                        double planeConst = staticPlaneShape.getPlaneConstant();
                        final btVector3 planeNormal = staticPlaneShape.getPlaneNormal();
                        getDebugDrawer().drawPlane(planeNormal, planeConst, worldTransform, color);
                        break;
                    }
                default:
                    {

                        /// for polyhedral shapes
                        if (shape.isPolyhedral()) {
                            btPolyhedralConvexShape polyshape = (btPolyhedralConvexShape) shape;

                            int i;
                            if (polyshape.getConvexPolyhedron() != null) {
                                final btConvexPolyhedron poly = polyshape.getConvexPolyhedron();
                                for (i = 0; i < poly.m_faces.size(); i++) {
                                    btVector3 centroid = new btVector3(0, 0, 0);
                                    int numVerts = poly.m_faces.get(i).m_indices.size();
                                    if (numVerts != 0) {
                                        int lastV = poly.m_faces.get(i).m_indices.get(numVerts - 1);
                                        for (int v = 0;
                                                v < poly.m_faces.get(i).m_indices.size();
                                                v++) {
                                            int curVert = poly.m_faces.get(i).m_indices.get(v);
                                            centroid.addLocal(poly.m_vertices.get(curVert));
                                            getDebugDrawer()
                                                    .drawLine(
                                                            worldTransform.transform(
                                                                    poly.m_vertices.get(lastV)),
                                                            worldTransform.transform(
                                                                    poly.m_vertices.get(curVert)),
                                                            color);
                                            lastV = curVert;
                                        }
                                    }
                                    centroid.mulLocal((double) 1.f / (double) numVerts);
                                    if ((getDebugDrawer().getDebugMode()
                                                    & btIDebugDraw.DBG_DrawNormals)
                                            != 0) {
                                        btVector3 normalColor = new btVector3(1, 1, 0);
                                        btVector3 faceNormal =
                                                new btVector3(
                                                        poly.m_faces.get(i).m_plane[0],
                                                        poly.m_faces.get(i).m_plane[1],
                                                        poly.m_faces.get(i).m_plane[2]);
                                        getDebugDrawer()
                                                .drawLine(
                                                        worldTransform.transform(centroid),
                                                        worldTransform.transform(
                                                                centroid.add(faceNormal)),
                                                        normalColor);
                                    }
                                }

                            } else {
                                for (i = 0; i < polyshape.getNumEdges(); i++) {
                                    btVector3 a = new btVector3(), b = new btVector3();
                                    polyshape.getEdge(i, a, b);
                                    btVector3 wa = worldTransform.transform(a);
                                    btVector3 wb = worldTransform.transform(b);
                                    getDebugDrawer().drawLine(wa, wb, color);
                                }
                            }
                        }

                        if (shape.isConcave()) {
                            btConcaveShape concaveMesh = (btConcaveShape) shape;

                            /// @todo pass camera, for some culling? no -> we are not a graphics lib
                            btVector3 aabbMax =
                                    new btVector3(
                                            btScalar.BT_LARGE_FLOAT,
                                            btScalar.BT_LARGE_FLOAT,
                                            btScalar.BT_LARGE_FLOAT);
                            btVector3 aabbMin =
                                    new btVector3(
                                            -btScalar.BT_LARGE_FLOAT,
                                            -btScalar.BT_LARGE_FLOAT,
                                            -btScalar.BT_LARGE_FLOAT);

                            DebugDrawcallback drawCallback =
                                    new DebugDrawcallback(getDebugDrawer(), worldTransform, color);
                            concaveMesh.processAllTriangles(drawCallback, aabbMin, aabbMax);
                        }

                        if (shape.getShapeType() == CONVEX_TRIANGLEMESH_SHAPE_PROXYTYPE) {
                            // btConvexTriangleMeshShape is not linked into PZBullet: unreachable.
                        }
                    }
            }
        }
    }

    public void debugDrawWorld() {
        if (getDebugDrawer() != null
                && (getDebugDrawer().getDebugMode() & btIDebugDraw.DBG_DrawContactPoints) != 0) {
            int numManifolds = getDispatcher().getNumManifolds();
            btVector3 color = new btVector3(1, 1, 0);
            for (int i = 0; i < numManifolds; i++) {
                btPersistentManifold contactManifold =
                        getDispatcher().getManifoldByIndexInternal(i);
                // btCollisionObject* obA =
                // static_cast<btCollisionObject*>(contactManifold->getBody0());
                // btCollisionObject* obB =
                // static_cast<btCollisionObject*>(contactManifold->getBody1());

                int numContacts = contactManifold.getNumContacts();
                for (int j = 0; j < numContacts; j++) {
                    btManifoldPoint cp = contactManifold.getContactPoint(j);
                    getDebugDrawer()
                            .drawContactPoint(
                                    cp.m_positionWorldOnB,
                                    cp.m_normalWorldOnB,
                                    cp.getDistance(),
                                    cp.getLifeTime(),
                                    color);
                }
            }
        }

        if (getDebugDrawer() != null
                && (getDebugDrawer().getDebugMode()
                                & (btIDebugDraw.DBG_DrawWireframe | btIDebugDraw.DBG_DrawAabb))
                        != 0) {
            int i;

            for (i = 0; i < m_collisionObjects.size(); i++) {
                btCollisionObject colObj = m_collisionObjects.get(i);
                if ((colObj.getCollisionFlags() & btCollisionObject.CF_DISABLE_VISUALIZE_OBJECT)
                        == 0) {
                    if (getDebugDrawer() != null
                            && (getDebugDrawer().getDebugMode() & btIDebugDraw.DBG_DrawWireframe)
                                    != 0) {
                        btVector3 color = new btVector3(1., 1., 1.);
                        switch (colObj.getActivationState()) {
                            case btCollisionObject.ACTIVE_TAG:
                                color = new btVector3(1., 1., 1.);
                                break;
                            case btCollisionObject.ISLAND_SLEEPING:
                                color = new btVector3(0., 1., 0.);
                                break;
                            case btCollisionObject.WANTS_DEACTIVATION:
                                color = new btVector3(0., 1., 1.);
                                break;
                            case btCollisionObject.DISABLE_DEACTIVATION:
                                color = new btVector3(1., 0., 0.);
                                break;
                            case btCollisionObject.DISABLE_SIMULATION:
                                color = new btVector3(1., 1., 0.);
                                break;
                            default:
                                {
                                    color = new btVector3(1, 0., 0.);
                                }
                        }

                        debugDrawObject(
                                colObj.getWorldTransform(), colObj.getCollisionShape(), color);
                    }
                    if (m_debugDrawer != null
                            && (m_debugDrawer.getDebugMode() & btIDebugDraw.DBG_DrawAabb) != 0) {
                        btVector3 minAabb = new btVector3(), maxAabb = new btVector3();
                        btVector3 colorvec = new btVector3(1, 0, 0);
                        colObj.getCollisionShape()
                                .getAabb(colObj.getWorldTransform(), minAabb, maxAabb);
                        btVector3 contactThreshold =
                                new btVector3(
                                        btGlobals.gContactBreakingThreshold,
                                        btGlobals.gContactBreakingThreshold,
                                        btGlobals.gContactBreakingThreshold);
                        minAabb.subLocal(contactThreshold);
                        maxAabb.addLocal(contactThreshold);

                        btVector3 minAabb2 = new btVector3(), maxAabb2 = new btVector3();

                        if (getDispatchInfo().m_useContinuous
                                && colObj.getInternalType() == btCollisionObject.CO_RIGID_BODY
                                && !colObj.isStaticOrKinematicObject()) {
                            colObj.getCollisionShape()
                                    .getAabb(
                                            colObj.getInterpolationWorldTransform(),
                                            minAabb2,
                                            maxAabb2);
                            minAabb2.subLocal(contactThreshold);
                            maxAabb2.addLocal(contactThreshold);
                            minAabb.setMin(minAabb2);
                            maxAabb.setMax(maxAabb2);
                        }

                        m_debugDrawer.drawAabb(minAabb, maxAabb, colorvec);
                    }
                }
            }
        }
    }

    // serialize / serializeCollisionObjects: btSerializer is not ported (PZ never serializes).
}
