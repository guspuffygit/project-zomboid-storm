// Port of BulletDynamics/ConstraintSolver/btSequentialImpulseConstraintSolver.{h,cpp} (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.collision.broadphase.btDispatcher;
import io.pzstorm.storm.bullet.collision.dispatch.btCollisionObject;
import io.pzstorm.storm.bullet.collision.narrowphase.btManifoldPoint;
import io.pzstorm.storm.bullet.collision.narrowphase.btPersistentManifold;
import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btIDebugDraw;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * The btSequentialImpulseConstraintSolver is a fast SIMD implementation of the Projected Gauss
 * Seidel (iterative LCP) method. USE_SIMD is not defined in the shipped build, so every *SIMD
 * resolver forwards to its scalar counterpart (as in the binary).
 *
 * <p>{@code T**} parameters use the {@link btConstraintSolver} convention (Java array, index 0 =
 * C++ pointer base). {@code btScalar& relaxation} is a {@code double[1]}.
 */
public class btSequentialImpulseConstraintSolver extends btConstraintSolver {
    /** global {@code int gNumSplitImpulseRecoveries} */
    public static int gNumSplitImpulseRecoveries = 0;

    protected final btAlignedObjectArray<btSolverBody> m_tmpSolverBodyPool =
            new btAlignedObjectArray<>(btSolverBody::new, btSolverBody::set);
    protected final btAlignedObjectArray<btSolverConstraint> m_tmpSolverContactConstraintPool =
            newConstraintArray();
    protected final btAlignedObjectArray<btSolverConstraint> m_tmpSolverNonContactConstraintPool =
            newConstraintArray();
    protected final btAlignedObjectArray<btSolverConstraint>
            m_tmpSolverContactFrictionConstraintPool = newConstraintArray();
    protected final btAlignedObjectArray<btSolverConstraint>
            m_tmpSolverContactRollingFrictionConstraintPool = newConstraintArray();

    protected final btAlignedObjectArray<Integer> m_orderTmpConstraintPool =
            new btAlignedObjectArray<>();
    protected final btAlignedObjectArray<Integer> m_orderNonContactConstraintPool =
            new btAlignedObjectArray<>();
    protected final btAlignedObjectArray<Integer> m_orderFrictionConstraintPool =
            new btAlignedObjectArray<>();
    protected final btAlignedObjectArray<btTypedConstraint.btConstraintInfo1>
            m_tmpConstraintSizesPool =
                    new btAlignedObjectArray<>(
                            btTypedConstraint.btConstraintInfo1::new,
                            (d, s) -> {
                                d.m_numConstraintRows = s.m_numConstraintRows;
                                d.nub = s.nub;
                            });
    protected int m_maxOverrideNumSolverIterations;
    protected int m_fixedBodyId;

    /**
     * m_btSeed2 is used for re-arranging the constraint rows. improves convergence/quality of
     * friction. C++ {@code unsigned long} (64-bit on the x86-64 Linux build).
     */
    protected long m_btSeed2;

    /** typedef btAlignedObjectArray&lt;btSolverConstraint&gt; btConstraintArray */
    private static btAlignedObjectArray<btSolverConstraint> newConstraintArray() {
        return new btAlignedObjectArray<>(btSolverConstraint::new, btSolverConstraint::set);
    }

    public btSequentialImpulseConstraintSolver() {
        m_btSeed2 = 0;
    }

    /** virtual ~btSequentialImpulseConstraintSolver() */
    @Override
    public void destroy() {}

    // Project Gauss Seidel or the equivalent Sequential Impulse
    protected void resolveSingleConstraintRowGenericSIMD(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        // USE_SIMD not defined
        resolveSingleConstraintRowGeneric(body1, body2, c);
    }

    // Project Gauss Seidel or the equivalent Sequential Impulse
    protected void resolveSingleConstraintRowGeneric(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        double deltaImpulse = c.m_rhs - c.m_appliedImpulse * c.m_cfm;
        final double deltaVel1Dotn =
                c.m_contactNormal1.dot(body1.internalGetDeltaLinearVelocity())
                        + c.m_relpos1CrossNormal.dot(body1.internalGetDeltaAngularVelocity());
        final double deltaVel2Dotn =
                c.m_contactNormal2.dot(body2.internalGetDeltaLinearVelocity())
                        + c.m_relpos2CrossNormal.dot(body2.internalGetDeltaAngularVelocity());

        //	const btScalar delta_rel_vel	=	deltaVel1Dotn-deltaVel2Dotn;
        deltaImpulse -= deltaVel1Dotn * c.m_jacDiagABInv;
        deltaImpulse -= deltaVel2Dotn * c.m_jacDiagABInv;

        final double sum = c.m_appliedImpulse + deltaImpulse;
        if (sum < c.m_lowerLimit) {
            deltaImpulse = c.m_lowerLimit - c.m_appliedImpulse;
            c.m_appliedImpulse = c.m_lowerLimit;
        } else if (sum > c.m_upperLimit) {
            deltaImpulse = c.m_upperLimit - c.m_appliedImpulse;
            c.m_appliedImpulse = c.m_upperLimit;
        } else {
            c.m_appliedImpulse = sum;
        }

        body1.internalApplyImpulse(
                c.m_contactNormal1.mul(body1.internalGetInvMass()),
                c.m_angularComponentA,
                deltaImpulse);
        body2.internalApplyImpulse(
                c.m_contactNormal2.mul(body2.internalGetInvMass()),
                c.m_angularComponentB,
                deltaImpulse);
    }

    protected void resolveSingleConstraintRowLowerLimitSIMD(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        // USE_SIMD not defined
        resolveSingleConstraintRowLowerLimit(body1, body2, c);
    }

    // Projected Gauss Seidel or the equivalent Sequential Impulse
    protected void resolveSingleConstraintRowLowerLimit(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        double deltaImpulse = c.m_rhs - c.m_appliedImpulse * c.m_cfm;
        final double deltaVel1Dotn =
                c.m_contactNormal1.dot(body1.internalGetDeltaLinearVelocity())
                        + c.m_relpos1CrossNormal.dot(body1.internalGetDeltaAngularVelocity());
        final double deltaVel2Dotn =
                c.m_contactNormal2.dot(body2.internalGetDeltaLinearVelocity())
                        + c.m_relpos2CrossNormal.dot(body2.internalGetDeltaAngularVelocity());

        deltaImpulse -= deltaVel1Dotn * c.m_jacDiagABInv;
        deltaImpulse -= deltaVel2Dotn * c.m_jacDiagABInv;
        final double sum = c.m_appliedImpulse + deltaImpulse;
        if (sum < c.m_lowerLimit) {
            deltaImpulse = c.m_lowerLimit - c.m_appliedImpulse;
            c.m_appliedImpulse = c.m_lowerLimit;
        } else {
            c.m_appliedImpulse = sum;
        }
        body1.internalApplyImpulse(
                c.m_contactNormal1.mul(body1.internalGetInvMass()),
                c.m_angularComponentA,
                deltaImpulse);
        body2.internalApplyImpulse(
                c.m_contactNormal2.mul(body2.internalGetInvMass()),
                c.m_angularComponentB,
                deltaImpulse);
    }

    protected void resolveSplitPenetrationImpulseCacheFriendly(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        if (c.m_rhsPenetration != 0) {
            gNumSplitImpulseRecoveries++;
            double deltaImpulse = c.m_rhsPenetration - c.m_appliedPushImpulse * c.m_cfm;
            final double deltaVel1Dotn =
                    c.m_contactNormal1.dot(body1.internalGetPushVelocity())
                            + c.m_relpos1CrossNormal.dot(body1.internalGetTurnVelocity());
            final double deltaVel2Dotn =
                    c.m_contactNormal2.dot(body2.internalGetPushVelocity())
                            + c.m_relpos2CrossNormal.dot(body2.internalGetTurnVelocity());

            deltaImpulse -= deltaVel1Dotn * c.m_jacDiagABInv;
            deltaImpulse -= deltaVel2Dotn * c.m_jacDiagABInv;
            final double sum = c.m_appliedPushImpulse + deltaImpulse;
            if (sum < c.m_lowerLimit) {
                deltaImpulse = c.m_lowerLimit - c.m_appliedPushImpulse;
                c.m_appliedPushImpulse = c.m_lowerLimit;
            } else {
                c.m_appliedPushImpulse = sum;
            }
            body1.internalApplyPushImpulse(
                    c.m_contactNormal1.mul(body1.internalGetInvMass()),
                    c.m_angularComponentA,
                    deltaImpulse);
            body2.internalApplyPushImpulse(
                    c.m_contactNormal2.mul(body2.internalGetInvMass()),
                    c.m_angularComponentB,
                    deltaImpulse);
        }
    }

    protected void resolveSplitPenetrationSIMD(
            btSolverBody body1, btSolverBody body2, btSolverConstraint c) {
        // USE_SIMD not defined
        resolveSplitPenetrationImpulseCacheFriendly(body1, body2, c);
    }

    /** {@code unsigned long btRand2()} */
    public long btRand2() {
        m_btSeed2 = (1664525L * m_btSeed2 + 1013904223L) & 0xffffffffL;
        return m_btSeed2;
    }

    // See ODE: adam's all-int straightforward(?) dRandInt (0..n-1)
    public int btRandInt2(int n) {
        // seems good; xor-fold and modulus
        final long un = (long) n; // static_cast<unsigned long>(n)
        long r = btRand2();

        // note: probably more aggressive than it needs to be -- might be
        //       able to get away without one or two of the innermost branches.
        if (Long.compareUnsigned(un, 0x00010000L) <= 0) {
            r ^= (r >>> 16);
            if (Long.compareUnsigned(un, 0x00000100L) <= 0) {
                r ^= (r >>> 8);
                if (Long.compareUnsigned(un, 0x00000010L) <= 0) {
                    r ^= (r >>> 4);
                    if (Long.compareUnsigned(un, 0x00000004L) <= 0) {
                        r ^= (r >>> 2);
                        if (Long.compareUnsigned(un, 0x00000002L) <= 0) {
                            r ^= (r >>> 1);
                        }
                    }
                }
            }
        }

        return (int) Long.remainderUnsigned(r, un);
    }

