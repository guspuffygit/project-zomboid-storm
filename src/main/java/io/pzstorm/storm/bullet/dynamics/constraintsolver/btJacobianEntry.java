// Port of BulletDynamics/ConstraintSolver/btJacobianEntry.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btJacobianEntry: 1D constraint along a normal axis between bodyA and bodyB. */
public class btJacobianEntry {
    public final btVector3 m_linearJointAxis = new btVector3();
    public final btVector3 m_aJ = new btVector3();
    public final btVector3 m_bJ = new btVector3();
    public final btVector3 m_0MinvJt = new btVector3();
    public final btVector3 m_1MinvJt = new btVector3();
    // Optimization: can be stored in the w/last component of one of the vectors
    public double m_Adiag;

    public btJacobianEntry() {}

    /** constraint between two different rigidbodies */
    public btJacobianEntry(
            btMatrix3x3 world2A,
            btMatrix3x3 world2B,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btVector3 jointAxis,
            btVector3 inertiaInvA,
            double massInvA,
            btVector3 inertiaInvB,
            double massInvB) {
        m_linearJointAxis.set(jointAxis);
        m_aJ.set(world2A.mul(rel_pos1.cross(m_linearJointAxis)));
        m_bJ.set(world2B.mul(rel_pos2.cross(m_linearJointAxis.negate())));
        m_0MinvJt.set(inertiaInvA.mul(m_aJ));
        m_1MinvJt.set(inertiaInvB.mul(m_bJ));
        m_Adiag = massInvA + m_0MinvJt.dot(m_aJ) + massInvB + m_1MinvJt.dot(m_bJ);
    }

    /** angular constraint between two different rigidbodies */
    public btJacobianEntry(
            btVector3 jointAxis,
            btMatrix3x3 world2A,
            btMatrix3x3 world2B,
            btVector3 inertiaInvA,
            btVector3 inertiaInvB) {
        m_linearJointAxis.set(new btVector3(0., 0., 0.));
        m_aJ.set(world2A.mul(jointAxis));
        m_bJ.set(world2B.mul(jointAxis.negate()));
        m_0MinvJt.set(inertiaInvA.mul(m_aJ));
        m_1MinvJt.set(inertiaInvB.mul(m_bJ));
        m_Adiag = m_0MinvJt.dot(m_aJ) + m_1MinvJt.dot(m_bJ);
    }

    /** angular constraint between two different rigidbodies */
    public btJacobianEntry(
            btVector3 axisInA, btVector3 axisInB, btVector3 inertiaInvA, btVector3 inertiaInvB) {
        m_linearJointAxis.set(new btVector3(0., 0., 0.));
        m_aJ.set(axisInA);
        m_bJ.set(axisInB.negate());
        m_0MinvJt.set(inertiaInvA.mul(m_aJ));
        m_1MinvJt.set(inertiaInvB.mul(m_bJ));
        m_Adiag = m_0MinvJt.dot(m_aJ) + m_1MinvJt.dot(m_bJ);
    }

    /** constraint on one rigidbody */
    public btJacobianEntry(
            btMatrix3x3 world2A,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btVector3 jointAxis,
            btVector3 inertiaInvA,
            double massInvA) {
        m_linearJointAxis.set(jointAxis);
        m_aJ.set(world2A.mul(rel_pos1.cross(jointAxis)));
        m_bJ.set(world2A.mul(rel_pos2.cross(jointAxis.negate())));
        m_0MinvJt.set(inertiaInvA.mul(m_aJ));
        m_1MinvJt.set(new btVector3(0., 0., 0.));
        m_Adiag = massInvA + m_0MinvJt.dot(m_aJ);
    }

    /** implicit copy assignment */
    public btJacobianEntry set(btJacobianEntry o) {
        m_linearJointAxis.set(o.m_linearJointAxis);
        m_aJ.set(o.m_aJ);
        m_bJ.set(o.m_bJ);
        m_0MinvJt.set(o.m_0MinvJt);
        m_1MinvJt.set(o.m_1MinvJt);
        m_Adiag = o.m_Adiag;
        return this;
    }

    public double getDiagonal() {
        return m_Adiag;
    }

    /** for two constraints on the same rigidbody (for example vehicle friction) */
    public double getNonDiagonal(btJacobianEntry jacB, double massInvA) {
        final btJacobianEntry jacA = this;
        double lin = massInvA * jacA.m_linearJointAxis.dot(jacB.m_linearJointAxis);
        double ang = jacA.m_0MinvJt.dot(jacB.m_aJ);
        return lin + ang;
    }

    /** for two constraints on sharing two same rigidbodies (for example two contact points) */
    public double getNonDiagonal(btJacobianEntry jacB, double massInvA, double massInvB) {
        final btJacobianEntry jacA = this;
        btVector3 lin = jacA.m_linearJointAxis.mul(jacB.m_linearJointAxis);
        btVector3 ang0 = jacA.m_0MinvJt.mul(jacB.m_aJ);
        btVector3 ang1 = jacA.m_1MinvJt.mul(jacB.m_bJ);
        btVector3 lin0 = lin.mul(massInvA);
        btVector3 lin1 = lin.mul(massInvB);
        btVector3 sum = ang0.add(ang1).add(lin0).add(lin1);
        return sum.get(0) + sum.get(1) + sum.get(2);
    }

    public double getRelativeVelocity(
            btVector3 linvelA, btVector3 angvelA, btVector3 linvelB, btVector3 angvelB) {
        btVector3 linrel = linvelA.sub(linvelB);
        btVector3 angvela = angvelA.mul(m_aJ);
        btVector3 angvelb = angvelB.mul(m_bJ);
        linrel.mulLocal(m_linearJointAxis);
        angvela.addLocal(angvelb);
        angvela.addLocal(linrel);
        double rel_vel2 = angvela.get(0) + angvela.get(1) + angvela.get(2);
        return rel_vel2 + btScalar.SIMD_EPSILON;
    }
}
