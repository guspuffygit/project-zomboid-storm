// Port of BulletDynamics/ConstraintSolver/btSolverBody.h (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * struct btSolverBody. USE_SIMD is not defined in the PZ build (no BT_USE_SSE with double
 * precision), so btSimdScalar is btScalar. Value type: {@link #set} is the implicit copy
 * assignment.
 */
public class btSolverBody {
    public final btTransform m_worldTransform = new btTransform();
    public final btVector3 m_deltaLinearVelocity = new btVector3();
    public final btVector3 m_deltaAngularVelocity = new btVector3();
    public final btVector3 m_angularFactor = new btVector3();
    public final btVector3 m_linearFactor = new btVector3();
    public final btVector3 m_invMass = new btVector3();
    public final btVector3 m_pushVelocity = new btVector3();
    public final btVector3 m_turnVelocity = new btVector3();
    public final btVector3 m_linearVelocity = new btVector3();
    public final btVector3 m_angularVelocity = new btVector3();
    public final btVector3 m_externalForceImpulse = new btVector3();
    public final btVector3 m_externalTorqueImpulse = new btVector3();
    public btRigidBody m_originalBody;

    public btSolverBody() {}

    public btSolverBody(btSolverBody other) {
        set(other);
    }

    /** implicit copy assignment */
    public btSolverBody set(btSolverBody o) {
        m_worldTransform.set(o.m_worldTransform);
        m_deltaLinearVelocity.set(o.m_deltaLinearVelocity);
        m_deltaAngularVelocity.set(o.m_deltaAngularVelocity);
        m_angularFactor.set(o.m_angularFactor);
        m_linearFactor.set(o.m_linearFactor);
        m_invMass.set(o.m_invMass);
        m_pushVelocity.set(o.m_pushVelocity);
        m_turnVelocity.set(o.m_turnVelocity);
        m_linearVelocity.set(o.m_linearVelocity);
        m_angularVelocity.set(o.m_angularVelocity);
        m_externalForceImpulse.set(o.m_externalForceImpulse);
        m_externalTorqueImpulse.set(o.m_externalTorqueImpulse);
        m_originalBody = o.m_originalBody;
        return this;
    }

    public void setWorldTransform(btTransform worldTransform) {
        m_worldTransform.set(worldTransform);
    }

    public btTransform getWorldTransform() {
        return m_worldTransform;
    }

    public void getVelocityInLocalPointNoDelta(btVector3 rel_pos, btVector3 velocity) {
        if (m_originalBody != null)
            velocity.set(
                    m_linearVelocity
                            .add(m_externalForceImpulse)
                            .add(m_angularVelocity.add(m_externalTorqueImpulse).cross(rel_pos)));
        else velocity.setValue(0, 0, 0);
    }

    public void getVelocityInLocalPointObsolete(btVector3 rel_pos, btVector3 velocity) {
        if (m_originalBody != null)
            velocity.set(
                    m_linearVelocity
                            .add(m_deltaLinearVelocity)
                            .add(m_angularVelocity.add(m_deltaAngularVelocity).cross(rel_pos)));
        else velocity.setValue(0, 0, 0);
    }

    public void getAngularVelocity(btVector3 angVel) {
        if (m_originalBody != null) angVel.set(m_angularVelocity.add(m_deltaAngularVelocity));
        else angVel.setValue(0, 0, 0);
    }

    public void applyImpulse(
            btVector3 linearComponent, btVector3 angularComponent, double impulseMagnitude) {
        if (m_originalBody != null) {
            m_deltaLinearVelocity.addLocal(
                    linearComponent.mul(impulseMagnitude).mul(m_linearFactor));
            m_deltaAngularVelocity.addLocal(
                    angularComponent.mul(m_angularFactor.mul(impulseMagnitude)));
        }
    }

    public void internalApplyPushImpulse(
            btVector3 linearComponent, btVector3 angularComponent, double impulseMagnitude) {
        if (m_originalBody != null) {
            m_pushVelocity.addLocal(linearComponent.mul(impulseMagnitude).mul(m_linearFactor));
            m_turnVelocity.addLocal(angularComponent.mul(m_angularFactor.mul(impulseMagnitude)));
        }
    }

    public btVector3 getDeltaLinearVelocity() {
        return m_deltaLinearVelocity;
    }

    public btVector3 getDeltaAngularVelocity() {
        return m_deltaAngularVelocity;
    }

    public btVector3 getPushVelocity() {
        return m_pushVelocity;
    }

    public btVector3 getTurnVelocity() {
        return m_turnVelocity;
    }

    public btVector3 internalGetDeltaLinearVelocity() {
        return m_deltaLinearVelocity;
    }

    public btVector3 internalGetDeltaAngularVelocity() {
        return m_deltaAngularVelocity;
    }

    public btVector3 internalGetAngularFactor() {
        return m_angularFactor;
    }

    public btVector3 internalGetInvMass() {
        return m_invMass;
    }

    public void internalSetInvMass(btVector3 invMass) {
        m_invMass.set(invMass);
    }

    public btVector3 internalGetPushVelocity() {
        return m_pushVelocity;
    }

    public btVector3 internalGetTurnVelocity() {
        return m_turnVelocity;
    }

    public void internalGetVelocityInLocalPointObsolete(btVector3 rel_pos, btVector3 velocity) {
        velocity.set(
                m_linearVelocity
                        .add(m_deltaLinearVelocity)
                        .add(m_angularVelocity.add(m_deltaAngularVelocity).cross(rel_pos)));
    }

    public void internalGetAngularVelocity(btVector3 angVel) {
        angVel.set(m_angularVelocity.add(m_deltaAngularVelocity));
    }

    public void internalApplyImpulse(
            btVector3 linearComponent, btVector3 angularComponent, double impulseMagnitude) {
        if (m_originalBody != null) {
            m_deltaLinearVelocity.addLocal(
                    linearComponent.mul(impulseMagnitude).mul(m_linearFactor));
            m_deltaAngularVelocity.addLocal(
                    angularComponent.mul(m_angularFactor.mul(impulseMagnitude)));
        }
    }

    public void writebackVelocity() {
        if (m_originalBody != null) {
            m_linearVelocity.addLocal(m_deltaLinearVelocity);
            m_angularVelocity.addLocal(m_deltaAngularVelocity);
        }
    }

    public void writebackVelocityAndTransform(double timeStep, double splitImpulseTurnErp) {
        if (m_originalBody != null) {
            m_linearVelocity.addLocal(m_deltaLinearVelocity);
            m_angularVelocity.addLocal(m_deltaAngularVelocity);

            final btTransform newTransform = new btTransform();
            if (m_pushVelocity.x != 0.f
                    || m_pushVelocity.y != 0
                    || m_pushVelocity.z != 0
                    || m_turnVelocity.x != 0.f
                    || m_turnVelocity.y != 0
                    || m_turnVelocity.z != 0) {
                btTransformUtil.integrateTransform(
                        m_worldTransform,
                        m_pushVelocity,
                        m_turnVelocity.mul(splitImpulseTurnErp),
                        timeStep,
                        newTransform);
                m_worldTransform.set(newTransform);
            }
        }
    }
}
