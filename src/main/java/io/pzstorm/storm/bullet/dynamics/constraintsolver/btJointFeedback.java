// Port of BulletDynamics/ConstraintSolver/btTypedConstraint.h (Bullet 2.82): struct btJointFeedback
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public class btJointFeedback {
    public final btVector3 m_appliedForceBodyA = new btVector3();
    public final btVector3 m_appliedTorqueBodyA = new btVector3();
    public final btVector3 m_appliedForceBodyB = new btVector3();
    public final btVector3 m_appliedTorqueBodyB = new btVector3();
}