    protected void initSolverBody(
            btSolverBody solverBody, btCollisionObject collisionObject, double timeStep) {
        btRigidBody rb = collisionObject != null ? btRigidBody.upcast(collisionObject) : null;

        solverBody.internalGetDeltaLinearVelocity().setValue(0.f, 0.f, 0.f);
        solverBody.internalGetDeltaAngularVelocity().setValue(0.f, 0.f, 0.f);
        solverBody.internalGetPushVelocity().setValue(0.f, 0.f, 0.f);
        solverBody.internalGetTurnVelocity().setValue(0.f, 0.f, 0.f);

        if (rb != null) {
            solverBody.m_worldTransform.set(rb.getWorldTransform());
            solverBody.internalSetInvMass(
                    new btVector3(rb.getInvMass(), rb.getInvMass(), rb.getInvMass())
                            .mul(rb.getLinearFactor()));
            solverBody.m_originalBody = rb;
            solverBody.m_angularFactor.set(rb.getAngularFactor());
            solverBody.m_linearFactor.set(rb.getLinearFactor());
            solverBody.m_linearVelocity.set(rb.getLinearVelocity());
            solverBody.m_angularVelocity.set(rb.getAngularVelocity());
            solverBody.m_externalForceImpulse.set(
                    rb.getTotalForce().mul(rb.getInvMass()).mul(timeStep));
            solverBody.m_externalTorqueImpulse.set(
                    btMatrix3x3
                            .mul(rb.getTotalTorque(), rb.getInvInertiaTensorWorld())
                            .mul(timeStep));
        } else {
            solverBody.m_worldTransform.setIdentity();
            solverBody.internalSetInvMass(new btVector3(0, 0, 0));
            solverBody.m_originalBody = null;
            solverBody.m_angularFactor.setValue(1, 1, 1);
            solverBody.m_linearFactor.setValue(1, 1, 1);
            solverBody.m_linearVelocity.setValue(0, 0, 0);
            solverBody.m_angularVelocity.setValue(0, 0, 0);
            solverBody.m_externalForceImpulse.setValue(0, 0, 0);
            solverBody.m_externalTorqueImpulse.setValue(0, 0, 0);
        }
    }

    protected double restitutionCurve(double rel_vel, double restitution) {
        double rest = restitution * -rel_vel;
        return rest;
    }

    protected static void applyAnisotropicFriction(
            btCollisionObject colObj, btVector3 frictionDirection, int frictionMode) {
        if (colObj != null && colObj.hasAnisotropicFriction(frictionMode)) {
            // transform to local coordinates
            btVector3 loc_lateral =
                    btMatrix3x3.mul(frictionDirection, colObj.getWorldTransform().getBasis());
            final btVector3 friction_scaling = colObj.getAnisotropicFriction();
            // apply anisotropic friction
            loc_lateral.mulLocal(friction_scaling);
            // ... and transform it back to global coordinates
            frictionDirection.set(colObj.getWorldTransform().getBasis().mul(loc_lateral));
        }
    }

    protected void setupFrictionConstraint(
            btSolverConstraint solverConstraint,
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation) {
        setupFrictionConstraint(
                solverConstraint,
                normalAxis,
                solverBodyIdA,
                solverBodyIdB,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                0.,
                0.);
    }

    protected void setupFrictionConstraint(
            btSolverConstraint solverConstraint,
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation,
            double desiredVelocity,
            double cfmSlip) {
        btSolverBody solverBodyA = m_tmpSolverBodyPool.get(solverBodyIdA);
        btSolverBody solverBodyB = m_tmpSolverBodyPool.get(solverBodyIdB);

        btRigidBody body0 = m_tmpSolverBodyPool.get(solverBodyIdA).m_originalBody;
        btRigidBody body1 = m_tmpSolverBodyPool.get(solverBodyIdB).m_originalBody;

        solverConstraint.m_solverBodyIdA = solverBodyIdA;
        solverConstraint.m_solverBodyIdB = solverBodyIdB;

        solverConstraint.m_friction = cp.m_combinedFriction;
        solverConstraint.m_originalContactPoint = null;

        solverConstraint.m_appliedImpulse = 0.f;
        solverConstraint.m_appliedPushImpulse = 0.f;

        if (body0 != null) {
            solverConstraint.m_contactNormal1.set(normalAxis);
            btVector3 ftorqueAxis1 = rel_pos1.cross(solverConstraint.m_contactNormal1);
            solverConstraint.m_relpos1CrossNormal.set(ftorqueAxis1);
            solverConstraint.m_angularComponentA.set(
                    body0.getInvInertiaTensorWorld()
                            .mul(ftorqueAxis1)
                            .mul(body0.getAngularFactor()));
        } else {
            solverConstraint.m_contactNormal1.setZero();
            solverConstraint.m_relpos1CrossNormal.setZero();
            solverConstraint.m_angularComponentA.setZero();
        }

        if (body1 != null) {
            solverConstraint.m_contactNormal2.set(normalAxis.negate());
            btVector3 ftorqueAxis1 = rel_pos2.cross(solverConstraint.m_contactNormal2);
            solverConstraint.m_relpos2CrossNormal.set(ftorqueAxis1);
            solverConstraint.m_angularComponentB.set(
                    body1.getInvInertiaTensorWorld()
                            .mul(ftorqueAxis1)
                            .mul(body1.getAngularFactor()));
        } else {
            solverConstraint.m_contactNormal2.setZero();
            solverConstraint.m_relpos2CrossNormal.setZero();
            solverConstraint.m_angularComponentB.setZero();
        }

        {
            btVector3 vec;
            double denom0 = 0.f;
            double denom1 = 0.f;
            if (body0 != null) {
                vec = (solverConstraint.m_angularComponentA).cross(rel_pos1);
                denom0 = body0.getInvMass() + normalAxis.dot(vec);
            }
            if (body1 != null) {
                vec = (solverConstraint.m_angularComponentB.negate()).cross(rel_pos2);
                denom1 = body1.getInvMass() + normalAxis.dot(vec);
            }
            double denom = relaxation / (denom0 + denom1);
            solverConstraint.m_jacDiagABInv = denom;
        }

        {
            double rel_vel;
            double vel1Dotn =
                    solverConstraint.m_contactNormal1.dot(
                                    body0 != null
                                            ? solverBodyA.m_linearVelocity.add(
                                                    solverBodyA.m_externalForceImpulse)
                                            : new btVector3(0, 0, 0))
                            + solverConstraint.m_relpos1CrossNormal.dot(
                                    body0 != null
                                            ? solverBodyA.m_angularVelocity
                                            : new btVector3(0, 0, 0));
            double vel2Dotn =
                    solverConstraint.m_contactNormal2.dot(
                                    body1 != null
                                            ? solverBodyB.m_linearVelocity.add(
                                                    solverBodyB.m_externalForceImpulse)
                                            : new btVector3(0, 0, 0))
                            + solverConstraint.m_relpos2CrossNormal.dot(
                                    body1 != null
                                            ? solverBodyB.m_angularVelocity
                                            : new btVector3(0, 0, 0));

            rel_vel = vel1Dotn + vel2Dotn;

            //		btScalar positionalError = 0.f;

            double velocityError = desiredVelocity - rel_vel;
            double velocityImpulse = velocityError * solverConstraint.m_jacDiagABInv;
            solverConstraint.m_rhs = velocityImpulse;
            solverConstraint.m_cfm = cfmSlip;
            solverConstraint.m_lowerLimit = -solverConstraint.m_friction;
            solverConstraint.m_upperLimit = solverConstraint.m_friction;
        }
    }

    protected btSolverConstraint addFrictionConstraint(
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            int frictionIndex,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation) {
        return addFrictionConstraint(
                normalAxis,
                solverBodyIdA,
                solverBodyIdB,
                frictionIndex,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                0.,
                0.);
    }

    protected btSolverConstraint addFrictionConstraint(
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            int frictionIndex,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation,
            double desiredVelocity,
            double cfmSlip) {
        btSolverConstraint solverConstraint =
                m_tmpSolverContactFrictionConstraintPool.expandNonInitializing();
        solverConstraint.m_frictionIndex = frictionIndex;
        setupFrictionConstraint(
                solverConstraint,
                normalAxis,
                solverBodyIdA,
                solverBodyIdB,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                desiredVelocity,
                cfmSlip);
        return solverConstraint;
    }

    protected void setupRollingFrictionConstraint(
            btSolverConstraint solverConstraint,
            btVector3 normalAxis1,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation) {
        setupRollingFrictionConstraint(
                solverConstraint,
                normalAxis1,
                solverBodyIdA,
                solverBodyIdB,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                0.,
                0.);
    }

