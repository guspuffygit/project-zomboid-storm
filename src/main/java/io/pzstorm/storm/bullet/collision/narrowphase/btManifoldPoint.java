// Port of BulletCollision/NarrowPhaseCollision/btManifoldPoint.h (Bullet 2.82)
package io.pzstorm.storm.bullet.collision.narrowphase;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * ManifoldContactPoint collects and maintains persistent contactpoints. Fields left uninitialised
 * by the C++ constructors are zero here. C++ assignment ({@code a = b}) is {@link #set}.
 */
public class btManifoldPoint {
    public final btVector3 m_localPointA = new btVector3();
    public final btVector3 m_localPointB = new btVector3();
    public final btVector3 m_positionWorldOnB = new btVector3();

    /** m_positionWorldOnA is redundant information, see getPositionWorldOnA(), but for clarity */
    public final btVector3 m_positionWorldOnA = new btVector3();

    public final btVector3 m_normalWorldOnB = new btVector3();

    public double m_distance1;
    public double m_combinedFriction;
    public double m_combinedRollingFriction;
    public double m_combinedRestitution;

    // BP mod, store contact triangles.
    public int m_partId0;
    public int m_partId1;
    public int m_index0;
    public int m_index1;

    public Object m_userPersistentData;
    public boolean m_lateralFrictionInitialized;

    public double m_appliedImpulse;
    public double m_appliedImpulseLateral1;
    public double m_appliedImpulseLateral2;
    public double m_contactMotion1;
    public double m_contactMotion2;
    public double m_contactCFM1;
    public double m_contactCFM2;

    public int m_lifeTime; // lifetime of the contactpoint in frames

    public final btVector3 m_lateralFrictionDir1 = new btVector3();
    public final btVector3 m_lateralFrictionDir2 = new btVector3();

    public btManifoldPoint() {
        m_userPersistentData = null;
        m_lateralFrictionInitialized = false;
        m_appliedImpulse = (double) 0.f;
        m_appliedImpulseLateral1 = (double) 0.f;
        m_appliedImpulseLateral2 = (double) 0.f;
        m_contactMotion1 = (double) 0.f;
        m_contactMotion2 = (double) 0.f;
        m_contactCFM1 = (double) 0.f;
        m_contactCFM2 = (double) 0.f;
        m_lifeTime = 0;
    }

    public btManifoldPoint(btVector3 pointA, btVector3 pointB, btVector3 normal, double distance) {
        m_localPointA.set(pointA);
        m_localPointB.set(pointB);
        m_normalWorldOnB.set(normal);
        m_distance1 = distance;
        m_combinedFriction = 0.0;
        m_combinedRollingFriction = 0.0;
        m_combinedRestitution = 0.0;
        m_userPersistentData = null;
        m_lateralFrictionInitialized = false;
        m_appliedImpulse = (double) 0.f;
        m_appliedImpulseLateral1 = (double) 0.f;
        m_appliedImpulseLateral2 = (double) 0.f;
        m_contactMotion1 = (double) 0.f;
        m_contactMotion2 = (double) 0.f;
        m_contactCFM1 = (double) 0.f;
        m_contactCFM2 = (double) 0.f;
        m_lifeTime = 0;
    }

    /** Implicit C++ copy constructor. */
    public btManifoldPoint(btManifoldPoint other) {
        set(other);
    }

    /** Implicit C++ {@code operator=} (member-wise copy, vectors including w). */
    public btManifoldPoint set(btManifoldPoint o) {
        m_localPointA.set(o.m_localPointA);
        m_localPointB.set(o.m_localPointB);
        m_positionWorldOnB.set(o.m_positionWorldOnB);
        m_positionWorldOnA.set(o.m_positionWorldOnA);
        m_normalWorldOnB.set(o.m_normalWorldOnB);
        m_distance1 = o.m_distance1;
        m_combinedFriction = o.m_combinedFriction;
        m_combinedRollingFriction = o.m_combinedRollingFriction;
        m_combinedRestitution = o.m_combinedRestitution;
        m_partId0 = o.m_partId0;
        m_partId1 = o.m_partId1;
        m_index0 = o.m_index0;
        m_index1 = o.m_index1;
        m_userPersistentData = o.m_userPersistentData;
        m_lateralFrictionInitialized = o.m_lateralFrictionInitialized;
        m_appliedImpulse = o.m_appliedImpulse;
        m_appliedImpulseLateral1 = o.m_appliedImpulseLateral1;
        m_appliedImpulseLateral2 = o.m_appliedImpulseLateral2;
        m_contactMotion1 = o.m_contactMotion1;
        m_contactMotion2 = o.m_contactMotion2;
        m_contactCFM1 = o.m_contactCFM1;
        m_contactCFM2 = o.m_contactCFM2;
        m_lifeTime = o.m_lifeTime;
        m_lateralFrictionDir1.set(o.m_lateralFrictionDir1);
        m_lateralFrictionDir2.set(o.m_lateralFrictionDir2);
        return this;
    }

    public double getDistance() {
        return m_distance1;
    }

    public int getLifeTime() {
        return m_lifeTime;
    }

    public btVector3 getPositionWorldOnA() {
        return m_positionWorldOnA;
    }

    public btVector3 getPositionWorldOnB() {
        return m_positionWorldOnB;
    }

    public void setDistance(double dist) {
        m_distance1 = dist;
    }

    /**
     * this returns the most recent applied impulse, to satisfy contact constraints by the
     * constraint solver
     */
    public double getAppliedImpulse() {
        return m_appliedImpulse;
    }
}
