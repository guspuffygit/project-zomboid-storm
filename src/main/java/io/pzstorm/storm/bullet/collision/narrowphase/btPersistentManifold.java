// Port of BulletCollision/NarrowPhaseCollision/btPersistentManifold.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.linearmath.btGlobals;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTypedObject;
import io.pzstorm.storm.bullet.linearmath.btVector3;
import io.pzstorm.storm.bullet.linearmath.btVector4;

/**
 * btPersistentManifold is a contact point cache, it stays persistent as long as objects are
 * overlapping in the broadphase. The globals gContactBreakingThreshold, gContactCalcArea3Points,
 * gContactDestroyedCallback and gContactProcessedCallback live in {@link btGlobals}; the callback
 * globals must hold a {@link ContactDestroyedCallback} / {@link ContactProcessedCallback}.
 */
public class btPersistentManifold extends btTypedObject {

    /** {@code typedef bool (*ContactDestroyedCallback)(void* userPersistentData);} */
    @FunctionalInterface
    public interface ContactDestroyedCallback {
        boolean call(Object userPersistentData);
    }

    /**
     * {@code typedef bool (*ContactProcessedCallback)(btManifoldPoint& cp,void* body0,void*
     * body1);}
     */
    @FunctionalInterface
    public interface ContactProcessedCallback {
        boolean call(btManifoldPoint cp, Object body0, Object body1);
    }

    // enum btContactManifoldTypes
    public static final int MIN_CONTACT_MANIFOLD_TYPE = 1024;
    public static final int BT_PERSISTENT_MANIFOLD_TYPE = 1025;

    public static final int MANIFOLD_CACHE_SIZE = 4;

    public final btManifoldPoint[] m_pointCache = new btManifoldPoint[MANIFOLD_CACHE_SIZE];

    /** this two body pointers can point to the physics rigidbody class. */
    public btCollisionObject m_body0;

    public btCollisionObject m_body1;

    public int m_cachedPoints;

    public double m_contactBreakingThreshold;
    public double m_contactProcessingThreshold;

    public int m_companionIdA;
    public int m_companionIdB;

    public int m_index1a;

    private void initPointCache() {
        for (int i = 0; i < MANIFOLD_CACHE_SIZE; i++) {
            m_pointCache[i] = new btManifoldPoint();
        }
    }

    public btPersistentManifold() {
        super(BT_PERSISTENT_MANIFOLD_TYPE);
        initPointCache();
        m_body0 = null;
        m_body1 = null;
        m_cachedPoints = 0;
        m_index1a = 0;
    }

    public btPersistentManifold(
            btCollisionObject body0,
            btCollisionObject body1,
            int unused,
            double contactBreakingThreshold,
            double contactProcessingThreshold) {
        super(BT_PERSISTENT_MANIFOLD_TYPE);
        initPointCache();
        m_body0 = body0;
        m_body1 = body1;
        m_cachedPoints = 0;
        m_contactBreakingThreshold = contactBreakingThreshold;
        m_contactProcessingThreshold = contactProcessingThreshold;
    }

    public btCollisionObject getBody0() {
        return m_body0;
    }

    public btCollisionObject getBody1() {
        return m_body1;
    }

    public void setBodies(btCollisionObject body0, btCollisionObject body1) {
        m_body0 = body0;
        m_body1 = body1;
    }

    public void clearUserCache(btManifoldPoint pt) {
        Object oldPtr = pt.m_userPersistentData;
        if (oldPtr != null) {
            if (pt.m_userPersistentData != null && btGlobals.gContactDestroyedCallback != null) {
                ((ContactDestroyedCallback) btGlobals.gContactDestroyedCallback)
                        .call(pt.m_userPersistentData);
                pt.m_userPersistentData = null;
            }
        }
    }

    public int getNumContacts() {
        return m_cachedPoints;
    }

    /**
     * the setNumContacts API is usually not used, except when you gather/fill all contacts manually
     */
    public void setNumContacts(int cachedPoints) {
        m_cachedPoints = cachedPoints;
    }

    public btManifoldPoint getContactPoint(int index) {
        return m_pointCache[index];
    }

    public double getContactBreakingThreshold() {
        return m_contactBreakingThreshold;
    }

    public double getContactProcessingThreshold() {
        return m_contactProcessingThreshold;
    }

    public void setContactBreakingThreshold(double contactBreakingThreshold) {
        m_contactBreakingThreshold = contactBreakingThreshold;
    }

    public void setContactProcessingThreshold(double contactProcessingThreshold) {
        m_contactProcessingThreshold = contactProcessingThreshold;
    }

