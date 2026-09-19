package io.pzstorm.storm.bullet.collision.dispatch;

import io.pzstorm.storm.bullet.collision.narrowphase.btDiscreteCollisionDetectorInterface;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Port of Bullet 2.82 BulletCollision/CollisionDispatch/btManifoldResult.{h,cpp}.
 *
 * <p>btManifoldResult is a helper class to manage contact results. {@code m_body0Wrap}/{@code
 * m_body1Wrap} are pointers: callers temporarily swap them to stack wrappers (compound algorithms),
 * so they are plain references here.
 */
public class btManifoldResult extends btDiscreteCollisionDetectorInterface.Result {
    public btPersistentManifold m_manifoldPtr;
    public btCollisionObjectWrapper m_body0Wrap;
    public btCollisionObjectWrapper m_body1Wrap;
    public int m_partId0;
    public int m_partId1;
    public int m_index0;
    public int m_index1;

    /** DEBUG_PART_INDEX is off: fields uninitialized in C++ (0 here). */
    public btManifoldResult() {}

    public btManifoldResult(
            btCollisionObjectWrapper body0Wrap, btCollisionObjectWrapper body1Wrap) {
        m_manifoldPtr = null;
        m_body0Wrap = body0Wrap;
        m_body1Wrap = body1Wrap;
    }

    /** {@code virtual ~btManifoldResult()} */
    public void destroy() {}

    public void setPersistentManifold(btPersistentManifold manifoldPtr) {
        m_manifoldPtr = manifoldPtr;
    }

    public btPersistentManifold getPersistentManifold() {
        return m_manifoldPtr;
    }

    @Override
    public void setShapeIdentifiersA(int partId0, int index0) {
        m_partId0 = partId0;
        m_index0 = index0;
    }

    @Override
    public void setShapeIdentifiersB(int partId1, int index1) {
        m_partId1 = partId1;
        m_index1 = index1;
    }

    public void refreshContactPoints() {
        if (m_manifoldPtr.getNumContacts() == 0) return;

        boolean isSwapped = m_manifoldPtr.getBody0() != m_body0Wrap.getCollisionObject();

        if (isSwapped) {
            m_manifoldPtr.refreshContactPoints(
                    m_body1Wrap.getCollisionObject().getWorldTransform(),
                    m_body0Wrap.getCollisionObject().getWorldTransform());
        } else {
            m_manifoldPtr.refreshContactPoints(
                    m_body0Wrap.getCollisionObject().getWorldTransform(),
                    m_body1Wrap.getCollisionObject().getWorldTransform());
        }
    }

    public btCollisionObjectWrapper getBody0Wrap() {
        return m_body0Wrap;
    }

    public btCollisionObjectWrapper getBody1Wrap() {
        return m_body1Wrap;
    }

    public void setBody0Wrap(btCollisionObjectWrapper obj0Wrap) {
        m_body0Wrap = obj0Wrap;
    }

    public void setBody1Wrap(btCollisionObjectWrapper obj1Wrap) {
        m_body1Wrap = obj1Wrap;
    }

    public btCollisionObject getBody0Internal() {
        return m_body0Wrap.getCollisionObject();
    }

    public btCollisionObject getBody1Internal() {
        return m_body1Wrap.getCollisionObject();
    }

    /** file-static inline {@code calculateCombinedRollingFriction} (btManifoldResult.cpp). */
    public static double calculateCombinedRollingFriction(
            btCollisionObject body0, btCollisionObject body1) {
        double friction = body0.getRollingFriction() * body1.getRollingFriction();

        final double MAX_FRICTION = 10.;
        if (friction < -MAX_FRICTION) friction = -MAX_FRICTION;
        if (friction > MAX_FRICTION) friction = MAX_FRICTION;
        return friction;
    }

    public static double calculateCombinedFriction(
            btCollisionObject body0, btCollisionObject body1) {
        double friction = body0.getFriction() * body1.getFriction();

        final double MAX_FRICTION = 10.;
        if (friction < -MAX_FRICTION) friction = -MAX_FRICTION;
        if (friction > MAX_FRICTION) friction = MAX_FRICTION;
        return friction;
    }

    public static double calculateCombinedRestitution(
            btCollisionObject body0, btCollisionObject body1) {
        return body0.getRestitution() * body1.getRestitution();
    }

    @Override
    public void addContactPoint(btVector3 normalOnBInWorld, btVector3 pointInWorld, double depth) {
        // order in manifold needs to match
        if (depth > m_manifoldPtr.getContactBreakingThreshold()) return;

        boolean isSwapped = m_manifoldPtr.getBody0() != m_body0Wrap.getCollisionObject();

        btVector3 pointA = pointInWorld.add(normalOnBInWorld.mul(depth));

        btVector3 localA;
        btVector3 localB;

        if (isSwapped) {
            localA = m_body1Wrap.getCollisionObject().getWorldTransform().invXform(pointA);
            localB = m_body0Wrap.getCollisionObject().getWorldTransform().invXform(pointInWorld);
        } else {
            localA = m_body0Wrap.getCollisionObject().getWorldTransform().invXform(pointA);
            localB = m_body1Wrap.getCollisionObject().getWorldTransform().invXform(pointInWorld);
        }

        btManifoldPoint newPt = new btManifoldPoint(localA, localB, normalOnBInWorld, depth);
        newPt.m_positionWorldOnA.set(pointA);
        newPt.m_positionWorldOnB.set(pointInWorld);

        int insertIndex = m_manifoldPtr.getCacheEntry(newPt);

        newPt.m_combinedFriction =
                calculateCombinedFriction(
                        m_body0Wrap.getCollisionObject(), m_body1Wrap.getCollisionObject());
        newPt.m_combinedRestitution =
                calculateCombinedRestitution(
                        m_body0Wrap.getCollisionObject(), m_body1Wrap.getCollisionObject());
        newPt.m_combinedRollingFriction =
                calculateCombinedRollingFriction(
                        m_body0Wrap.getCollisionObject(), m_body1Wrap.getCollisionObject());
        btVector3.btPlaneSpace1(
                newPt.m_normalWorldOnB, newPt.m_lateralFrictionDir1, newPt.m_lateralFrictionDir2);

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
        /// @todo, check this for any side effects
        if (insertIndex >= 0) {
            m_manifoldPtr.replaceContactPoint(newPt, insertIndex);
        } else {
            insertIndex = m_manifoldPtr.addManifoldPoint(newPt);
        }

        // User can override friction and/or restitution
        if (btGlobals.gContactAddedCallback != null
                &&
                // and if either of the two bodies requires custom material
                ((m_body0Wrap.getCollisionObject().getCollisionFlags()
                                        & btCollisionObject.CF_CUSTOM_MATERIAL_CALLBACK)
                                != 0
                        || (m_body1Wrap.getCollisionObject().getCollisionFlags()
                                        & btCollisionObject.CF_CUSTOM_MATERIAL_CALLBACK)
                                != 0)) {
            // experimental feature info, for per-triangle material etc.
            btCollisionObjectWrapper obj0Wrap = isSwapped ? m_body1Wrap : m_body0Wrap;
            btCollisionObjectWrapper obj1Wrap = isSwapped ? m_body0Wrap : m_body1Wrap;
            ((ContactAddedCallback) btGlobals.gContactAddedCallback)
                    .invoke(
                            m_manifoldPtr.getContactPoint(insertIndex),
                            obj0Wrap,
                            newPt.m_partId0,
                            newPt.m_index0,
                            obj1Wrap,
                            newPt.m_partId1,
                            newPt.m_index1);
        }
    }
}