    protected void setupRollingFrictionConstraint(
            btSolverConstraint solverConstraint,
            btVector3 normalAxis1,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation,
            double desiredVelocity,
            double cfmSlip) {
        btVector3 normalAxis = new btVector3(0, 0, 0);

        solverConstraint.m_contactNormal1.set(normalAxis);
        solverConstraint.m_contactNormal2.set(normalAxis.negate());
        btSolverBody solverBodyA = m_tmpSolverBodyPool.get(solverBodyIdA);
        btSolverBody solverBodyB = m_tmpSolverBodyPool.get(solverBodyIdB);

        btRigidBody body0 = m_tmpSolverBodyPool.get(solverBodyIdA).m_originalBody;
        btRigidBody body1 = m_tmpSolverBodyPool.get(solverBodyIdB).m_originalBody;

        solverConstraint.m_solverBodyIdA = solverBodyIdA;
        solverConstraint.m_solverBodyIdB = solverBodyIdB;

        solverConstraint.m_friction = cp.m_combinedRollingFriction;
        solverConstraint.m_originalContactPoint = null;

        solverConstraint.m_appliedImpulse = 0.f;
        solverConstraint.m_appliedPushImpulse = 0.f;

        {
            btVector3 ftorqueAxis1 = normalAxis1.negate();
            solverConstraint.m_relpos1CrossNormal.set(ftorqueAxis1);
            solverConstraint.m_angularComponentA.set(
                    body0 != null
                            ? body0.getInvInertiaTensorWorld()
                                    .mul(ftorqueAxis1)
                                    .mul(body0.getAngularFactor())
                            : new btVector3(0, 0, 0));
        }
        {
            btVector3 ftorqueAxis1 = normalAxis1;
            solverConstraint.m_relpos2CrossNormal.set(ftorqueAxis1);
            solverConstraint.m_angularComponentB.set(
                    body1 != null
                            ? body1.getInvInertiaTensorWorld()
                                    .mul(ftorqueAxis1)
                                    .mul(body1.getAngularFactor())
                            : new btVector3(0, 0, 0));
        }

        {
            btVector3 iMJaA =
                    body0 != null
                            ? body0.getInvInertiaTensorWorld()
                                    .mul(solverConstraint.m_relpos1CrossNormal)
                            : new btVector3(0, 0, 0);
            btVector3 iMJaB =
                    body1 != null
                            ? body1.getInvInertiaTensorWorld()
                                    .mul(solverConstraint.m_relpos2CrossNormal)
                            : new btVector3(0, 0, 0);
            double sum = 0;
            sum += iMJaA.dot(solverConstraint.m_relpos1CrossNormal);
            sum += iMJaB.dot(solverConstraint.m_relpos2CrossNormal);
            solverConstraint.m_jacDiagABInv = 1. / sum;
        }

        {
            double rel_vel;
            double vel1Dotn =
                    solverConstraint.m_contactNormal1.dot(
                                    body0 != null
                                            ? solverBodyA.m_linearVelocity.add(
                                                    solverBodyA.m_externalForceImpulse)
                                            : new btVector3(0, 0, 0))
                            + solverConstraint.m_relpos1CrossNormal.dot(
                                    body0 != null
                                            ? solverBodyA.m_angularVelocity
                                            : new btVector3(0, 0, 0));
            double vel2Dotn =
                    solverConstraint.m_contactNormal2.dot(
                                    body1 != null
                                            ? solverBodyB.m_linearVelocity.add(
                                                    solverBodyB.m_externalForceImpulse)
                                            : new btVector3(0, 0, 0))
                            + solverConstraint.m_relpos2CrossNormal.dot(
                                    body1 != null
                                            ? solverBodyB.m_angularVelocity
                                            : new btVector3(0, 0, 0));

            rel_vel = vel1Dotn + vel2Dotn;

            //		btScalar positionalError = 0.f;

            double velocityError = desiredVelocity - rel_vel;
            double velocityImpulse = velocityError * solverConstraint.m_jacDiagABInv;
            solverConstraint.m_rhs = velocityImpulse;
            solverConstraint.m_cfm = cfmSlip;
            solverConstraint.m_lowerLimit = -solverConstraint.m_friction;
            solverConstraint.m_upperLimit = solverConstraint.m_friction;
        }
    }

    protected btSolverConstraint addRollingFrictionConstraint(
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            int frictionIndex,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation) {
        return addRollingFrictionConstraint(
                normalAxis,
                solverBodyIdA,
                solverBodyIdB,
                frictionIndex,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                0,
                0.f);
    }

    protected btSolverConstraint addRollingFrictionConstraint(
            btVector3 normalAxis,
            int solverBodyIdA,
            int solverBodyIdB,
            int frictionIndex,
            btManifoldPoint cp,
            btVector3 rel_pos1,
            btVector3 rel_pos2,
            btCollisionObject colObj0,
            btCollisionObject colObj1,
            double relaxation,
            double desiredVelocity,
            double cfmSlip) {
        btSolverConstraint solverConstraint =
                m_tmpSolverContactRollingFrictionConstraintPool.expandNonInitializing();
        solverConstraint.m_frictionIndex = frictionIndex;
        setupRollingFrictionConstraint(
                solverConstraint,
                normalAxis,
                solverBodyIdA,
                solverBodyIdB,
                cp,
                rel_pos1,
                rel_pos2,
                colObj0,
                colObj1,
                relaxation,
                desiredVelocity,
                cfmSlip);
        return solverConstraint;
    }

    protected int getOrInitSolverBody(btCollisionObject body, double timeStep) {
        int solverBodyIdA = -1;

        if (body.getCompanionId() >= 0) {
            // body has already been converted
            solverBodyIdA = body.getCompanionId();
        } else {
            btRigidBody rb = btRigidBody.upcast(body);
            // convert both active and kinematic objects (for their velocity)
            if (rb != null && (rb.getInvMass() != 0 || rb.isKinematicObject())) {
                solverBodyIdA = m_tmpSolverBodyPool.size();
                btSolverBody solverBody = m_tmpSolverBodyPool.expand();
                initSolverBody(solverBody, body, timeStep);
                body.setCompanionId(solverBodyIdA);
            } else {

                if (m_fixedBodyId < 0) {
                    m_fixedBodyId = m_tmpSolverBodyPool.size();
                    btSolverBody fixedBody = m_tmpSolverBodyPool.expand();
                    initSolverBody(fixedBody, null, timeStep);
                }
                return m_fixedBodyId;
                //			return 0;//assume first one is a fixed solver body
            }
        }

        return solverBodyIdA;
    }

