// Port of BulletCollision/BroadphaseCollision/btBroadphaseProxy.h (Bullet 2.82), struct
// btBroadphaseProxy (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btBroadphaseProxy is the main class that can be used with the Bullet broadphases. It stores
 * collision shape type information, collision filter information and a client object, typically a
 * btCollisionObject or btRigidBody.
 */
public class btBroadphaseProxy {
    // enum CollisionFilterGroups
    public static final int DefaultFilter = 1;
    public static final int StaticFilter = 2;
    public static final int KinematicFilter = 4;
    public static final int DebrisFilter = 8;
    public static final int SensorTrigger = 16;
    public static final int CharacterFilter = 32;
    public static final int AllFilter = -1;

    /** Usually the client btCollisionObject or Rigidbody class. */
    public Object m_clientObject;

    public short m_collisionFilterGroup;
    public short m_collisionFilterMask;
    public Object m_multiSapParentProxy;

    /** m_uniqueId is introduced for paircache. */
    public int m_uniqueId;

    public final btVector3 m_aabbMin = new btVector3();
    public final btVector3 m_aabbMax = new btVector3();

    public int getUid() {
        return m_uniqueId;
    }

    /**
     * Default ctor: m_clientObject(0), m_multiSapParentProxy(0); the rest is uninitialized in C++.
     */
    public btBroadphaseProxy() {
        m_clientObject = null;
        m_multiSapParentProxy = null;
    }

    public btBroadphaseProxy(
            btVector3 aabbMin,
            btVector3 aabbMax,
            Object userPtr,
            short collisionFilterGroup,
            short collisionFilterMask) {
        this(aabbMin, aabbMax, userPtr, collisionFilterGroup, collisionFilterMask, null);
    }

    public btBroadphaseProxy(
            btVector3 aabbMin,
            btVector3 aabbMax,
            Object userPtr,
            short collisionFilterGroup,
            short collisionFilterMask,
            Object multiSapParentProxy) {
        m_clientObject = userPtr;
        m_collisionFilterGroup = collisionFilterGroup;
        m_collisionFilterMask = collisionFilterMask;
        m_aabbMin.set(aabbMin);
        m_aabbMax.set(aabbMax);
        m_multiSapParentProxy = multiSapParentProxy;
    }

    public static boolean isPolyhedral(int proxyType) {
        return (proxyType < BroadphaseNativeTypes.IMPLICIT_CONVEX_SHAPES_START_HERE);
    }

    public static boolean isConvex(int proxyType) {
        return (proxyType < BroadphaseNativeTypes.CONCAVE_SHAPES_START_HERE);
    }

    public static boolean isNonMoving(int proxyType) {
        return (isConcave(proxyType)
                && !(proxyType == BroadphaseNativeTypes.GIMPACT_SHAPE_PROXYTYPE));
    }

    public static boolean isConcave(int proxyType) {
        return ((proxyType > BroadphaseNativeTypes.CONCAVE_SHAPES_START_HERE)
                && (proxyType < BroadphaseNativeTypes.CONCAVE_SHAPES_END_HERE));
    }

    public static boolean isCompound(int proxyType) {
        return (proxyType == BroadphaseNativeTypes.COMPOUND_SHAPE_PROXYTYPE);
    }

    public static boolean isSoftBody(int proxyType) {
        return (proxyType == BroadphaseNativeTypes.SOFTBODY_SHAPE_PROXYTYPE);
    }

    public static boolean isInfinite(int proxyType) {
        return (proxyType == BroadphaseNativeTypes.STATIC_PLANE_PROXYTYPE);
    }

    public static boolean isConvex2d(int proxyType) {
        return (proxyType == BroadphaseNativeTypes.BOX_2D_SHAPE_PROXYTYPE)
                || (proxyType == BroadphaseNativeTypes.CONVEX_2D_SHAPE_PROXYTYPE);
    }
}
