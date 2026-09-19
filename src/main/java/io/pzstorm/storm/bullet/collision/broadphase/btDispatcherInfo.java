// Port of BulletCollision/BroadphaseCollision/btDispatcher.h (Bullet 2.82), struct
// btDispatcherInfo (header-only).
package io.pzstorm.storm.bullet.collision.broadphase;

import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;

public class btDispatcherInfo {
    // enum DispatchFunc
    public static final int DISPATCH_DISCRETE = 1;
    public static final int DISPATCH_CONTINUOUS = 2;

    public double m_timeStep;
    public int m_stepCount;
    public int m_dispatchFunc;

    /** {@code mutable} in C++. */
    public double m_timeOfImpact;

    public boolean m_useContinuous;
    public btIDebugDraw m_debugDraw;
    public boolean m_enableSatConvex;
    public boolean m_enableSPU;
    public boolean m_useEpa;
    public double m_allowedCcdPenetration;
    public boolean m_useConvexConservativeDistanceUtil;
    public double m_convexConservativeDistanceThreshold;

    public btDispatcherInfo() {
        m_timeStep = 0.;
        m_stepCount = 0;
        m_dispatchFunc = DISPATCH_DISCRETE;
        m_timeOfImpact = 1.;
        m_useContinuous = true;
        m_debugDraw = null;
        m_enableSatConvex = false;
        m_enableSPU = true;
        m_useEpa = true;
        m_allowedCcdPenetration = 0.04;
        m_useConvexConservativeDistanceUtil = false;
        // 0.0f widened to btScalar
        m_convexConservativeDistanceThreshold = 0.0;
    }
}