    /** {@code btScalar& relaxation} is {@code relaxation[0]}. */
    protected void setupContactConstraint(
            btSolverConstraint solverConstraint,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btContactSolverInfo infoGlobal,
            double[] relaxation,
            btVector3 rel_pos1,
            btVector3 rel_pos2) {
        final btVector3 pos1 = cp.getPositionWorldOnA();
        final btVector3 pos2 = cp.getPositionWorldOnB();

        btSolverBody bodyA = m_tmpSolverBodyPool.get(solverBodyIdA);
        btSolverBody bodyB = m_tmpSolverBodyPool.get(solverBodyIdB);

        btRigidBody rb0 = bodyA.m_originalBody;
        btRigidBody rb1 = bodyB.m_originalBody;

        relaxation[0] = 1.f;

        btVector3 torqueAxis0 = rel_pos1.cross(cp.m_normalWorldOnB);
        solverConstraint.m_angularComponentA.set(
                rb0 != null
                        ? rb0.getInvInertiaTensorWorld()
                                .mul(torqueAxis0)
                                .mul(rb0.getAngularFactor())
                        : new btVector3(0, 0, 0));
        btVector3 torqueAxis1 = rel_pos2.cross(cp.m_normalWorldOnB);
        solverConstraint.m_angularComponentB.set(
                rb1 != null
                        ? rb1.getInvInertiaTensorWorld()
                                .mul(torqueAxis1.negate())
                                .mul(rb1.getAngularFactor())
                        : new btVector3(0, 0, 0));

        {
            // COMPUTE_IMPULSE_DENOM not defined
            btVector3 vec;
            double denom0 = 0.f;
            double denom1 = 0.f;
            if (rb0 != null) {
                vec = (solverConstraint.m_angularComponentA).cross(rel_pos1);
                denom0 = rb0.getInvMass() + cp.m_normalWorldOnB.dot(vec);
            }
            if (rb1 != null) {
                vec = (solverConstraint.m_angularComponentB.negate()).cross(rel_pos2);
                denom1 = rb1.getInvMass() + cp.m_normalWorldOnB.dot(vec);
            }

            double denom = relaxation[0] / (denom0 + denom1);
            solverConstraint.m_jacDiagABInv = denom;
        }

        if (rb0 != null) {
            solverConstraint.m_contactNormal1.set(cp.m_normalWorldOnB);
            solverConstraint.m_relpos1CrossNormal.set(torqueAxis0);
        } else {
            solverConstraint.m_contactNormal1.setZero();
            solverConstraint.m_relpos1CrossNormal.setZero();
        }
        if (rb1 != null) {
            solverConstraint.m_contactNormal2.set(cp.m_normalWorldOnB.negate());
            solverConstraint.m_relpos2CrossNormal.set(torqueAxis1.negate());
        } else {
            solverConstraint.m_contactNormal2.setZero();
            solverConstraint.m_relpos2CrossNormal.setZero();
        }

        double restitution = 0.f;
        double penetration = cp.getDistance() + infoGlobal.m_linearSlop;

        {
            btVector3 vel1, vel2;

            vel1 = rb0 != null ? rb0.getVelocityInLocalPoint(rel_pos1) : new btVector3(0, 0, 0);
            vel2 = rb1 != null ? rb1.getVelocityInLocalPoint(rel_pos2) : new btVector3(0, 0, 0);

            btVector3 vel = vel1.sub(vel2);
            double rel_vel = cp.m_normalWorldOnB.dot(vel);

            solverConstraint.m_friction = cp.m_combinedFriction;

            restitution = restitutionCurve(rel_vel, cp.m_combinedRestitution);
            if (restitution <= 0.) {
                restitution = 0.f;
            }
        }

        /// warm starting (or zero if disabled)
        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_WARMSTARTING) != 0) {
            solverConstraint.m_appliedImpulse =
                    cp.m_appliedImpulse * infoGlobal.m_warmstartingFactor;
            if (rb0 != null)
                bodyA.internalApplyImpulse(
                        solverConstraint
                                .m_contactNormal1
                                .mul(bodyA.internalGetInvMass())
                                .mul(rb0.getLinearFactor()),
                        solverConstraint.m_angularComponentA,
                        solverConstraint.m_appliedImpulse);
            if (rb1 != null)
                bodyB.internalApplyImpulse(
                        solverConstraint
                                .m_contactNormal2
                                .negate()
                                .mul(bodyB.internalGetInvMass())
                                .mul(rb1.getLinearFactor()),
                        solverConstraint.m_angularComponentB.negate(),
                        -solverConstraint.m_appliedImpulse);
        } else {
            solverConstraint.m_appliedImpulse = 0.f;
        }

        solverConstraint.m_appliedPushImpulse = 0.f;

        {
            btVector3 externalForceImpulseA =
                    bodyA.m_originalBody != null
                            ? bodyA.m_externalForceImpulse
                            : new btVector3(0, 0, 0);
            btVector3 externalTorqueImpulseA =
                    bodyA.m_originalBody != null
                            ? bodyA.m_externalTorqueImpulse
                            : new btVector3(0, 0, 0);
            btVector3 externalForceImpulseB =
                    bodyB.m_originalBody != null
                            ? bodyB.m_externalForceImpulse
                            : new btVector3(0, 0, 0);
            btVector3 externalTorqueImpulseB =
                    bodyB.m_originalBody != null
                            ? bodyB.m_externalTorqueImpulse
                            : new btVector3(0, 0, 0);

            double vel1Dotn =
                    solverConstraint.m_contactNormal1.dot(
                                    bodyA.m_linearVelocity.add(externalForceImpulseA))
                            + solverConstraint.m_relpos1CrossNormal.dot(
                                    bodyA.m_angularVelocity.add(externalTorqueImpulseA));
            double vel2Dotn =
                    solverConstraint.m_contactNormal2.dot(
                                    bodyB.m_linearVelocity.add(externalForceImpulseB))
                            + solverConstraint.m_relpos2CrossNormal.dot(
                                    bodyB.m_angularVelocity.add(externalTorqueImpulseB));
            double rel_vel = vel1Dotn + vel2Dotn;

            double positionalError = 0.f;
            double velocityError = restitution - rel_vel; // * damping;

            double erp = infoGlobal.m_erp2;
            if (infoGlobal.m_splitImpulse == 0
                    || (penetration > infoGlobal.m_splitImpulsePenetrationThreshold)) {
                erp = infoGlobal.m_erp;
            }

            if (penetration > 0) {
                positionalError = 0;

                velocityError -= penetration / infoGlobal.m_timeStep;
            } else {
                positionalError = -penetration * erp / infoGlobal.m_timeStep;
            }

            double penetrationImpulse = positionalError * solverConstraint.m_jacDiagABInv;
            double velocityImpulse = velocityError * solverConstraint.m_jacDiagABInv;

            if (infoGlobal.m_splitImpulse == 0
                    || (penetration > infoGlobal.m_splitImpulsePenetrationThreshold)) {
                // combine position and velocity into rhs
                solverConstraint.m_rhs = penetrationImpulse + velocityImpulse;
                solverConstraint.m_rhsPenetration = 0.f;

            } else {
                // split position and velocity into rhs and m_rhsPenetration
                solverConstraint.m_rhs = velocityImpulse;
                solverConstraint.m_rhsPenetration = penetrationImpulse;
            }
            solverConstraint.m_cfm = 0.f;
            solverConstraint.m_lowerLimit = 0;
            solverConstraint.m_upperLimit = (double) 1e10f;
        }
    }

    protected void setFrictionConstraintImpulse(
            btSolverConstraint solverConstraint,
            int solverBodyIdA,
            int solverBodyIdB,
            btManifoldPoint cp,
            btContactSolverInfo infoGlobal) {
        btSolverBody bodyA = m_tmpSolverBodyPool.get(solverBodyIdA);
        btSolverBody bodyB = m_tmpSolverBodyPool.get(solverBodyIdB);

        btRigidBody rb0 = bodyA.m_originalBody;
        btRigidBody rb1 = bodyB.m_originalBody;

        {
            btSolverConstraint frictionConstraint1 =
                    m_tmpSolverContactFrictionConstraintPool.get(solverConstraint.m_frictionIndex);
            if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_WARMSTARTING) != 0) {
                frictionConstraint1.m_appliedImpulse =
                        cp.m_appliedImpulseLateral1 * infoGlobal.m_warmstartingFactor;
                if (rb0 != null)
                    bodyA.internalApplyImpulse(
                            frictionConstraint1
                                    .m_contactNormal1
                                    .mul(rb0.getInvMass())
                                    .mul(rb0.getLinearFactor()),
                            frictionConstraint1.m_angularComponentA,
                            frictionConstraint1.m_appliedImpulse);
                if (rb1 != null)
                    bodyB.internalApplyImpulse(
                            frictionConstraint1
                                    .m_contactNormal2
                                    .negate()
                                    .mul(rb1.getInvMass())
                                    .mul(rb1.getLinearFactor()),
                            frictionConstraint1.m_angularComponentB.negate(),
                            -frictionConstraint1.m_appliedImpulse);
            } else {
                frictionConstraint1.m_appliedImpulse = 0.f;
            }
        }

        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                != 0) {
            btSolverConstraint frictionConstraint2 =
                    m_tmpSolverContactFrictionConstraintPool.get(
                            solverConstraint.m_frictionIndex + 1);
            if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_WARMSTARTING) != 0) {
                frictionConstraint2.m_appliedImpulse =
                        cp.m_appliedImpulseLateral2 * infoGlobal.m_warmstartingFactor;
                if (rb0 != null)
                    bodyA.internalApplyImpulse(
                            frictionConstraint2.m_contactNormal1.mul(rb0.getInvMass()),
                            frictionConstraint2.m_angularComponentA,
                            frictionConstraint2.m_appliedImpulse);
                if (rb1 != null)
                    bodyB.internalApplyImpulse(
                            frictionConstraint2.m_contactNormal2.negate().mul(rb1.getInvMass()),
                            frictionConstraint2.m_angularComponentB.negate(),
                            -frictionConstraint2.m_appliedImpulse);
            } else {
                frictionConstraint2.m_appliedImpulse = 0.f;
            }
        }
    }

    protected void convertContact(btPersistentManifold manifold, btContactSolverInfo infoGlobal) {
        btCollisionObject colObj0 = null, colObj1 = null;

        colObj0 = manifold.getBody0();
        colObj1 = manifold.getBody1();

        int solverBodyIdA = getOrInitSolverBody(colObj0, infoGlobal.m_timeStep);
        int solverBodyIdB = getOrInitSolverBody(colObj1, infoGlobal.m_timeStep);

        //	btRigidBody* bodyA = btRigidBody::upcast(colObj0);
        //	btRigidBody* bodyB = btRigidBody::upcast(colObj1);

        btSolverBody solverBodyA = m_tmpSolverBodyPool.get(solverBodyIdA);
        btSolverBody solverBodyB = m_tmpSolverBodyPool.get(solverBodyIdB);

        /// avoid collision response between two static objects
        if (solverBodyA == null
                || (solverBodyA.m_invMass.isZero()
                        && (solverBodyB == null || solverBodyB.m_invMass.isZero()))) return;

        int rollingFriction = 1;
        double[] relaxation = new double[1];
        for (int j = 0; j < manifold.getNumContacts(); j++) {

            btManifoldPoint cp = manifold.getContactPoint(j);

            if (cp.getDistance() <= manifold.getContactProcessingThreshold()) {
                btVector3 rel_pos1;
                btVector3 rel_pos2;

                int frictionIndex = m_tmpSolverContactConstraintPool.size();
                btSolverConstraint solverConstraint =
                        m_tmpSolverContactConstraintPool.expandNonInitializing();
                btRigidBody rb0 = btRigidBody.upcast(colObj0);
                btRigidBody rb1 = btRigidBody.upcast(colObj1);
                solverConstraint.m_solverBodyIdA = solverBodyIdA;
                solverConstraint.m_solverBodyIdB = solverBodyIdB;

                solverConstraint.m_originalContactPoint = cp;

                final btVector3 pos1 = cp.getPositionWorldOnA();
                final btVector3 pos2 = cp.getPositionWorldOnB();

                rel_pos1 = pos1.sub(colObj0.getWorldTransform().getOrigin());
                rel_pos2 = pos2.sub(colObj1.getWorldTransform().getOrigin());

                btVector3 vel1 = new btVector3();
                btVector3 vel2 = new btVector3();

                solverBodyA.getVelocityInLocalPointNoDelta(rel_pos1, vel1);
                solverBodyB.getVelocityInLocalPointNoDelta(rel_pos2, vel2);

                btVector3 vel = vel1.sub(vel2);
                double rel_vel = cp.m_normalWorldOnB.dot(vel);

                setupContactConstraint(
                        solverConstraint,
                        solverBodyIdA,
                        solverBodyIdB,
                        cp,
                        infoGlobal,
                        relaxation,
                        rel_pos1,
                        rel_pos2);

                /// setup the friction constraints

                solverConstraint.m_frictionIndex = m_tmpSolverContactFrictionConstraintPool.size();

                btVector3 angVelA = new btVector3(0, 0, 0), angVelB = new btVector3(0, 0, 0);
                if (rb0 != null) angVelA.set(rb0.getAngularVelocity());
                if (rb1 != null) angVelB.set(rb1.getAngularVelocity());
                btVector3 relAngVel = angVelB.sub(angVelA);

                if ((cp.m_combinedRollingFriction > 0.f) && (rollingFriction > 0)) {
                    // only a single rollingFriction per manifold
                    rollingFriction--;
                    if (relAngVel.length() > infoGlobal.m_singleAxisRollingFrictionThreshold) {
                        relAngVel.normalize();
                        applyAnisotropicFriction(
                                colObj0,
                                relAngVel,
                                btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        applyAnisotropicFriction(
                                colObj1,
                                relAngVel,
                                btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        if (relAngVel.length() > 0.001)
                            addRollingFrictionConstraint(
                                    relAngVel,
                                    solverBodyIdA,
                                    solverBodyIdB,
                                    frictionIndex,
                                    cp,
                                    rel_pos1,
                                    rel_pos2,
                                    colObj0,
                                    colObj1,
                                    relaxation[0]);

                    } else {
                        addRollingFrictionConstraint(
                                cp.m_normalWorldOnB,
                                solverBodyIdA,
                                solverBodyIdB,
                                frictionIndex,
                                cp,
                                rel_pos1,
                                rel_pos2,
                                colObj0,
                                colObj1,
                                relaxation[0]);
                        btVector3 axis0 = new btVector3(), axis1 = new btVector3();
                        btVector3.btPlaneSpace1(cp.m_normalWorldOnB, axis0, axis1);
                        applyAnisotropicFriction(
                                colObj0, axis0, btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        applyAnisotropicFriction(
                                colObj1, axis0, btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        applyAnisotropicFriction(
                                colObj0, axis1, btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        applyAnisotropicFriction(
                                colObj1, axis1, btCollisionObject.CF_ANISOTROPIC_ROLLING_FRICTION);
                        if (axis0.length() > 0.001)
                            addRollingFrictionConstraint(
                                    axis0,
                                    solverBodyIdA,
                                    solverBodyIdB,
                                    frictionIndex,
                                    cp,
                                    rel_pos1,
                                    rel_pos2,
                                    colObj0,
                                    colObj1,
                                    relaxation[0]);
                        if (axis1.length() > 0.001)
                            addRollingFrictionConstraint(
                                    axis1,
                                    solverBodyIdA,
                                    solverBodyIdB,
                                    frictionIndex,
                                    cp,
                                    rel_pos1,
                                    rel_pos2,
                                    colObj0,
                                    colObj1,
                                    relaxation[0]);
                    }
                }

                /// Bullet has several options to set the friction directions
                /// By default, each contact has only a single friction direction that is
                /// recomputed automatically very frame based on the relative linear velocity.
                /// If the relative velocity it zero, it will automatically compute a friction
                /// direction.
                ///
                /// You can also enable two friction directions, using the
                /// SOLVER_USE_2_FRICTION_DIRECTIONS. In that case, the second friction direction
                /// will be orthogonal to both contact normal and first friction direction.
                ///
                /// If you choose SOLVER_DISABLE_VELOCITY_DEPENDENT_FRICTION_DIRECTION, then the
                /// friction will be independent from the relative projected velocity.
                ///
                /// The user can manually override the friction directions for certain contacts
                /// using a contact callback, and set the cp.m_lateralFrictionInitialized to true
                /// In that case, you can set the target relative motion in each friction
                /// direction (cp.m_contactMotion1 and cp.m_contactMotion2) this will give a
                /// conveyor belt effect
                ///
                if ((infoGlobal.m_solverMode
                                        & btContactSolverInfoData
                                                .SOLVER_ENABLE_FRICTION_DIRECTION_CACHING)
                                == 0
                        || !cp.m_lateralFrictionInitialized) {
                    cp.m_lateralFrictionDir1.set(vel.sub(cp.m_normalWorldOnB.mul(rel_vel)));
                    double lat_rel_vel = cp.m_lateralFrictionDir1.length2();
                    if ((infoGlobal.m_solverMode
                                            & btContactSolverInfoData
                                                    .SOLVER_DISABLE_VELOCITY_DEPENDENT_FRICTION_DIRECTION)
                                    == 0
                            && lat_rel_vel > btScalar.SIMD_EPSILON) {
                        cp.m_lateralFrictionDir1.mulLocal((double) 1.f / Math.sqrt(lat_rel_vel));
                        applyAnisotropicFriction(
                                colObj0,
                                cp.m_lateralFrictionDir1,
                                btCollisionObject.CF_ANISOTROPIC_FRICTION);
                        applyAnisotropicFriction(
                                colObj1,
                                cp.m_lateralFrictionDir1,
                                btCollisionObject.CF_ANISOTROPIC_FRICTION);
                        addFrictionConstraint(
                                cp.m_lateralFrictionDir1,
                                solverBodyIdA,
                                solverBodyIdB,
                                frictionIndex,
                                cp,
                                rel_pos1,
                                rel_pos2,
                                colObj0,
                                colObj1,
                                relaxation[0]);

                        if ((infoGlobal.m_solverMode
                                        & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                                != 0) {
                            cp.m_lateralFrictionDir2.set(
                                    cp.m_lateralFrictionDir1.cross(cp.m_normalWorldOnB));
                            cp.m_lateralFrictionDir2.normalize(); // ??
                            applyAnisotropicFriction(
                                    colObj0,
                                    cp.m_lateralFrictionDir2,
                                    btCollisionObject.CF_ANISOTROPIC_FRICTION);
                            applyAnisotropicFriction(
                                    colObj1,
                                    cp.m_lateralFrictionDir2,
                                    btCollisionObject.CF_ANISOTROPIC_FRICTION);
                            addFrictionConstraint(
                                    cp.m_lateralFrictionDir2,
                                    solverBodyIdA,
                                    solverBodyIdB,
                                    frictionIndex,
                                    cp,
                                    rel_pos1,
                                    rel_pos2,
                                    colObj0,
                                    colObj1,
                                    relaxation[0]);
                        }

                    } else {
                        btVector3.btPlaneSpace1(
                                cp.m_normalWorldOnB,
                                cp.m_lateralFrictionDir1,
                                cp.m_lateralFrictionDir2);

                        applyAnisotropicFriction(
                                colObj0,
                                cp.m_lateralFrictionDir1,
                                btCollisionObject.CF_ANISOTROPIC_FRICTION);
                        applyAnisotropicFriction(
                                colObj1,
                                cp.m_lateralFrictionDir1,
                                btCollisionObject.CF_ANISOTROPIC_FRICTION);
                        addFrictionConstraint(
                                cp.m_lateralFrictionDir1,
                                solverBodyIdA,
                                solverBodyIdB,
                                frictionIndex,
                                cp,
                                rel_pos1,
                                rel_pos2,
                                colObj0,
                                colObj1,
                                relaxation[0]);

                        if ((infoGlobal.m_solverMode
                                        & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                                != 0) {
                            applyAnisotropicFriction(
                                    colObj0,
                                    cp.m_lateralFrictionDir2,
                                    btCollisionObject.CF_ANISOTROPIC_FRICTION);
                            applyAnisotropicFriction(
                                    colObj1,
                                    cp.m_lateralFrictionDir2,
                                    btCollisionObject.CF_ANISOTROPIC_FRICTION);
                            addFrictionConstraint(
                                    cp.m_lateralFrictionDir2,
                                    solverBodyIdA,
                                    solverBodyIdB,
                                    frictionIndex,
                                    cp,
                                    rel_pos1,
                                    rel_pos2,
                                    colObj0,
                                    colObj1,
                                    relaxation[0]);
                        }

                        if ((infoGlobal.m_solverMode
                                                & btContactSolverInfoData
                                                        .SOLVER_USE_2_FRICTION_DIRECTIONS)
                                        != 0
                                && (infoGlobal.m_solverMode
                                                & btContactSolverInfoData
                                                        .SOLVER_DISABLE_VELOCITY_DEPENDENT_FRICTION_DIRECTION)
                                        != 0) {
                            cp.m_lateralFrictionInitialized = true;
                        }
                    }

                } else {
                    addFrictionConstraint(
                            cp.m_lateralFrictionDir1,
                            solverBodyIdA,
                            solverBodyIdB,
                            frictionIndex,
                            cp,
                            rel_pos1,
                            rel_pos2,
                            colObj0,
                            colObj1,
                            relaxation[0],
                            cp.m_contactMotion1,
                            cp.m_contactCFM1);

                    if ((infoGlobal.m_solverMode
                                    & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                            != 0)
                        addFrictionConstraint(
                                cp.m_lateralFrictionDir2,
                                solverBodyIdA,
                                solverBodyIdB,
                                frictionIndex,
                                cp,
                                rel_pos1,
                                rel_pos2,
                                colObj0,
                                colObj1,
                                relaxation[0],
                                cp.m_contactMotion2,
                                cp.m_contactCFM2);
                }
                setFrictionConstraintImpulse(
                        solverConstraint, solverBodyIdA, solverBodyIdB, cp, infoGlobal);
            }
        }
    }

    protected void convertContacts(
            btPersistentManifold[] manifoldPtr, int numManifolds, btContactSolverInfo infoGlobal) {
        int i;
        btPersistentManifold manifold = null;
        //			btCollisionObject* colObj0=0,*colObj1=0;

        for (i = 0; i < numManifolds; i++) {
            manifold = manifoldPtr[i];
            convertContact(manifold, infoGlobal);
        }
    }

    protected double solveGroupCacheFriendlySetup(
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifoldPtr,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo infoGlobal,
            btIDebugDraw debugDrawer) {
        m_fixedBodyId = -1;
        m_maxOverrideNumSolverIterations = 0;

        // BT_ADDITIONAL_DEBUG not defined

        for (int i = 0; i < numBodies; i++) {
            bodies[i].setCompanionId(-1);
        }

        m_tmpSolverBodyPool.reserve(numBodies + 1);
        m_tmpSolverBodyPool.resize(0);

        // btSolverBody& fixedBody = m_tmpSolverBodyPool.expand();
        // initSolverBody(&fixedBody,0);

        // convert all bodies

        for (int i = 0; i < numBodies; i++) {
            int bodyId = getOrInitSolverBody(bodies[i], infoGlobal.m_timeStep);

            btRigidBody body = btRigidBody.upcast(bodies[i]);
            if (body != null && body.getInvMass() != 0) {
                btSolverBody solverBody = m_tmpSolverBodyPool.get(bodyId);
                btVector3 gyroForce = new btVector3(0, 0, 0);
                if ((body.getFlags() & btRigidBody.BT_ENABLE_GYROPSCOPIC_FORCE) != 0) {
                    gyroForce = body.computeGyroscopicForce(infoGlobal.m_maxGyroscopicForce);
                    solverBody.m_externalTorqueImpulse.subLocal(
                            btMatrix3x3
                                    .mul(gyroForce, body.getInvInertiaTensorWorld())
                                    .mul(infoGlobal.m_timeStep));
                }
            }
        }

        if (true) {
            int j;
            for (j = 0; j < numConstraints; j++) {
                btTypedConstraint constraint = constraints[j];
                constraint.buildJacobian();
                constraint.internalSetAppliedImpulse(0.0f);
            }
        }

        {
            {
                int totalNumRows = 0;
                int i;

                m_tmpConstraintSizesPool.resizeNoInitialize(numConstraints);
                // calculate the total number of contraint rows
                for (i = 0; i < numConstraints; i++) {
                    btTypedConstraint.btConstraintInfo1 info1 = m_tmpConstraintSizesPool.get(i);
                    btJointFeedback fb = constraints[i].getJointFeedback();
                    if (fb != null) {
                        fb.m_appliedForceBodyA.setZero();
                        fb.m_appliedTorqueBodyA.setZero();
                        fb.m_appliedForceBodyB.setZero();
                        fb.m_appliedTorqueBodyB.setZero();
                    }

                    if (constraints[i].isEnabled()) {}
                    if (constraints[i].isEnabled()) {
                        constraints[i].getInfo1(info1);
                    } else {
                        info1.m_numConstraintRows = 0;
                        info1.nub = 0;
                    }
                    totalNumRows += info1.m_numConstraintRows;
                }
                m_tmpSolverNonContactConstraintPool.resizeNoInitialize(totalNumRows);

                /// setup the btSolverConstraints
                int currentRow = 0;

                for (i = 0; i < numConstraints; i++) {
                    final btTypedConstraint.btConstraintInfo1 info1 =
                            m_tmpConstraintSizesPool.get(i);

                    if (info1.m_numConstraintRows != 0) {
                        // btSolverConstraint* currentConstraintRow =
                        //     &m_tmpSolverNonContactConstraintPool[currentRow];
                        final Object[] rows = m_tmpSolverNonContactConstraintPool.m_data;
                        btTypedConstraint constraint = constraints[i];
                        btRigidBody rbA = constraint.getRigidBodyA();
                        btRigidBody rbB = constraint.getRigidBodyB();

                        int solverBodyIdA = getOrInitSolverBody(rbA, infoGlobal.m_timeStep);
                        int solverBodyIdB = getOrInitSolverBody(rbB, infoGlobal.m_timeStep);

                        btSolverBody bodyAPtr = m_tmpSolverBodyPool.get(solverBodyIdA);
                        btSolverBody bodyBPtr = m_tmpSolverBodyPool.get(solverBodyIdB);

                        int overrideNumSolverIterations =
                                constraint.getOverrideNumSolverIterations() > 0
                                        ? constraint.getOverrideNumSolverIterations()
                                        : infoGlobal.m_numIterations;
                        if (overrideNumSolverIterations > m_maxOverrideNumSolverIterations)
                            m_maxOverrideNumSolverIterations = overrideNumSolverIterations;

                        int j;
                        for (j = 0; j < info1.m_numConstraintRows; j++) {
                            btSolverConstraint row = (btSolverConstraint) rows[currentRow + j];
                            row.zero();
                            row.m_lowerLimit = -btScalar.SIMD_INFINITY;
                            row.m_upperLimit = btScalar.SIMD_INFINITY;
                            row.m_appliedImpulse = 0.f;
                            row.m_appliedPushImpulse = 0.f;
                            row.m_solverBodyIdA = solverBodyIdA;
                            row.m_solverBodyIdB = solverBodyIdB;
                            row.m_overrideNumSolverIterations = overrideNumSolverIterations;
                        }

                        bodyAPtr.internalGetDeltaLinearVelocity().setValue(0.f, 0.f, 0.f);
                        bodyAPtr.internalGetDeltaAngularVelocity().setValue(0.f, 0.f, 0.f);
                        bodyAPtr.internalGetPushVelocity().setValue(0.f, 0.f, 0.f);
                        bodyAPtr.internalGetTurnVelocity().setValue(0.f, 0.f, 0.f);
                        bodyBPtr.internalGetDeltaLinearVelocity().setValue(0.f, 0.f, 0.f);
                        bodyBPtr.internalGetDeltaAngularVelocity().setValue(0.f, 0.f, 0.f);
                        bodyBPtr.internalGetPushVelocity().setValue(0.f, 0.f, 0.f);
                        bodyBPtr.internalGetTurnVelocity().setValue(0.f, 0.f, 0.f);

                        btSolverConstraint currentConstraintRow =
                                (btSolverConstraint) rows[currentRow];

                        btTypedConstraint.btConstraintInfo2 info2 =
                                new btTypedConstraint.btConstraintInfo2();
                        info2.fps = (double) 1.f / infoGlobal.m_timeStep;
                        info2.erp = infoGlobal.m_erp;
                        info2.m_J1linearAxis = new btScalarPtr(rows, currentRow, 4);
                        info2.m_J1angularAxis = new btScalarPtr(rows, currentRow, 0);
                        info2.m_J2linearAxis = new btScalarPtr(rows, currentRow, 12);
                        info2.m_J2angularAxis = new btScalarPtr(rows, currentRow, 8);
                        info2.rowskip = btSolverConstraint.ROWSKIP; // check this
                        /// the size of btSolverConstraint needs be a multiple of btScalar
                        info2.m_constraintError = new btScalarPtr(rows, currentRow, 28);
                        currentConstraintRow.m_cfm = infoGlobal.m_globalCfm;
                        info2.m_damping = infoGlobal.m_damping;
                        info2.cfm = new btScalarPtr(rows, currentRow, 29);
                        info2.m_lowerLimit = new btScalarPtr(rows, currentRow, 30);
                        info2.m_upperLimit = new btScalarPtr(rows, currentRow, 31);
                        info2.m_numIterations = infoGlobal.m_numIterations;
                        constraints[i].getInfo2(info2);

                        /// finalize the constraint setup
                        for (j = 0; j < info1.m_numConstraintRows; j++) {
                            btSolverConstraint solverConstraint =
                                    (btSolverConstraint) rows[currentRow + j];

                            if (solverConstraint.m_upperLimit
                                    >= constraints[i].getBreakingImpulseThreshold()) {
                                solverConstraint.m_upperLimit =
                                        constraints[i].getBreakingImpulseThreshold();
                            }

                            if (solverConstraint.m_lowerLimit
                                    <= -constraints[i].getBreakingImpulseThreshold()) {
                                solverConstraint.m_lowerLimit =
                                        -constraints[i].getBreakingImpulseThreshold();
                            }

                            solverConstraint.m_originalContactPoint = constraint;

                            {
                                final btVector3 ftorqueAxis1 =
                                        solverConstraint.m_relpos1CrossNormal;
                                solverConstraint.m_angularComponentA.set(
                                        constraint
                                                .getRigidBodyA()
                                                .getInvInertiaTensorWorld()
                                                .mul(ftorqueAxis1)
                                                .mul(
                                                        constraint
                                                                .getRigidBodyA()
                                                                .getAngularFactor()));
                            }
                            {
                                final btVector3 ftorqueAxis2 =
                                        solverConstraint.m_relpos2CrossNormal;
                                solverConstraint.m_angularComponentB.set(
                                        constraint
                                                .getRigidBodyB()
                                                .getInvInertiaTensorWorld()
                                                .mul(ftorqueAxis2)
                                                .mul(
                                                        constraint
                                                                .getRigidBodyB()
                                                                .getAngularFactor()));
                            }

                            {
                                btVector3 iMJlA =
                                        solverConstraint.m_contactNormal1.mul(rbA.getInvMass());
                                btVector3 iMJaA =
                                        rbA.getInvInertiaTensorWorld()
                                                .mul(solverConstraint.m_relpos1CrossNormal);
                                btVector3 iMJlB =
                                        solverConstraint.m_contactNormal2.mul(
                                                rbB.getInvMass()); // sign of normal?
                                btVector3 iMJaB =
                                        rbB.getInvInertiaTensorWorld()
                                                .mul(solverConstraint.m_relpos2CrossNormal);

                                double sum = iMJlA.dot(solverConstraint.m_contactNormal1);
                                sum += iMJaA.dot(solverConstraint.m_relpos1CrossNormal);
                                sum += iMJlB.dot(solverConstraint.m_contactNormal2);
                                sum += iMJaB.dot(solverConstraint.m_relpos2CrossNormal);
                                double fsum = Math.abs(sum);
                                solverConstraint.m_jacDiagABInv =
                                        fsum > btScalar.SIMD_EPSILON ? 1. / sum : 0.f;
                            }

                            {
                                double rel_vel;
                                btVector3 externalForceImpulseA =
                                        bodyAPtr.m_originalBody != null
                                                ? bodyAPtr.m_externalForceImpulse
                                                : new btVector3(0, 0, 0);
                                btVector3 externalTorqueImpulseA =
                                        bodyAPtr.m_originalBody != null
                                                ? bodyAPtr.m_externalTorqueImpulse
                                                : new btVector3(0, 0, 0);

                                btVector3 externalForceImpulseB =
                                        bodyBPtr.m_originalBody != null
                                                ? bodyBPtr.m_externalForceImpulse
                                                : new btVector3(0, 0, 0);
                                btVector3 externalTorqueImpulseB =
                                        bodyBPtr.m_originalBody != null
                                                ? bodyBPtr.m_externalTorqueImpulse
                                                : new btVector3(0, 0, 0);

                                double vel1Dotn =
                                        solverConstraint.m_contactNormal1.dot(
                                                        rbA.getLinearVelocity()
                                                                .add(externalForceImpulseA))
                                                + solverConstraint.m_relpos1CrossNormal.dot(
                                                        rbA.getAngularVelocity()
                                                                .add(externalTorqueImpulseA));

                                double vel2Dotn =
                                        solverConstraint.m_contactNormal2.dot(
                                                        rbB.getLinearVelocity()
                                                                .add(externalForceImpulseB))
                                                + solverConstraint.m_relpos2CrossNormal.dot(
                                                        rbB.getAngularVelocity()
                                                                .add(externalTorqueImpulseB));

                                rel_vel = vel1Dotn + vel2Dotn;
                                double restitution = 0.f;
                                double positionalError =
                                        solverConstraint.m_rhs; // already filled in by
                                // getConstraintInfo2
                                double velocityError = restitution - rel_vel * info2.m_damping;
                                double penetrationImpulse =
                                        positionalError * solverConstraint.m_jacDiagABInv;
                                double velocityImpulse =
                                        velocityError * solverConstraint.m_jacDiagABInv;
                                solverConstraint.m_rhs = penetrationImpulse + velocityImpulse;
                                solverConstraint.m_appliedImpulse = 0.f;
                            }
                        }
                    }
                    currentRow += m_tmpConstraintSizesPool.get(i).m_numConstraintRows;
                }
            }

            convertContacts(manifoldPtr, numManifolds, infoGlobal);
        }

        //	btContactSolverInfo info = infoGlobal;

        int numNonContactPool = m_tmpSolverNonContactConstraintPool.size();
        int numConstraintPool = m_tmpSolverContactConstraintPool.size();
        int numFrictionPool = m_tmpSolverContactFrictionConstraintPool.size();

        /// @todo: use stack allocator for such temporarily memory, same for solver
        /// bodies/constraints
        m_orderNonContactConstraintPool.resizeNoInitialize(numNonContactPool);
        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                != 0) m_orderTmpConstraintPool.resizeNoInitialize(numConstraintPool * 2);
        else m_orderTmpConstraintPool.resizeNoInitialize(numConstraintPool);

        m_orderFrictionConstraintPool.resizeNoInitialize(numFrictionPool);
        {
            int i;
            for (i = 0; i < numNonContactPool; i++) {
                m_orderNonContactConstraintPool.set(i, i);
            }
            for (i = 0; i < numConstraintPool; i++) {
                m_orderTmpConstraintPool.set(i, i);
            }
            for (i = 0; i < numFrictionPool; i++) {
                m_orderFrictionConstraintPool.set(i, i);
            }
        }

        return 0.f;
    }

    protected double solveSingleIteration(
            int iteration,
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifoldPtr,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo infoGlobal,
            btIDebugDraw debugDrawer) {

        int numNonContactPool = m_tmpSolverNonContactConstraintPool.size();
        int numConstraintPool = m_tmpSolverContactConstraintPool.size();
        int numFrictionPool = m_tmpSolverContactFrictionConstraintPool.size();

        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_RANDMIZE_ORDER) != 0) {
            if (true) // uncomment this for a bit less random ((iteration & 7) == 0)
            {

                for (int j = 0; j < numNonContactPool; ++j) {
                    int tmp = m_orderNonContactConstraintPool.get(j);
                    int swapi = btRandInt2(j + 1);
                    m_orderNonContactConstraintPool.set(
                            j, m_orderNonContactConstraintPool.get(swapi));
                    m_orderNonContactConstraintPool.set(swapi, tmp);
                }

                // contact/friction constraints are not solved more than
                if (iteration < infoGlobal.m_numIterations) {
                    for (int j = 0; j < numConstraintPool; ++j) {
                        int tmp = m_orderTmpConstraintPool.get(j);
                        int swapi = btRandInt2(j + 1);
                        m_orderTmpConstraintPool.set(j, m_orderTmpConstraintPool.get(swapi));
                        m_orderTmpConstraintPool.set(swapi, tmp);
                    }

                    for (int j = 0; j < numFrictionPool; ++j) {
                        int tmp = m_orderFrictionConstraintPool.get(j);
                        int swapi = btRandInt2(j + 1);
                        m_orderFrictionConstraintPool.set(
                                j, m_orderFrictionConstraintPool.get(swapi));
                        m_orderFrictionConstraintPool.set(swapi, tmp);
                    }
                }
            }
        }

        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_SIMD) != 0) {
            /// solve all joint constraints, using SIMD, if available
            for (int j = 0; j < m_tmpSolverNonContactConstraintPool.size(); j++) {
                btSolverConstraint constraint =
                        m_tmpSolverNonContactConstraintPool.get(
                                m_orderNonContactConstraintPool.get(j));
                if (iteration < constraint.m_overrideNumSolverIterations)
                    resolveSingleConstraintRowGenericSIMD(
                            m_tmpSolverBodyPool.get(constraint.m_solverBodyIdA),
                            m_tmpSolverBodyPool.get(constraint.m_solverBodyIdB),
                            constraint);
            }

            if (iteration < infoGlobal.m_numIterations) {
                for (int j = 0; j < numConstraints; j++) {
                    if (constraints[j].isEnabled()) {
                        int bodyAid =
                                getOrInitSolverBody(
                                        constraints[j].getRigidBodyA(), infoGlobal.m_timeStep);
                        int bodyBid =
                                getOrInitSolverBody(
                                        constraints[j].getRigidBodyB(), infoGlobal.m_timeStep);
                        btSolverBody bodyA = m_tmpSolverBodyPool.get(bodyAid);
                        btSolverBody bodyB = m_tmpSolverBodyPool.get(bodyBid);
                        constraints[j].solveConstraintObsolete(bodyA, bodyB, infoGlobal.m_timeStep);
                    }
                }

                /// solve all contact constraints using SIMD, if available
                if ((infoGlobal.m_solverMode
                                & btContactSolverInfoData
                                        .SOLVER_INTERLEAVE_CONTACT_AND_FRICTION_CONSTRAINTS)
                        != 0) {
                    int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
                    int multiplier =
                            (infoGlobal.m_solverMode
                                                    & btContactSolverInfoData
                                                            .SOLVER_USE_2_FRICTION_DIRECTIONS)
                                            != 0
                                    ? 2
                                    : 1;

                    for (int c = 0; c < numPoolConstraints; c++) {
                        double totalImpulse = 0;

                        {
                            final btSolverConstraint solveManifold =
                                    m_tmpSolverContactConstraintPool.get(
                                            m_orderTmpConstraintPool.get(c));
                            resolveSingleConstraintRowLowerLimitSIMD(
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                    solveManifold);
                            totalImpulse = solveManifold.m_appliedImpulse;
                        }
                        boolean applyFriction = true;
                        if (applyFriction) {
                            {
                                btSolverConstraint solveManifold =
                                        m_tmpSolverContactFrictionConstraintPool.get(
                                                m_orderFrictionConstraintPool.get(c * multiplier));

                                if (totalImpulse > 0) {
                                    solveManifold.m_lowerLimit =
                                            -(solveManifold.m_friction * totalImpulse);
                                    solveManifold.m_upperLimit =
                                            solveManifold.m_friction * totalImpulse;

                                    resolveSingleConstraintRowGenericSIMD(
                                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                            solveManifold);
                                }
                            }

                            if ((infoGlobal.m_solverMode
                                            & btContactSolverInfoData
                                                    .SOLVER_USE_2_FRICTION_DIRECTIONS)
                                    != 0) {

                                btSolverConstraint solveManifold =
                                        m_tmpSolverContactFrictionConstraintPool.get(
                                                m_orderFrictionConstraintPool.get(
                                                        c * multiplier + 1));

                                if (totalImpulse > 0) {
                                    solveManifold.m_lowerLimit =
                                            -(solveManifold.m_friction * totalImpulse);
                                    solveManifold.m_upperLimit =
                                            solveManifold.m_friction * totalImpulse;

                                    resolveSingleConstraintRowGenericSIMD(
                                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                            solveManifold);
                                }
                            }
                        }
                    }

                } else // SOLVER_INTERLEAVE_CONTACT_AND_FRICTION_CONSTRAINTS
                {
                    // solve the friction constraints after all contact constraints, don't
                    // interleave them
                    int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
                    int j;

                    for (j = 0; j < numPoolConstraints; j++) {
                        final btSolverConstraint solveManifold =
                                m_tmpSolverContactConstraintPool.get(
                                        m_orderTmpConstraintPool.get(j));
                        // resolveSingleConstraintRowLowerLimitSIMD(...);
                        resolveSingleConstraintRowLowerLimit(
                                m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                solveManifold);
                    }

                    /// solve all friction constraints, using SIMD, if available

                    int numFrictionPoolConstraints =
                            m_tmpSolverContactFrictionConstraintPool.size();
                    for (j = 0; j < numFrictionPoolConstraints; j++) {
                        btSolverConstraint solveManifold =
                                m_tmpSolverContactFrictionConstraintPool.get(
                                        m_orderFrictionConstraintPool.get(j));
                        double totalImpulse =
                                m_tmpSolverContactConstraintPool.get(solveManifold.m_frictionIndex)
                                        .m_appliedImpulse;

                        if (totalImpulse > 0) {
                            solveManifold.m_lowerLimit = -(solveManifold.m_friction * totalImpulse);
                            solveManifold.m_upperLimit = solveManifold.m_friction * totalImpulse;

                            // resolveSingleConstraintRowGenericSIMD(...);
                            resolveSingleConstraintRowGeneric(
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                    solveManifold);
                        }
                    }

                    int numRollingFrictionPoolConstraints =
                            m_tmpSolverContactRollingFrictionConstraintPool.size();
                    for (j = 0; j < numRollingFrictionPoolConstraints; j++) {

                        btSolverConstraint rollingFrictionConstraint =
                                m_tmpSolverContactRollingFrictionConstraintPool.get(j);
                        double totalImpulse =
                                m_tmpSolverContactConstraintPool.get(
                                                rollingFrictionConstraint.m_frictionIndex)
                                        .m_appliedImpulse;
                        if (totalImpulse > 0) {
                            double rollingFrictionMagnitude =
                                    rollingFrictionConstraint.m_friction * totalImpulse;
                            if (rollingFrictionMagnitude > rollingFrictionConstraint.m_friction)
                                rollingFrictionMagnitude = rollingFrictionConstraint.m_friction;

                            rollingFrictionConstraint.m_lowerLimit = -rollingFrictionMagnitude;
                            rollingFrictionConstraint.m_upperLimit = rollingFrictionMagnitude;

                            resolveSingleConstraintRowGenericSIMD(
                                    m_tmpSolverBodyPool.get(
                                            rollingFrictionConstraint.m_solverBodyIdA),
                                    m_tmpSolverBodyPool.get(
                                            rollingFrictionConstraint.m_solverBodyIdB),
                                    rollingFrictionConstraint);
                        }
                    }
                }
            }
        } else {
            // non-SIMD version
            /// solve all joint constraints
            for (int j = 0; j < m_tmpSolverNonContactConstraintPool.size(); j++) {
                btSolverConstraint constraint =
                        m_tmpSolverNonContactConstraintPool.get(
                                m_orderNonContactConstraintPool.get(j));
                if (iteration < constraint.m_overrideNumSolverIterations)
                    resolveSingleConstraintRowGeneric(
                            m_tmpSolverBodyPool.get(constraint.m_solverBodyIdA),
                            m_tmpSolverBodyPool.get(constraint.m_solverBodyIdB),
                            constraint);
            }

            if (iteration < infoGlobal.m_numIterations) {
                for (int j = 0; j < numConstraints; j++) {
                    if (constraints[j].isEnabled()) {
                        int bodyAid =
                                getOrInitSolverBody(
                                        constraints[j].getRigidBodyA(), infoGlobal.m_timeStep);
                        int bodyBid =
                                getOrInitSolverBody(
                                        constraints[j].getRigidBodyB(), infoGlobal.m_timeStep);
                        btSolverBody bodyA = m_tmpSolverBodyPool.get(bodyAid);
                        btSolverBody bodyB = m_tmpSolverBodyPool.get(bodyBid);
                        constraints[j].solveConstraintObsolete(bodyA, bodyB, infoGlobal.m_timeStep);
                    }
                }
                /// solve all contact constraints
                int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
                for (int j = 0; j < numPoolConstraints; j++) {
                    final btSolverConstraint solveManifold =
                            m_tmpSolverContactConstraintPool.get(m_orderTmpConstraintPool.get(j));
                    resolveSingleConstraintRowLowerLimit(
                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                            m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                            solveManifold);
                }
                /// solve all friction constraints
                int numFrictionPoolConstraints = m_tmpSolverContactFrictionConstraintPool.size();
                for (int j = 0; j < numFrictionPoolConstraints; j++) {
                    btSolverConstraint solveManifold =
                            m_tmpSolverContactFrictionConstraintPool.get(
                                    m_orderFrictionConstraintPool.get(j));
                    double totalImpulse =
                            m_tmpSolverContactConstraintPool.get(solveManifold.m_frictionIndex)
                                    .m_appliedImpulse;

                    if (totalImpulse > 0) {
                        solveManifold.m_lowerLimit = -(solveManifold.m_friction * totalImpulse);
                        solveManifold.m_upperLimit = solveManifold.m_friction * totalImpulse;

                        resolveSingleConstraintRowGeneric(
                                m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                solveManifold);
                    }
                }

                int numRollingFrictionPoolConstraints =
                        m_tmpSolverContactRollingFrictionConstraintPool.size();
                for (int j = 0; j < numRollingFrictionPoolConstraints; j++) {
                    btSolverConstraint rollingFrictionConstraint =
                            m_tmpSolverContactRollingFrictionConstraintPool.get(j);
                    double totalImpulse =
                            m_tmpSolverContactConstraintPool.get(
                                            rollingFrictionConstraint.m_frictionIndex)
                                    .m_appliedImpulse;
                    if (totalImpulse > 0) {
                        double rollingFrictionMagnitude =
                                rollingFrictionConstraint.m_friction * totalImpulse;
                        if (rollingFrictionMagnitude > rollingFrictionConstraint.m_friction)
                            rollingFrictionMagnitude = rollingFrictionConstraint.m_friction;

                        rollingFrictionConstraint.m_lowerLimit = -rollingFrictionMagnitude;
                        rollingFrictionConstraint.m_upperLimit = rollingFrictionMagnitude;

                        resolveSingleConstraintRowGeneric(
                                m_tmpSolverBodyPool.get(rollingFrictionConstraint.m_solverBodyIdA),
                                m_tmpSolverBodyPool.get(rollingFrictionConstraint.m_solverBodyIdB),
                                rollingFrictionConstraint);
                    }
                }
            }
        }
        return 0.f;
    }

    protected void solveGroupCacheFriendlySplitImpulseIterations(
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifoldPtr,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo infoGlobal,
            btIDebugDraw debugDrawer) {
        int iteration;
        if (infoGlobal.m_splitImpulse != 0) {
            if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_SIMD) != 0) {
                for (iteration = 0; iteration < infoGlobal.m_numIterations; iteration++) {
                    {
                        int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
                        int j;
                        for (j = 0; j < numPoolConstraints; j++) {
                            final btSolverConstraint solveManifold =
                                    m_tmpSolverContactConstraintPool.get(
                                            m_orderTmpConstraintPool.get(j));

                            resolveSplitPenetrationSIMD(
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                    solveManifold);
                        }
                    }
                }
            } else {
                for (iteration = 0; iteration < infoGlobal.m_numIterations; iteration++) {
                    {
                        int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
                        int j;
                        for (j = 0; j < numPoolConstraints; j++) {
                            final btSolverConstraint solveManifold =
                                    m_tmpSolverContactConstraintPool.get(
                                            m_orderTmpConstraintPool.get(j));

                            resolveSplitPenetrationImpulseCacheFriendly(
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdA),
                                    m_tmpSolverBodyPool.get(solveManifold.m_solverBodyIdB),
                                    solveManifold);
                        }
                    }
                }
            }
        }
    }

    protected double solveGroupCacheFriendlyIterations(
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifoldPtr,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo infoGlobal,
            btIDebugDraw debugDrawer) {
        {
            /// this is a special step to resolve penetrations (just for contacts)
            solveGroupCacheFriendlySplitImpulseIterations(
                    bodies,
                    numBodies,
                    manifoldPtr,
                    numManifolds,
                    constraints,
                    numConstraints,
                    infoGlobal,
                    debugDrawer);

            int maxIterations =
                    m_maxOverrideNumSolverIterations > infoGlobal.m_numIterations
                            ? m_maxOverrideNumSolverIterations
                            : infoGlobal.m_numIterations;

            for (int iteration = 0; iteration < maxIterations; iteration++)
            // for ( int iteration = maxIterations-1  ; iteration >= 0;iteration--)
            {
                solveSingleIteration(
                        iteration,
                        bodies,
                        numBodies,
                        manifoldPtr,
                        numManifolds,
                        constraints,
                        numConstraints,
                        infoGlobal,
                        debugDrawer);
            }
        }
        return 0.f;
    }

    protected double solveGroupCacheFriendlyFinish(
            btCollisionObject[] bodies, int numBodies, btContactSolverInfo infoGlobal) {
        int numPoolConstraints = m_tmpSolverContactConstraintPool.size();
        int i, j;

        if ((infoGlobal.m_solverMode & btContactSolverInfoData.SOLVER_USE_WARMSTARTING) != 0) {
            for (j = 0; j < numPoolConstraints; j++) {
                final btSolverConstraint solveManifold = m_tmpSolverContactConstraintPool.get(j);
                btManifoldPoint pt = (btManifoldPoint) solveManifold.m_originalContactPoint;
                pt.m_appliedImpulse = solveManifold.m_appliedImpulse;
                pt.m_appliedImpulseLateral1 =
                        m_tmpSolverContactFrictionConstraintPool.get(solveManifold.m_frictionIndex)
                                .m_appliedImpulse;
                if ((infoGlobal.m_solverMode
                                & btContactSolverInfoData.SOLVER_USE_2_FRICTION_DIRECTIONS)
                        != 0) {
                    pt.m_appliedImpulseLateral2 =
                            m_tmpSolverContactFrictionConstraintPool.get(
                                            solveManifold.m_frictionIndex + 1)
                                    .m_appliedImpulse;
                }
                // do a callback here?
            }
        }

        numPoolConstraints = m_tmpSolverNonContactConstraintPool.size();
        for (j = 0; j < numPoolConstraints; j++) {
            final btSolverConstraint solverConstr = m_tmpSolverNonContactConstraintPool.get(j);
            btTypedConstraint constr = (btTypedConstraint) solverConstr.m_originalContactPoint;
            btJointFeedback fb = constr.getJointFeedback();
            if (fb != null) {
                fb.m_appliedForceBodyA.addLocal(
                        solverConstr
                                .m_contactNormal1
                                .mul(solverConstr.m_appliedImpulse)
                                .mul(constr.getRigidBodyA().getLinearFactor())
                                .div(infoGlobal.m_timeStep));
                fb.m_appliedForceBodyB.addLocal(
                        solverConstr
                                .m_contactNormal2
                                .mul(solverConstr.m_appliedImpulse)
                                .mul(constr.getRigidBodyB().getLinearFactor())
                                .div(infoGlobal.m_timeStep));
                fb.m_appliedTorqueBodyA.addLocal(
                        solverConstr
                                .m_relpos1CrossNormal
                                .mul(constr.getRigidBodyA().getAngularFactor())
                                .mul(solverConstr.m_appliedImpulse)
                                .div(infoGlobal.m_timeStep));
                fb.m_appliedTorqueBodyB.addLocal(
                        solverConstr
                                .m_relpos2CrossNormal
                                .mul(constr.getRigidBodyB().getAngularFactor())
                                .mul(solverConstr.m_appliedImpulse)
                                .div(infoGlobal.m_timeStep)); /*RGM ???? */
            }

            constr.internalSetAppliedImpulse(solverConstr.m_appliedImpulse);
            if (Math.abs(solverConstr.m_appliedImpulse) >= constr.getBreakingImpulseThreshold()) {
                constr.setEnabled(false);
            }
        }

        for (i = 0; i < m_tmpSolverBodyPool.size(); i++) {
            btRigidBody body = m_tmpSolverBodyPool.get(i).m_originalBody;
            if (body != null) {
                if (infoGlobal.m_splitImpulse != 0)
                    m_tmpSolverBodyPool
                            .get(i)
                            .writebackVelocityAndTransform(
                                    infoGlobal.m_timeStep, infoGlobal.m_splitImpulseTurnErp);
                else m_tmpSolverBodyPool.get(i).writebackVelocity();

                m_tmpSolverBodyPool
                        .get(i)
                        .m_originalBody
                        .setLinearVelocity(
                                m_tmpSolverBodyPool
                                        .get(i)
                                        .m_linearVelocity
                                        .add(m_tmpSolverBodyPool.get(i).m_externalForceImpulse));

                m_tmpSolverBodyPool
                        .get(i)
                        .m_originalBody
                        .setAngularVelocity(
                                m_tmpSolverBodyPool
                                        .get(i)
                                        .m_angularVelocity
                                        .add(m_tmpSolverBodyPool.get(i).m_externalTorqueImpulse));

                if (infoGlobal.m_splitImpulse != 0)
                    m_tmpSolverBodyPool
                            .get(i)
                            .m_originalBody
                            .setWorldTransform(m_tmpSolverBodyPool.get(i).m_worldTransform);

                m_tmpSolverBodyPool.get(i).m_originalBody.setCompanionId(-1);
            }
        }

        m_tmpSolverContactConstraintPool.resizeNoInitialize(0);
        m_tmpSolverNonContactConstraintPool.resizeNoInitialize(0);
        m_tmpSolverContactFrictionConstraintPool.resizeNoInitialize(0);
        m_tmpSolverContactRollingFrictionConstraintPool.resizeNoInitialize(0);

        m_tmpSolverBodyPool.resizeNoInitialize(0);
        return 0.f;
    }

    /// btSequentialImpulseConstraintSolver Sequentially applies impulses
    @Override
    public double solveGroup(
            btCollisionObject[] bodies,
            int numBodies,
            btPersistentManifold[] manifoldPtr,
            int numManifolds,
            btTypedConstraint[] constraints,
            int numConstraints,
            btContactSolverInfo infoGlobal,
            btIDebugDraw debugDrawer,
            btDispatcher dispatcher) {
        // you need to provide at least some bodies

        solveGroupCacheFriendlySetup(
                bodies,
                numBodies,
                manifoldPtr,
                numManifolds,
                constraints,
                numConstraints,
                infoGlobal,
                debugDrawer);

        solveGroupCacheFriendlyIterations(
                bodies,
                numBodies,
                manifoldPtr,
                numManifolds,
                constraints,
                numConstraints,
                infoGlobal,
                debugDrawer);

        solveGroupCacheFriendlyFinish(bodies, numBodies, infoGlobal);

        return 0.f;
    }

    /// clear internal cached data and reset random seed
    @Override
    public void reset() {
        m_btSeed2 = 0;
    }

    public void setRandSeed(long seed) {
        m_btSeed2 = seed;
    }

    public long getRandSeed() {
        return m_btSeed2;
    }

    @Override
    public int getSolverType() {
        return BT_SEQUENTIAL_IMPULSE_SOLVER;
    }
}