    private static double calcArea4Points(btVector3 p0, btVector3 p1, btVector3 p2, btVector3 p3) {
        // It calculates possible 3 area constructed from random 4 points and returns the biggest
        // one.
        btVector3[] a = new btVector3[3], b = new btVector3[3];
        a[0] = p0.sub(p1);
        a[1] = p0.sub(p2);
        a[2] = p0.sub(p3);
        b[0] = p2.sub(p3);
        b[1] = p1.sub(p3);
        b[2] = p1.sub(p2);

        btVector3 tmp0 = a[0].cross(b[0]);
        btVector3 tmp1 = a[1].cross(b[1]);
        btVector3 tmp2 = a[2].cross(b[2]);

        return btMinMax.btMax(btMinMax.btMax(tmp0.length2(), tmp1.length2()), tmp2.length2());
    }

    /** sort cached points so most isolated points come first */
    public int sortCachedPoints(btManifoldPoint pt) {
        // calculate 4 possible cases areas, and take biggest area
        // also need to keep 'deepest'
        int maxPenetrationIndex = -1;
        // KEEP_DEEPEST_POINT
        double maxPenetration = pt.getDistance();
        for (int i = 0; i < 4; i++) {
            if (m_pointCache[i].getDistance() < maxPenetration) {
                maxPenetrationIndex = i;
                maxPenetration = m_pointCache[i].getDistance();
            }
        }

        double res0 = 0.0, res1 = 0.0, res2 = 0.0, res3 = 0.0;

        if (btGlobals.gContactCalcArea3Points) {
            if (maxPenetrationIndex != 0) {
                btVector3 a0 = pt.m_localPointA.sub(m_pointCache[1].m_localPointA);
                btVector3 b0 = m_pointCache[3].m_localPointA.sub(m_pointCache[2].m_localPointA);
                btVector3 cross = a0.cross(b0);
                res0 = cross.length2();
            }
            if (maxPenetrationIndex != 1) {
                btVector3 a1 = pt.m_localPointA.sub(m_pointCache[0].m_localPointA);
                btVector3 b1 = m_pointCache[3].m_localPointA.sub(m_pointCache[2].m_localPointA);
                btVector3 cross = a1.cross(b1);
                res1 = cross.length2();
            }
            if (maxPenetrationIndex != 2) {
                btVector3 a2 = pt.m_localPointA.sub(m_pointCache[0].m_localPointA);
                btVector3 b2 = m_pointCache[3].m_localPointA.sub(m_pointCache[1].m_localPointA);
                btVector3 cross = a2.cross(b2);
                res2 = cross.length2();
            }
            if (maxPenetrationIndex != 3) {
                btVector3 a3 = pt.m_localPointA.sub(m_pointCache[0].m_localPointA);
                btVector3 b3 = m_pointCache[2].m_localPointA.sub(m_pointCache[1].m_localPointA);
                btVector3 cross = a3.cross(b3);
                res3 = cross.length2();
            }
        } else {
            if (maxPenetrationIndex != 0) {
                res0 =
                        calcArea4Points(
                                pt.m_localPointA,
                                m_pointCache[1].m_localPointA,
                                m_pointCache[2].m_localPointA,
                                m_pointCache[3].m_localPointA);
            }
            if (maxPenetrationIndex != 1) {
                res1 =
                        calcArea4Points(
                                pt.m_localPointA,
                                m_pointCache[0].m_localPointA,
                                m_pointCache[2].m_localPointA,
                                m_pointCache[3].m_localPointA);
            }
            if (maxPenetrationIndex != 2) {
                res2 =
                        calcArea4Points(
                                pt.m_localPointA,
                                m_pointCache[0].m_localPointA,
                                m_pointCache[1].m_localPointA,
                                m_pointCache[3].m_localPointA);
            }
            if (maxPenetrationIndex != 3) {
                res3 =
                        calcArea4Points(
                                pt.m_localPointA,
                                m_pointCache[0].m_localPointA,
                                m_pointCache[1].m_localPointA,
                                m_pointCache[2].m_localPointA);
            }
        }
        btVector4 maxvec = new btVector4(res0, res1, res2, res3);
        int biggestarea = maxvec.closestAxis4();
        return biggestarea;
    }

    public int getCacheEntry(btManifoldPoint newPoint) {
        double shortestDist = getContactBreakingThreshold() * getContactBreakingThreshold();
        int size = getNumContacts();
        int nearestPoint = -1;
        for (int i = 0; i < size; i++) {
            btManifoldPoint mp = m_pointCache[i];

            btVector3 diffA = mp.m_localPointA.sub(newPoint.m_localPointA);
            double distToManiPoint = diffA.dot(diffA);
            if (distToManiPoint < shortestDist) {
                shortestDist = distToManiPoint;
                nearestPoint = i;
            }
        }
        return nearestPoint;
    }

    public int addManifoldPoint(btManifoldPoint newPoint) {
        return addManifoldPoint(newPoint, false);
    }

    public int addManifoldPoint(btManifoldPoint newPoint, boolean isPredictive) {
        int insertIndex = getNumContacts();
        if (insertIndex == MANIFOLD_CACHE_SIZE) {
            // sort cache so best points come first, based on area
            insertIndex = sortCachedPoints(newPoint);
            clearUserCache(m_pointCache[insertIndex]);
        } else {
            m_cachedPoints++;
        }
        if (insertIndex < 0) insertIndex = 0;

        m_pointCache[insertIndex].set(newPoint);
        return insertIndex;
    }

