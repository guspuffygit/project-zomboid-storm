// Port of BulletDynamics/ConstraintSolver/btContactConstraint.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btContactConstraint can be automatically created to solve contact constraints using the unified
 * btTypedConstraint interface
 */
public class btContactConstraint extends btTypedConstraint {
    /** C++ holds the manifold by value; Java keeps an owned instance and copies into it. */
    protected final btPersistentManifold m_contactManifold = new btPersistentManifold();

    public btContactConstraint(
            btPersistentManifold contactManifold, btRigidBody rbA, btRigidBody rbB) {
        super(CONTACT_CONSTRAINT_TYPE, rbA, rbB);
        copyManifold(m_contactManifold, contactManifold);
    }

    /** implicit {@code btPersistentManifold::operator=} (memberwise copy). */
    private static void copyManifold(btPersistentManifold dst, btPersistentManifold src) {
        dst.m_objectType = src.m_objectType;
        for (int i = 0; i < btPersistentManifold.MANIFOLD_CACHE_SIZE; i++) {
            dst.m_pointCache[i].set(src.m_pointCache[i]);
        }
        dst.m_body0 = src.m_body0;
        dst.m_body1 = src.m_body1;
        dst.m_cachedPoints = src.m_cachedPoints;
        dst.m_contactBreakingThreshold = src.m_contactBreakingThreshold;
        dst.m_contactProcessingThreshold = src.m_contactProcessingThreshold;
        dst.m_companionIdA = src.m_companionIdA;
        dst.m_companionIdB = src.m_companionIdB;
        dst.m_index1a = src.m_index1a;
    }

    public void setContactManifold(btPersistentManifold contactManifold) {
        copyManifold(m_contactManifold, contactManifold);
    }

    public btPersistentManifold getContactManifold() {
        return m_contactManifold;
    }

    @Override
    public void getInfo1(btConstraintInfo1 info) {}

    @Override
    public void getInfo2(btConstraintInfo2 info) {}

    /** obsolete methods */
    @Override
    public void buildJacobian() {}

    /** btTypedConstraint::setParam is pure virtual; btContactConstraint does not override it. */
    @Override
    public void setParam(int num, double value, int axis) {
        throw new UnsupportedOperationException("pure virtual btTypedConstraint::setParam");
    }

    /** btTypedConstraint::getParam is pure virtual; btContactConstraint does not override it. */
    @Override
    public double getParam(int num, int axis) {
        throw new UnsupportedOperationException("pure virtual btTypedConstraint::getParam");
    }

    /**
     * response between two dynamic objects without friction and no restitution, assuming 0
     * penetration depth
     */
    public static double resolveSingleCollision(
            btRigidBody body1,
            btCollisionObject colObj2,
            btVector3 contactPositionWorld,
            btVector3 contactNormalOnB,
            btContactSolverInfo solverInfo,
            double distance) {
        btRigidBody body2 = btRigidBody.upcast(colObj2);

        btVector3 normal = contactNormalOnB;

        btVector3 rel_pos1 = contactPositionWorld.sub(body1.getWorldTransform().getOrigin());
        btVector3 rel_pos2 = contactPositionWorld.sub(colObj2.getWorldTransform().getOrigin());

        btVector3 vel1 = body1.getVelocityInLocalPoint(rel_pos1);
        btVector3 vel2 =
                body2 != null ? body2.getVelocityInLocalPoint(rel_pos2) : new btVector3(0, 0, 0);
        btVector3 vel = vel1.sub(vel2);
        double rel_vel;
        rel_vel = normal.dot(vel);

        double combinedRestitution = (double) 0.f;
        double restitution = combinedRestitution * -rel_vel;

        double positionalError = solverInfo.m_erp * -distance / solverInfo.m_timeStep;
        double velocityError = -((double) 1.0f + restitution) * rel_vel; // * damping;
        double denom0 = body1.computeImpulseDenominator(contactPositionWorld, normal);
        double denom1 =
                body2 != null
                        ? body2.computeImpulseDenominator(contactPositionWorld, normal)
                        : (double) 0.f;
        double relaxation = (double) 1.f;
        double jacDiagABInv = relaxation / (denom0 + denom1);

        double penetrationImpulse = positionalError * jacDiagABInv;
        double velocityImpulse = velocityError * jacDiagABInv;

        double normalImpulse = penetrationImpulse + velocityImpulse;
        normalImpulse = 0.f > normalImpulse ? (double) 0.f : normalImpulse;

        body1.applyImpulse(normal.mul(normalImpulse), rel_pos1);
        if (body2 != null) body2.applyImpulse(normal.negate().mul(normalImpulse), rel_pos2);

        return normalImpulse;
    }

    /**
     * bilateral constraint between two dynamic objects. resolveSingleBilateral is an obsolete
     * method used for vehicle friction between two dynamic objects. {@code impulse} is the C++
     * {@code btScalar&} out-param, written to {@code impulse[0]}.
     */
    public static void resolveSingleBilateral(
            btRigidBody body1,
            btVector3 pos1,
            btRigidBody body2,
            btVector3 pos2,
            double distance,
            btVector3 normal,
            double[] impulse,
            double timeStep) {
        double normalLenSqr = normal.length2();
        if (normalLenSqr > 1.1) {
            impulse[0] = 0.;
            return;
        }
        btVector3 rel_pos1 = pos1.sub(body1.getCenterOfMassPosition());
        btVector3 rel_pos2 = pos2.sub(body2.getCenterOfMassPosition());
        // this jacobian entry could be re-used for all iterations

        btVector3 vel1 = body1.getVelocityInLocalPoint(rel_pos1);
        btVector3 vel2 = body2.getVelocityInLocalPoint(rel_pos2);
        btVector3 vel = vel1.sub(vel2);

        btJacobianEntry jac =
                new btJacobianEntry(
                        body1.getCenterOfMassTransform().getBasis().transpose(),
                        body2.getCenterOfMassTransform().getBasis().transpose(),
                        rel_pos1,
                        rel_pos2,
                        normal,
                        body1.getInvInertiaDiagLocal(),
                        body1.getInvMass(),
                        body2.getInvInertiaDiagLocal(),
                        body2.getInvMass());

        double jacDiagAB = jac.getDiagonal();
        double jacDiagABInv = 1. / jacDiagAB;

        double rel_vel =
                jac.getRelativeVelocity(
                        body1.getLinearVelocity(),
                        body1.getCenterOfMassTransform()
                                .getBasis()
                                .transpose()
                                .mul(body1.getAngularVelocity()),
                        body2.getLinearVelocity(),
                        body2.getCenterOfMassTransform()
                                .getBasis()
                                .transpose()
                                .mul(body2.getAngularVelocity()));

        rel_vel = normal.dot(vel);

        // todo: move this into proper structure
        double contactDamping = 0.2;

        double velocityImpulse = -contactDamping * rel_vel * jacDiagABInv;
        impulse[0] = velocityImpulse;
    }
}