    public void removeContactPoint(int index) {
        clearUserCache(m_pointCache[index]);

        int lastUsedIndex = getNumContacts() - 1;
        if (index != lastUsedIndex) {
            m_pointCache[index].set(m_pointCache[lastUsedIndex]);
            // get rid of duplicated userPersistentData pointer
            m_pointCache[lastUsedIndex].m_userPersistentData = null;
            m_pointCache[lastUsedIndex].m_appliedImpulse = (double) 0.f;
            m_pointCache[lastUsedIndex].m_lateralFrictionInitialized = false;
            m_pointCache[lastUsedIndex].m_appliedImpulseLateral1 = (double) 0.f;
            m_pointCache[lastUsedIndex].m_appliedImpulseLateral2 = (double) 0.f;
            m_pointCache[lastUsedIndex].m_lifeTime = 0;
        }
        m_cachedPoints--;
    }

    public void replaceContactPoint(btManifoldPoint newPoint, int insertIndex) {
        // MAINTAIN_PERSISTENCY
        int lifeTime = m_pointCache[insertIndex].getLifeTime();
        double appliedImpulse = m_pointCache[insertIndex].m_appliedImpulse;
        double appliedLateralImpulse1 = m_pointCache[insertIndex].m_appliedImpulseLateral1;
        double appliedLateralImpulse2 = m_pointCache[insertIndex].m_appliedImpulseLateral2;

        Object cache = m_pointCache[insertIndex].m_userPersistentData;

        m_pointCache[insertIndex].set(newPoint);

        m_pointCache[insertIndex].m_userPersistentData = cache;
        m_pointCache[insertIndex].m_appliedImpulse = appliedImpulse;
        m_pointCache[insertIndex].m_appliedImpulseLateral1 = appliedLateralImpulse1;
        m_pointCache[insertIndex].m_appliedImpulseLateral2 = appliedLateralImpulse2;

        m_pointCache[insertIndex].m_appliedImpulse = appliedImpulse;
        m_pointCache[insertIndex].m_appliedImpulseLateral1 = appliedLateralImpulse1;
        m_pointCache[insertIndex].m_appliedImpulseLateral2 = appliedLateralImpulse2;

        m_pointCache[insertIndex].m_lifeTime = lifeTime;
    }

    public boolean validContactDistance(btManifoldPoint pt) {
        return pt.m_distance1 <= getContactBreakingThreshold();
    }

    /**
     * calculated new worldspace coordinates and depth, and reject points that exceed the collision
     * margin
     */
    public void refreshContactPoints(btTransform trA, btTransform trB) {
        int i;
        // first refresh worldspace positions and distance
        for (i = getNumContacts() - 1; i >= 0; i--) {
            btManifoldPoint manifoldPoint = m_pointCache[i];
            manifoldPoint.m_positionWorldOnA.set(trA.transform(manifoldPoint.m_localPointA));
            manifoldPoint.m_positionWorldOnB.set(trB.transform(manifoldPoint.m_localPointB));
            manifoldPoint.m_distance1 =
                    (manifoldPoint.m_positionWorldOnA.sub(manifoldPoint.m_positionWorldOnB))
                            .dot(manifoldPoint.m_normalWorldOnB);
            manifoldPoint.m_lifeTime++;
        }

        // then
        double distance2d;
        btVector3 projectedDifference = new btVector3(), projectedPoint = new btVector3();
        for (i = getNumContacts() - 1; i >= 0; i--) {
            btManifoldPoint manifoldPoint = m_pointCache[i];
            // contact becomes invalid when signed distance exceeds margin (projected on
            // contactnormal direction)
            if (!validContactDistance(manifoldPoint)) {
                removeContactPoint(i);
            } else {
                // contact also becomes invalid when relative movement orthogonal to normal exceeds
                // margin
                projectedPoint.set(
                        manifoldPoint.m_positionWorldOnA.sub(
                                manifoldPoint.m_normalWorldOnB.mul(manifoldPoint.m_distance1)));
                projectedDifference.set(manifoldPoint.m_positionWorldOnB.sub(projectedPoint));
                distance2d = projectedDifference.dot(projectedDifference);
                if (distance2d > getContactBreakingThreshold() * getContactBreakingThreshold()) {
                    removeContactPoint(i);
                } else {
                    // contact point processed callback
                    if (btGlobals.gContactProcessedCallback != null)
                        ((ContactProcessedCallback) btGlobals.gContactProcessedCallback)
                                .call(manifoldPoint, m_body0, m_body1);
                }
            }
        }
    }

    public void clearManifold() {
        int i;
        for (i = 0; i < m_cachedPoints; i++) {
            clearUserCache(m_pointCache[i]);
        }
        m_cachedPoints = 0;
    }
}
