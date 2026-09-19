// Port of BulletDynamics/ConstraintSolver/btConeTwistConstraint.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btMinMax;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btTransformUtil;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btConeTwistConstraint can be used to simulate ragdoll joints (upper arm, leg etc) */
public class btConeTwistConstraint extends btTypedConstraint {
    // enum btConeTwistFlags
    public static final int BT_CONETWIST_FLAGS_LIN_CFM = 1;
    public static final int BT_CONETWIST_FLAGS_LIN_ERP = 2;
    public static final int BT_CONETWIST_FLAGS_ANG_CFM = 4;

    /** #define CONETWIST_USE_OBSOLETE_SOLVER false */
    public static final boolean CONETWIST_USE_OBSOLETE_SOLVER = false;

    /** #define CONETWIST_DEF_FIX_THRESH btScalar(.05f) */
    public static final double CONETWIST_DEF_FIX_THRESH = (double) .05f;

    /** sizeof(btConeTwistConstraintDoubleData) */
    public static final int SIZEOF_btConeTwistConstraintDoubleData = 392;

    /** file static {@code static btVector3 vTwist(1,0,0);} twist axis in constraint's space */
    private static final btVector3 vTwist = new btVector3(1, 0, 0);

    /** C++ function-local {@code static bool bDoTorque = true;} in solveConstraintObsolete. */
    private static boolean bDoTorque = true;

    /** file-static SIMD_FORCE_INLINE computeAngularImpulseDenominator(axis, invInertiaWorld) */
    static double computeAngularImpulseDenominator(btVector3 axis, btMatrix3x3 invInertiaWorld) {
        btVector3 vec = btMatrix3x3.mul(axis, invInertiaWorld);
        return axis.dot(vec);
    }

    public final btJacobianEntry[] m_jac = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    }; // 3 orthogonal linear constraints

    public final btTransform m_rbAFrame = new btTransform();
    public final btTransform m_rbBFrame = new btTransform();

    public double m_limitSoftness;
    public double m_biasFactor;
    public double m_relaxationFactor;

    public double m_damping;

    public double m_swingSpan1;
    public double m_swingSpan2;
    public double m_twistSpan;

    public double m_fixThresh;

    public final btVector3 m_swingAxis = new btVector3();
    public final btVector3 m_twistAxis = new btVector3();

    public double m_kSwing;
    public double m_kTwist;

    public double m_twistLimitSign;
    public double m_swingCorrection;
    public double m_twistCorrection;

    public double m_twistAngle;

    public double m_accSwingLimitImpulse;
    public double m_accTwistLimitImpulse;

    public boolean m_angularOnly;
    public boolean m_solveTwistLimit;
    public boolean m_solveSwingLimit;

    public boolean m_useSolveConstraintObsolete;

    // not yet used...
    public double m_swingLimitRatio;
    public double m_twistLimitRatio;
    public final btVector3 m_twistAxisA = new btVector3();

    // motor
    public boolean m_bMotorEnabled;
    public boolean m_bNormalizedMotorStrength;
    public final btQuaternion m_qTarget = new btQuaternion();
    public double m_maxMotorImpulse;
    public final btVector3 m_accMotorImpulse = new btVector3();

    // parameters
    public int m_flags;
    public double m_linCFM;
    public double m_linERP;
    public double m_angCFM;

    public btConeTwistConstraint(
            btRigidBody rbA, btRigidBody rbB, btTransform rbAFrame, btTransform rbBFrame) {
        super(CONETWIST_CONSTRAINT_TYPE, rbA, rbB);
        m_rbAFrame.set(rbAFrame);
        m_rbBFrame.set(rbBFrame);
        m_angularOnly = false;
        m_useSolveConstraintObsolete = CONETWIST_USE_OBSOLETE_SOLVER;
        init();
    }

    public btConeTwistConstraint(btRigidBody rbA, btTransform rbAFrame) {
        super(CONETWIST_CONSTRAINT_TYPE, rbA);
        m_rbAFrame.set(rbAFrame);
        m_angularOnly = false;
        m_useSolveConstraintObsolete = CONETWIST_USE_OBSOLETE_SOLVER;
        m_rbBFrame.set(m_rbAFrame);
        m_rbBFrame.setOrigin(new btVector3(0., 0., 0.));
        init();
    }

    protected void init() {
        m_angularOnly = false;
        m_solveTwistLimit = false;
        m_solveSwingLimit = false;
        m_bMotorEnabled = false;
        m_maxMotorImpulse = -1;

        setLimit(btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT, btScalar.BT_LARGE_FLOAT);
        m_damping = 0.01;
        m_fixThresh = CONETWIST_DEF_FIX_THRESH;
        m_flags = 0;
        m_linCFM = (double) 0.f;
        m_linERP = (double) 0.7f;
        m_angCFM = (double) 0.f;
    }

    @Override
    public void getInfo1(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            info.m_numConstraintRows = 3;
            info.nub = 3;
            calcAngleInfo2(
                    m_rbA.getCenterOfMassTransform(),
                    m_rbB.getCenterOfMassTransform(),
                    m_rbA.getInvInertiaTensorWorld(),
                    m_rbB.getInvInertiaTensorWorld());
            if (m_solveSwingLimit) {
                info.m_numConstraintRows++;
                info.nub--;
                if ((m_swingSpan1 < m_fixThresh) && (m_swingSpan2 < m_fixThresh)) {
                    info.m_numConstraintRows++;
                    info.nub--;
                }
            }
            if (m_solveTwistLimit) {
                info.m_numConstraintRows++;
                info.nub--;
            }
        }
    }

    public void getInfo1NonVirtual(btConstraintInfo1 info) {
        // always reserve 6 rows: object transform is not available on SPU
        info.m_numConstraintRows = 6;
        info.nub = 0;
    }

    @Override
    public void getInfo2(btConstraintInfo2 info) {
        getInfo2NonVirtual(
                info,
                m_rbA.getCenterOfMassTransform(),
                m_rbB.getCenterOfMassTransform(),
                m_rbA.getInvInertiaTensorWorld(),
                m_rbB.getInvInertiaTensorWorld());
    }

    public void getInfo2NonVirtual(
            btConstraintInfo2 info,
            btTransform transA,
            btTransform transB,
            btMatrix3x3 invInertiaWorldA,
            btMatrix3x3 invInertiaWorldB) {
        calcAngleInfo2(transA, transB, invInertiaWorldA, invInertiaWorldB);

        final int skip = info.rowskip;
        // set jacobian
        info.m_J1linearAxis.set(0, 1);
        info.m_J1linearAxis.set(skip + 1, 1);
        info.m_J1linearAxis.set(2 * skip + 2, 1);
        btVector3 a1 = transA.getBasis().mul(m_rbAFrame.getOrigin());
        {
            btVector3 angular0 = new btVector3();
            btVector3 angular1 = new btVector3();
            btVector3 angular2 = new btVector3();
            btVector3 a1neg = a1.negate();
            a1neg.getSkewSymmetricMatrix(angular0, angular1, angular2);
            info.m_J1angularAxis.setVec(0, angular0);
            info.m_J1angularAxis.setVec(skip, angular1);
            info.m_J1angularAxis.setVec(2 * skip, angular2);
        }
        info.m_J2linearAxis.set(0, -1);
        info.m_J2linearAxis.set(skip + 1, -1);
        info.m_J2linearAxis.set(2 * skip + 2, -1);
        btVector3 a2 = transB.getBasis().mul(m_rbBFrame.getOrigin());
        {
            btVector3 angular0 = new btVector3();
            btVector3 angular1 = new btVector3();
            btVector3 angular2 = new btVector3();
            a2.getSkewSymmetricMatrix(angular0, angular1, angular2);
            info.m_J2angularAxis.setVec(0, angular0);
            info.m_J2angularAxis.setVec(skip, angular1);
            info.m_J2angularAxis.setVec(2 * skip, angular2);
        }
        // set right hand side
        double linERP = ((m_flags & BT_CONETWIST_FLAGS_LIN_ERP) != 0) ? m_linERP : info.erp;
        double k = info.fps * linERP;
        int j;
        for (j = 0; j < 3; j++) {
            info.m_constraintError.set(
                    j * skip,
                    k
                            * (a2.get(j)
                                    + transB.getOrigin().get(j)
                                    - a1.get(j)
                                    - transA.getOrigin().get(j)));
            info.m_lowerLimit.set(j * skip, -btScalar.SIMD_INFINITY);
            info.m_upperLimit.set(j * skip, btScalar.SIMD_INFINITY);
            if ((m_flags & BT_CONETWIST_FLAGS_LIN_CFM) != 0) {
                info.cfm.set(j * skip, m_linCFM);
            }
        }
        int row = 3;
        int srow = row * skip;
        btVector3 ax1;
        // angular limits
        if (m_solveSwingLimit) {
            btScalarPtr J1 = info.m_J1angularAxis;
            btScalarPtr J2 = info.m_J2angularAxis;
            if ((m_swingSpan1 < m_fixThresh) && (m_swingSpan2 < m_fixThresh)) {
                btTransform trA = transA.mul(m_rbAFrame);
                btVector3 p = trA.getBasis().getColumn(1);
                btVector3 q = trA.getBasis().getColumn(2);
                int srow1 = srow + skip;
                J1.set(srow + 0, p.get(0));
                J1.set(srow + 1, p.get(1));
                J1.set(srow + 2, p.get(2));
                J1.set(srow1 + 0, q.get(0));
                J1.set(srow1 + 1, q.get(1));
                J1.set(srow1 + 2, q.get(2));
                J2.set(srow + 0, -p.get(0));
                J2.set(srow + 1, -p.get(1));
                J2.set(srow + 2, -p.get(2));
                J2.set(srow1 + 0, -q.get(0));
                J2.set(srow1 + 1, -q.get(1));
                J2.set(srow1 + 2, -q.get(2));
                double fact = info.fps * m_relaxationFactor;
                info.m_constraintError.set(srow, fact * m_swingAxis.dot(p));
                info.m_constraintError.set(srow1, fact * m_swingAxis.dot(q));
                info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                info.m_lowerLimit.set(srow1, -btScalar.SIMD_INFINITY);
                info.m_upperLimit.set(srow1, btScalar.SIMD_INFINITY);
                srow = srow1 + skip;
            } else {
                ax1 = m_swingAxis.mul(m_relaxationFactor).mul(m_relaxationFactor);
                J1.set(srow + 0, ax1.get(0));
                J1.set(srow + 1, ax1.get(1));
                J1.set(srow + 2, ax1.get(2));
                J2.set(srow + 0, -ax1.get(0));
                J2.set(srow + 1, -ax1.get(1));
                J2.set(srow + 2, -ax1.get(2));
                double k2 = info.fps * m_biasFactor;

                info.m_constraintError.set(srow, k2 * m_swingCorrection);
                if ((m_flags & BT_CONETWIST_FLAGS_ANG_CFM) != 0) {
                    info.cfm.set(srow, m_angCFM);
                }
                // m_swingCorrection is always positive or 0
                info.m_lowerLimit.set(srow, 0);
                info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                srow += skip;
            }
        }
        if (m_solveTwistLimit) {
            ax1 = m_twistAxis.mul(m_relaxationFactor).mul(m_relaxationFactor);
            btScalarPtr J1 = info.m_J1angularAxis;
            btScalarPtr J2 = info.m_J2angularAxis;
            J1.set(srow + 0, ax1.get(0));
            J1.set(srow + 1, ax1.get(1));
            J1.set(srow + 2, ax1.get(2));
            J2.set(srow + 0, -ax1.get(0));
            J2.set(srow + 1, -ax1.get(1));
            J2.set(srow + 2, -ax1.get(2));
            double k2 = info.fps * m_biasFactor;
            info.m_constraintError.set(srow, k2 * m_twistCorrection);
            if ((m_flags & BT_CONETWIST_FLAGS_ANG_CFM) != 0) {
                info.cfm.set(srow, m_angCFM);
            }
            if (m_twistSpan > 0.0f) {
                if (m_twistCorrection > 0.0f) {
                    info.m_lowerLimit.set(srow, 0);
                    info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                } else {
                    info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                    info.m_upperLimit.set(srow, 0);
                }
            } else {
                info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
            }
            srow += skip;
        }
    }

    @Override
    public void buildJacobian() {
        if (m_useSolveConstraintObsolete) {
            m_appliedImpulse = 0.;
            m_accTwistLimitImpulse = 0.;
            m_accSwingLimitImpulse = 0.;
            m_accMotorImpulse.set(new btVector3(0., 0., 0.));

            if (!m_angularOnly) {
                btVector3 pivotAInW = m_rbA.getCenterOfMassTransform().mul(m_rbAFrame.getOrigin());
                btVector3 pivotBInW = m_rbB.getCenterOfMassTransform().mul(m_rbBFrame.getOrigin());
                btVector3 relPos = pivotBInW.sub(pivotAInW);

                btVector3[] normal = {new btVector3(), new btVector3(), new btVector3()};
                if (relPos.length2() > btScalar.SIMD_EPSILON) {
                    normal[0].set(relPos.normalized());
                } else {
                    normal[0].setValue(1.0, 0, 0);
                }

                btVector3.btPlaneSpace1(normal[0], normal[1], normal[2]);

                for (int i = 0; i < 3; i++) {
                    m_jac[i].set(
                            new btJacobianEntry(
                                    m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                                    m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                                    pivotAInW.sub(m_rbA.getCenterOfMassPosition()),
                                    pivotBInW.sub(m_rbB.getCenterOfMassPosition()),
                                    normal[i],
                                    m_rbA.getInvInertiaDiagLocal(),
                                    m_rbA.getInvMass(),
                                    m_rbB.getInvInertiaDiagLocal(),
                                    m_rbB.getInvMass()));
                }
            }

            calcAngleInfo2(
                    m_rbA.getCenterOfMassTransform(),
                    m_rbB.getCenterOfMassTransform(),
                    m_rbA.getInvInertiaTensorWorld(),
                    m_rbB.getInvInertiaTensorWorld());
        }
    }

    @Override
    public void solveConstraintObsolete(btSolverBody bodyA, btSolverBody bodyB, double timeStep) {
        if (m_useSolveConstraintObsolete) {
            btVector3 pivotAInW = m_rbA.getCenterOfMassTransform().mul(m_rbAFrame.getOrigin());
            btVector3 pivotBInW = m_rbB.getCenterOfMassTransform().mul(m_rbBFrame.getOrigin());

            double tau = 0.3;

            // linear part
            if (!m_angularOnly) {
                btVector3 rel_pos1 = pivotAInW.sub(m_rbA.getCenterOfMassPosition());
                btVector3 rel_pos2 = pivotBInW.sub(m_rbB.getCenterOfMassPosition());

                btVector3 vel1 = new btVector3();
                bodyA.internalGetVelocityInLocalPointObsolete(rel_pos1, vel1);
                btVector3 vel2 = new btVector3();
                bodyB.internalGetVelocityInLocalPointObsolete(rel_pos2, vel2);
                btVector3 vel = vel1.sub(vel2);

                for (int i = 0; i < 3; i++) {
                    btVector3 normal = m_jac[i].m_linearJointAxis;
                    double jacDiagABInv = 1. / m_jac[i].getDiagonal();

                    double rel_vel;
                    rel_vel = normal.dot(vel);
                    // positional error (zeroth order error)
                    double depth =
                            -(pivotAInW.sub(pivotBInW))
                                    .dot(normal); // this is the error projected on the normal
                    double impulse = depth * tau / timeStep * jacDiagABInv - rel_vel * jacDiagABInv;
                    m_appliedImpulse += impulse;

                    btVector3 ftorqueAxis1 = rel_pos1.cross(normal);
                    btVector3 ftorqueAxis2 = rel_pos2.cross(normal);
                    bodyA.internalApplyImpulse(
                            normal.mul(m_rbA.getInvMass()),
                            m_rbA.getInvInertiaTensorWorld().mul(ftorqueAxis1),
                            impulse);
                    bodyB.internalApplyImpulse(
                            normal.mul(m_rbB.getInvMass()),
                            m_rbB.getInvInertiaTensorWorld().mul(ftorqueAxis2),
                            -impulse);
                }
            }

            // apply motor
            if (m_bMotorEnabled) {
                // compute current and predicted transforms
                btTransform trACur = new btTransform(m_rbA.getCenterOfMassTransform());
                btTransform trBCur = new btTransform(m_rbB.getCenterOfMassTransform());
                btVector3 omegaA = new btVector3();
                bodyA.internalGetAngularVelocity(omegaA);
                btVector3 omegaB = new btVector3();
                bodyB.internalGetAngularVelocity(omegaB);
                btTransform trAPred = new btTransform();
                trAPred.setIdentity();
                btVector3 zerovec = new btVector3(0, 0, 0);
                btTransformUtil.integrateTransform(trACur, zerovec, omegaA, timeStep, trAPred);
                btTransform trBPred = new btTransform();
                trBPred.setIdentity();
                btTransformUtil.integrateTransform(trBCur, zerovec, omegaB, timeStep, trBPred);

                // compute desired transforms in world
                btTransform trPose = new btTransform(m_qTarget);
                btTransform trABDes = m_rbBFrame.mul(trPose).mul(m_rbAFrame.inverse());
                btTransform trADes = trBPred.mul(trABDes);
                btTransform trBDes = trAPred.mul(trABDes.inverse());

                // compute desired omegas in world
                btVector3 omegaADes = new btVector3();
                btVector3 omegaBDes = new btVector3();

                btTransformUtil.calculateVelocity(trACur, trADes, timeStep, zerovec, omegaADes);
                btTransformUtil.calculateVelocity(trBCur, trBDes, timeStep, zerovec, omegaBDes);

                // compute delta omegas
                btVector3 dOmegaA = omegaADes.sub(omegaA);
                btVector3 dOmegaB = omegaBDes.sub(omegaB);

                // compute weighted avg axis of dOmega (weighting based on inertias)
                // (C++: axisA/axisB uninitialised when not assigned; Java zero)
                btVector3 axisA = new btVector3();
                btVector3 axisB = new btVector3();
                double kAxisAInv = 0, kAxisBInv = 0;

                if (dOmegaA.length2() > btScalar.SIMD_EPSILON) {
                    axisA = dOmegaA.normalized();
                    kAxisAInv = getRigidBodyA().computeAngularImpulseDenominator(axisA);
                }

                if (dOmegaB.length2() > btScalar.SIMD_EPSILON) {
                    axisB = dOmegaB.normalized();
                    kAxisBInv = getRigidBodyB().computeAngularImpulseDenominator(axisB);
                }

                btVector3 avgAxis = axisA.mul(kAxisAInv).add(axisB.mul(kAxisBInv));

                if (bDoTorque && avgAxis.length2() > btScalar.SIMD_EPSILON) {
                    avgAxis.normalize();
                    kAxisAInv = getRigidBodyA().computeAngularImpulseDenominator(avgAxis);
                    kAxisBInv = getRigidBodyB().computeAngularImpulseDenominator(avgAxis);
                    double kInvCombined = kAxisAInv + kAxisBInv;

                    btVector3 impulse =
                            dOmegaA.mul(kAxisAInv)
                                    .sub(dOmegaB.mul(kAxisBInv))
                                    .div(kInvCombined * kInvCombined);

                    if (m_maxMotorImpulse >= 0) {
                        double fMaxImpulse = m_maxMotorImpulse;
                        if (m_bNormalizedMotorStrength) fMaxImpulse /= kAxisAInv;

                        btVector3 newUnclampedAccImpulse = m_accMotorImpulse.add(impulse);
                        double newUnclampedMag = newUnclampedAccImpulse.length();
                        if (newUnclampedMag > fMaxImpulse) {
                            newUnclampedAccImpulse.normalize();
                            newUnclampedAccImpulse.mulLocal(fMaxImpulse);
                            impulse = newUnclampedAccImpulse.sub(m_accMotorImpulse);
                        }
                        m_accMotorImpulse.addLocal(impulse);
                    }

                    double impulseMag = impulse.length();
                    btVector3 impulseAxis = impulse.div(impulseMag);

                    bodyA.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbA.getInvInertiaTensorWorld().mul(impulseAxis),
                            impulseMag);
                    bodyB.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbB.getInvInertiaTensorWorld().mul(impulseAxis),
                            -impulseMag);
                }
            } else if (m_damping > btScalar.SIMD_EPSILON) // no motor: do a little damping
            {
                btVector3 angVelA = new btVector3();
                bodyA.internalGetAngularVelocity(angVelA);
                btVector3 angVelB = new btVector3();
                bodyB.internalGetAngularVelocity(angVelB);
                btVector3 relVel = angVelB.sub(angVelA);
                if (relVel.length2() > btScalar.SIMD_EPSILON) {
                    btVector3 relVelAxis = relVel.normalized();
                    double m_kDamping =
                            1.
                                    / (getRigidBodyA().computeAngularImpulseDenominator(relVelAxis)
                                            + getRigidBodyB()
                                                    .computeAngularImpulseDenominator(relVelAxis));
                    btVector3 impulse = relVel.mul(m_damping * m_kDamping);

                    double impulseMag = impulse.length();
                    btVector3 impulseAxis = impulse.div(impulseMag);
                    bodyA.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbA.getInvInertiaTensorWorld().mul(impulseAxis),
                            impulseMag);
                    bodyB.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbB.getInvInertiaTensorWorld().mul(impulseAxis),
                            -impulseMag);
                }
            }

            // joint limits
            {
                /// solve angular part
                btVector3 angVelA = new btVector3();
                bodyA.internalGetAngularVelocity(angVelA);
                btVector3 angVelB = new btVector3();
                bodyB.internalGetAngularVelocity(angVelB);

                // solve swing limit
                if (m_solveSwingLimit) {
                    double amplitude =
                            m_swingLimitRatio * m_swingCorrection * m_biasFactor / timeStep;
                    double relSwingVel = (angVelB.sub(angVelA)).dot(m_swingAxis);
                    if (relSwingVel > 0)
                        amplitude += m_swingLimitRatio * relSwingVel * m_relaxationFactor;
                    double impulseMag = amplitude * m_kSwing;

                    // Clamp the accumulated impulse
                    double temp = m_accSwingLimitImpulse;
                    m_accSwingLimitImpulse =
                            btMinMax.btMax(m_accSwingLimitImpulse + impulseMag, 0.0);
                    impulseMag = m_accSwingLimitImpulse - temp;

                    btVector3 impulse = m_swingAxis.mul(impulseMag);

                    // don't let cone response affect twist
                    // (this can happen since body A's twist doesn't match body B's AND we use an
                    // elliptical cone limit)
                    {
                        btVector3 impulseTwistCouple = m_twistAxisA.mul(impulse.dot(m_twistAxisA));
                        btVector3 impulseNoTwistCouple = impulse.sub(impulseTwistCouple);
                        impulse = impulseNoTwistCouple;
                    }

                    impulseMag = impulse.length();
                    btVector3 noTwistSwingAxis = impulse.div(impulseMag);

                    bodyA.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbA.getInvInertiaTensorWorld().mul(noTwistSwingAxis),
                            impulseMag);
                    bodyB.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbB.getInvInertiaTensorWorld().mul(noTwistSwingAxis),
                            -impulseMag);
                }

                // solve twist limit
                if (m_solveTwistLimit) {
                    double amplitude =
                            m_twistLimitRatio * m_twistCorrection * m_biasFactor / timeStep;
                    double relTwistVel = (angVelB.sub(angVelA)).dot(m_twistAxis);
                    if (relTwistVel
                            > 0) // only damp when moving towards limit (m_twistAxis flipping is
                        // important)
                        amplitude += m_twistLimitRatio * relTwistVel * m_relaxationFactor;
                    double impulseMag = amplitude * m_kTwist;

                    // Clamp the accumulated impulse
                    double temp = m_accTwistLimitImpulse;
                    m_accTwistLimitImpulse =
                            btMinMax.btMax(m_accTwistLimitImpulse + impulseMag, 0.0);
                    impulseMag = m_accTwistLimitImpulse - temp;

                    bodyA.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbA.getInvInertiaTensorWorld().mul(m_twistAxis),
                            impulseMag);
                    bodyB.internalApplyImpulse(
                            new btVector3(0, 0, 0),
                            m_rbB.getInvInertiaTensorWorld().mul(m_twistAxis),
                            -impulseMag);
                }
            }
        }
    }

    public void updateRHS(double timeStep) {}

    public btRigidBody getRigidBodyA() {
        return m_rbA;
    }

    public btRigidBody getRigidBodyB() {
        return m_rbB;
    }

    public void setAngularOnly(boolean angularOnly) {
        m_angularOnly = angularOnly;
    }

    public void setLimit(int limitIndex, double limitValue) {
        switch (limitIndex) {
            case 3:
                m_twistSpan = limitValue;
                break;
            case 4:
                m_swingSpan2 = limitValue;
                break;
            case 5:
                m_swingSpan1 = limitValue;
                break;
            default:
        }
    }

    public void setLimit(
            double _swingSpan1,
            double _swingSpan2,
            double _twistSpan,
            double _softness,
            double _biasFactor,
            double _relaxationFactor) {
        m_swingSpan1 = _swingSpan1;
        m_swingSpan2 = _swingSpan2;
        m_twistSpan = _twistSpan;

        m_limitSoftness = _softness;
        m_biasFactor = _biasFactor;
        m_relaxationFactor = _relaxationFactor;
    }

    public void setLimit(
            double _swingSpan1,
            double _swingSpan2,
            double _twistSpan,
            double _softness,
            double _biasFactor) {
        setLimit(_swingSpan1, _swingSpan2, _twistSpan, _softness, _biasFactor, (double) 1.0f);
    }

    public void setLimit(
            double _swingSpan1, double _swingSpan2, double _twistSpan, double _softness) {
        setLimit(_swingSpan1, _swingSpan2, _twistSpan, _softness, (double) 0.3f, (double) 1.0f);
    }

    public void setLimit(double _swingSpan1, double _swingSpan2, double _twistSpan) {
        setLimit(_swingSpan1, _swingSpan2, _twistSpan, (double) 1.f, (double) 0.3f, (double) 1.0f);
    }

    public btTransform getAFrame() {
        return m_rbAFrame;
    }

    public btTransform getBFrame() {
        return m_rbBFrame;
    }

    public int getSolveTwistLimit() {
        return m_solveTwistLimit ? 1 : 0;
    }

    /** Upstream bug kept: returns m_solveTwistLimit. */
    public int getSolveSwingLimit() {
        return m_solveTwistLimit ? 1 : 0;
    }

    public double getTwistLimitSign() {
        return m_twistLimitSign;
    }

    public void calcAngleInfo() {
        m_swingCorrection = 0.;
        m_twistLimitSign = 0.;
        m_solveTwistLimit = false;
        m_solveSwingLimit = false;

        // C++: b1Axis2/b1Axis3 uninitialised when the matching swing span < 0.05f; Java zero
        btVector3 b1Axis1, b1Axis2 = new btVector3(), b1Axis3 = new btVector3();
        btVector3 b2Axis1;

        b1Axis1 =
                getRigidBodyA()
                        .getCenterOfMassTransform()
                        .getBasis()
                        .mul(this.m_rbAFrame.getBasis().getColumn(0));
        b2Axis1 =
                getRigidBodyB()
                        .getCenterOfMassTransform()
                        .getBasis()
                        .mul(this.m_rbBFrame.getBasis().getColumn(0));

        double swing1 = 0., swing2 = 0.;

        double swx = 0., swy = 0.;
        double thresh = 10.;
        double fact;

        // Get Frame into world space
        if (m_swingSpan1 >= (double) 0.05f) {
            b1Axis2 =
                    getRigidBodyA()
                            .getCenterOfMassTransform()
                            .getBasis()
                            .mul(this.m_rbAFrame.getBasis().getColumn(1));
            swx = b2Axis1.dot(b1Axis1);
            swy = b2Axis1.dot(b1Axis2);
            swing1 = btScalar.btAtan2Fast(swy, swx);
            fact = (swy * swy + swx * swx) * thresh * thresh;
            fact /= (fact + 1.0);
            swing1 *= fact;
        }

        if (m_swingSpan2 >= (double) 0.05f) {
            b1Axis3 =
                    getRigidBodyA()
                            .getCenterOfMassTransform()
                            .getBasis()
                            .mul(this.m_rbAFrame.getBasis().getColumn(2));
            swx = b2Axis1.dot(b1Axis1);
            swy = b2Axis1.dot(b1Axis3);
            swing2 = btScalar.btAtan2Fast(swy, swx);
            fact = (swy * swy + swx * swx) * thresh * thresh;
            fact /= (fact + 1.0);
            swing2 *= fact;
        }

        double RMaxAngle1Sq = (double) 1.0f / (m_swingSpan1 * m_swingSpan1);
        double RMaxAngle2Sq = (double) 1.0f / (m_swingSpan2 * m_swingSpan2);
        double EllipseAngle =
                Math.abs(swing1 * swing1) * RMaxAngle1Sq + Math.abs(swing2 * swing2) * RMaxAngle2Sq;

        if (EllipseAngle > 1.0f) {
            m_swingCorrection = EllipseAngle - (double) 1.0f;
            m_solveSwingLimit = true;
            // Calculate necessary axis & factors
            m_swingAxis.set(
                    b2Axis1.cross(
                            b1Axis2.mul(b2Axis1.dot(b1Axis2))
                                    .add(b1Axis3.mul(b2Axis1.dot(b1Axis3)))));
            m_swingAxis.normalize();
            double swingAxisSign = (b2Axis1.dot(b1Axis1) >= 0.0f) ? (double) 1.0f : (double) -1.0f;
            m_swingAxis.mulLocal(swingAxisSign);
        }

        // Twist limits
        if (m_twistSpan >= 0.) {
            btVector3 b2Axis2 =
                    getRigidBodyB()
                            .getCenterOfMassTransform()
                            .getBasis()
                            .mul(this.m_rbBFrame.getBasis().getColumn(1));
            btQuaternion rotationArc = btQuaternion.shortestArcQuat(b2Axis1, b1Axis1);
            btVector3 TwistRef = btQuaternion.quatRotate(rotationArc, b2Axis2);
            double twist = btScalar.btAtan2Fast(TwistRef.dot(b1Axis3), TwistRef.dot(b1Axis2));
            m_twistAngle = twist;

            double lockedFreeFactor = (m_twistSpan > (double) 0.05f) ? (double) 1.0f : 0.;
            if (twist <= -m_twistSpan * lockedFreeFactor) {
                m_twistCorrection = -(twist + m_twistSpan);
                m_solveTwistLimit = true;
                m_twistAxis.set(b2Axis1.add(b1Axis1).mul((double) 0.5f));
                m_twistAxis.normalize();
                m_twistAxis.mulLocal((double) -1.0f);
            } else if (twist > m_twistSpan * lockedFreeFactor) {
                m_twistCorrection = (twist - m_twistSpan);
                m_solveTwistLimit = true;
                m_twistAxis.set(b2Axis1.add(b1Axis1).mul((double) 0.5f));
                m_twistAxis.normalize();
            }
        }
    }

    public void calcAngleInfo2(
            btTransform transA,
            btTransform transB,
            btMatrix3x3 invInertiaWorldA,
            btMatrix3x3 invInertiaWorldB) {
        m_swingCorrection = 0.;
        m_twistLimitSign = 0.;
        m_solveTwistLimit = false;
        m_solveSwingLimit = false;
        // compute rotation of A wrt B (in constraint space)
        if (m_bMotorEnabled && (!m_useSolveConstraintObsolete)) {
            // it is assumed that setMotorTarget() was alredy called
            // and motor target m_qTarget is within constraint limits
            btTransform trPose = new btTransform(m_qTarget);
            btTransform trA = transA.mul(m_rbAFrame);
            btTransform trB = transB.mul(m_rbBFrame);
            btTransform trDeltaAB = trB.mul(trPose).mul(trA.inverse());
            btQuaternion qDeltaAB = trDeltaAB.getRotation();
            btVector3 swingAxis = new btVector3(qDeltaAB.x(), qDeltaAB.y(), qDeltaAB.z());
            float swingAxisLen2 = (float) swingAxis.length2();
            if (btScalar.btFuzzyZero(swingAxisLen2)) {
                return;
            }
            m_swingAxis.set(swingAxis);
            m_swingAxis.normalize();
            m_swingCorrection = qDeltaAB.getAngle();
            if (!btScalar.btFuzzyZero(m_swingCorrection)) {
                m_solveSwingLimit = true;
            }
            return;
        }

        {
            // compute rotation of A wrt B (in constraint space)
            btQuaternion qA = transA.getRotation().mul(m_rbAFrame.getRotation());
            btQuaternion qB = transB.getRotation().mul(m_rbBFrame.getRotation());
            btQuaternion qAB = qB.inverse().mul(qA);
            // split rotation into cone and twist
            // (all this is done from B's perspective. Maybe I should be averaging axes...)
            btVector3 vConeNoTwist = btQuaternion.quatRotate(qAB, vTwist);
            vConeNoTwist.normalize();
            btQuaternion qABCone = btQuaternion.shortestArcQuat(vTwist, vConeNoTwist);
            qABCone.normalize();
            btQuaternion qABTwist = qABCone.inverse().mul(qAB);
            qABTwist.normalize();

            if (m_swingSpan1 >= m_fixThresh && m_swingSpan2 >= m_fixThresh) {
                // C++: swingAngle/swingAxis uninitialised until computeConeLimitInfo writes them
                double[] swingAngle = {0}, swingLimit = {0};
                btVector3 swingAxis = new btVector3();
                computeConeLimitInfo(qABCone, swingAngle, swingAxis, swingLimit);

                if (swingAngle[0] > swingLimit[0] * m_limitSoftness) {
                    m_solveSwingLimit = true;

                    // compute limit ratio: 0->1, where
                    // 0 == beginning of soft limit
                    // 1 == hard/real limit
                    m_swingLimitRatio = (double) 1.f;
                    if (swingAngle[0] < swingLimit[0]
                            && m_limitSoftness < (double) 1.f - btScalar.SIMD_EPSILON) {
                        m_swingLimitRatio =
                                (swingAngle[0] - swingLimit[0] * m_limitSoftness)
                                        / (swingLimit[0] - swingLimit[0] * m_limitSoftness);
                    }

                    // swing correction tries to get back to soft limit
                    m_swingCorrection = swingAngle[0] - (swingLimit[0] * m_limitSoftness);

                    // adjustment of swing axis (based on ellipse normal)
                    adjustSwingAxisToUseEllipseNormal(swingAxis);

                    // Calculate necessary axis & factors
                    m_swingAxis.set(btQuaternion.quatRotate(qB, swingAxis.negate()));

                    m_twistAxisA.setValue(0, 0, 0);

                    m_kSwing =
                            1.
                                    / (computeAngularImpulseDenominator(
                                                    m_swingAxis, invInertiaWorldA)
                                            + computeAngularImpulseDenominator(
                                                    m_swingAxis, invInertiaWorldB));
                }
            } else {
                // you haven't set any limits;
                // or you're trying to set at least one of the swing limits too small.
                // anyway, we have either hinge or fixed joint
                btVector3 ivA = transA.getBasis().mul(m_rbAFrame.getBasis().getColumn(0));
                btVector3 jvA = transA.getBasis().mul(m_rbAFrame.getBasis().getColumn(1));
                btVector3 kvA = transA.getBasis().mul(m_rbAFrame.getBasis().getColumn(2));
                btVector3 ivB = transB.getBasis().mul(m_rbBFrame.getBasis().getColumn(0));
                btVector3 target = new btVector3();
                double x = ivB.dot(ivA);
                double y = ivB.dot(jvA);
                double z = ivB.dot(kvA);
                if ((m_swingSpan1 < m_fixThresh) && (m_swingSpan2 < m_fixThresh)) {
                    // fixed. We'll need to add one more row to constraint
                    if ((!btScalar.btFuzzyZero(y)) || (!(btScalar.btFuzzyZero(z)))) {
                        m_solveSwingLimit = true;
                        m_swingAxis.set(ivB.negate().cross(ivA));
                    }
                } else {
                    if (m_swingSpan1 < m_fixThresh) {
                        // hinge around Y axis
                        if ((!(btScalar.btFuzzyZero(x))) || (!(btScalar.btFuzzyZero(z)))) {
                            m_solveSwingLimit = true;
                            if (m_swingSpan2 >= m_fixThresh) {
                                y = (double) 0.f;
                                double span2 = btScalar.btAtan2(z, x);
                                if (span2 > m_swingSpan2) {
                                    x = btScalar.btCos(m_swingSpan2);
                                    z = btScalar.btSin(m_swingSpan2);
                                } else if (span2 < -m_swingSpan2) {
                                    x = btScalar.btCos(m_swingSpan2);
                                    z = -btScalar.btSin(m_swingSpan2);
                                }
                            }
                        }
                    } else {
                        // hinge around Z axis
                        if ((!(btScalar.btFuzzyZero(x))) || (!(btScalar.btFuzzyZero(y)))) {
                            m_solveSwingLimit = true;
                            if (m_swingSpan1 >= m_fixThresh) {
                                z = (double) 0.f;
                                double span1 = btScalar.btAtan2(y, x);
                                if (span1 > m_swingSpan1) {
                                    x = btScalar.btCos(m_swingSpan1);
                                    y = btScalar.btSin(m_swingSpan1);
                                } else if (span1 < -m_swingSpan1) {
                                    x = btScalar.btCos(m_swingSpan1);
                                    y = -btScalar.btSin(m_swingSpan1);
                                }
                            }
                        }
                    }
                    target.set(0, x * ivA.get(0) + y * jvA.get(0) + z * kvA.get(0));
                    target.set(1, x * ivA.get(1) + y * jvA.get(1) + z * kvA.get(1));
                    target.set(2, x * ivA.get(2) + y * jvA.get(2) + z * kvA.get(2));
                    target.normalize();
                    m_swingAxis.set(ivB.negate().cross(target));
                    m_swingCorrection = m_swingAxis.length();
                    m_swingAxis.normalize();
                }
            }

            if (m_twistSpan >= (double) 0.f) {
                btVector3 twistAxis = new btVector3();
                double[] twistAngle = {0};
                computeTwistLimitInfo(qABTwist, twistAngle, twistAxis);
                m_twistAngle = twistAngle[0];

                if (m_twistAngle > m_twistSpan * m_limitSoftness) {
                    m_solveTwistLimit = true;

                    m_twistLimitRatio = (double) 1.f;
                    if (m_twistAngle < m_twistSpan
                            && m_limitSoftness < (double) 1.f - btScalar.SIMD_EPSILON) {
                        m_twistLimitRatio =
                                (m_twistAngle - m_twistSpan * m_limitSoftness)
                                        / (m_twistSpan - m_twistSpan * m_limitSoftness);
                    }

                    // twist correction tries to get back to soft limit
                    m_twistCorrection = m_twistAngle - (m_twistSpan * m_limitSoftness);

                    m_twistAxis.set(btQuaternion.quatRotate(qB, twistAxis.negate()));

                    m_kTwist =
                            1.
                                    / (computeAngularImpulseDenominator(
                                                    m_twistAxis, invInertiaWorldA)
                                            + computeAngularImpulseDenominator(
                                                    m_twistAxis, invInertiaWorldB));
                }

                if (m_solveSwingLimit)
                    m_twistAxisA.set(btQuaternion.quatRotate(qA, twistAxis.negate()));
            } else {
                m_twistAngle = (double) 0.f;
            }
        }
    }

    /**
     * given a cone rotation in constraint space, (pre: twist must already be removed) this method
     * computes its corresponding swing angle and axis. Out-params: swingAngle[0], vSwingAxis (in
     * place), swingLimit[0] (left untouched when swingAngle <= SIMD_EPSILON, as in C++).
     */
    protected void computeConeLimitInfo(
            btQuaternion qCone, double[] swingAngle, btVector3 vSwingAxis, double[] swingLimit) {
        swingAngle[0] = qCone.getAngle();
        if (swingAngle[0] > btScalar.SIMD_EPSILON) {
            vSwingAxis.set(new btVector3(qCone.x(), qCone.y(), qCone.z()));
            vSwingAxis.normalize();

            // Compute limit for given swing. tricky:
            // Given a swing axis, we're looking for the intersection with the bounding cone
            // ellipse.
            double xEllipse = vSwingAxis.y();
            double yEllipse = -vSwingAxis.z();

            swingLimit[0] = m_swingSpan1; // if xEllipse == 0, pure vSwingAxis.z rotation
            if (Math.abs(xEllipse) > btScalar.SIMD_EPSILON) {
                double surfaceSlope2 = (yEllipse * yEllipse) / (xEllipse * xEllipse);
                double norm = 1 / (m_swingSpan2 * m_swingSpan2);
                norm += surfaceSlope2 / (m_swingSpan1 * m_swingSpan1);
                double swingLimit2 = (1 + surfaceSlope2) / norm;
                swingLimit[0] = Math.sqrt(swingLimit2);
            }
        } else if (swingAngle[0] < 0) {
            // this should never happen!
        }
    }

    public btVector3 GetPointForAngle(double fAngleInRadians, double fLength) {
        // compute x/y in ellipse using cone angle (0 -> 2*PI along surface of cone)
        double xEllipse = btScalar.btCos(fAngleInRadians);
        double yEllipse = btScalar.btSin(fAngleInRadians);

        float swingLimit = (float) m_swingSpan1; // if xEllipse == 0, just use axis b (1)
        if (Math.abs(xEllipse) > btScalar.SIMD_EPSILON) {
            double surfaceSlope2 = (yEllipse * yEllipse) / (xEllipse * xEllipse);
            double norm = 1 / (m_swingSpan2 * m_swingSpan2);
            norm += surfaceSlope2 / (m_swingSpan1 * m_swingSpan1);
            double swingLimit2 = (1 + surfaceSlope2) / norm;
            swingLimit = (float) Math.sqrt(swingLimit2);
        }

        // convert into point in constraint space:
        // note: twist is x-axis, swing 1 and 2 are along the z and y axes respectively
        btVector3 vSwingAxis = new btVector3(0, xEllipse, -yEllipse);
        btQuaternion qSwing = new btQuaternion(vSwingAxis, (double) swingLimit);
        btVector3 vPointInConstraintSpace = new btVector3(fLength, 0, 0);
        return btQuaternion.quatRotate(qSwing, vPointInConstraintSpace);
    }

    /**
     * given a twist rotation in constraint space, (pre: cone must already be removed) this method
     * computes its corresponding angle and axis. Out-params: twistAngle[0], vTwistAxis (in place).
     */
    protected void computeTwistLimitInfo(
            btQuaternion qTwist, double[] twistAngle, btVector3 vTwistAxis) {
        btQuaternion qMinTwist = new btQuaternion(qTwist);
        twistAngle[0] = qTwist.getAngle();

        if (twistAngle[0] > btScalar.SIMD_PI) // long way around. flip quat and recalculate.
        {
            qMinTwist = qTwist.negate();
            twistAngle[0] = qMinTwist.getAngle();
        }
        if (twistAngle[0] < 0) {
            // this should never happen
        }

        vTwistAxis.set(new btVector3(qMinTwist.x(), qMinTwist.y(), qMinTwist.z()));
        if (twistAngle[0] > btScalar.SIMD_EPSILON) vTwistAxis.normalize();
    }

    protected void adjustSwingAxisToUseEllipseNormal(btVector3 vSwingAxis) {
        // convert swing axis to direction from center to surface of ellipse
        // (ie. rotate 2D vector by PI/2)
        double y = -vSwingAxis.z();
        double z = vSwingAxis.y();

        // do the math...
        if (Math.abs(z) > btScalar.SIMD_EPSILON) // avoid division by 0.
        {
            // compute gradient/normal of ellipse surface at current "point"
            double grad = y / z;
            grad *= m_swingSpan2 / m_swingSpan1;

            // adjust y/z to represent normal at point (instead of vector to point)
            if (y > 0) y = Math.abs(grad * z);
            else y = -Math.abs(grad * z);

            // convert ellipse direction back to swing axis
            vSwingAxis.setZ(-y);
            vSwingAxis.setY(z);
            vSwingAxis.normalize();
        }
    }

    public double getSwingSpan1() {
        return m_swingSpan1;
    }

    public double getSwingSpan2() {
        return m_swingSpan2;
    }

    public double getTwistSpan() {
        return m_twistSpan;
    }

    public double getTwistAngle() {
        return m_twistAngle;
    }

    public boolean isPastSwingLimit() {
        return m_solveSwingLimit;
    }

    public void setDamping(double damping) {
        m_damping = damping;
    }

    public void enableMotor(boolean b) {
        m_bMotorEnabled = b;
    }

    public void setMaxMotorImpulse(double maxMotorImpulse) {
        m_maxMotorImpulse = maxMotorImpulse;
        m_bNormalizedMotorStrength = false;
    }

    public void setMaxMotorImpulseNormalized(double maxMotorImpulse) {
        m_maxMotorImpulse = maxMotorImpulse;
        m_bNormalizedMotorStrength = true;
    }

    public double getFixThresh() {
        return m_fixThresh;
    }

    public void setFixThresh(double fixThresh) {
        m_fixThresh = fixThresh;
    }

    public void setMotorTarget(btQuaternion q) {
        btQuaternion qConstraint =
                m_rbBFrame.getRotation().inverse().mul(q).mul(m_rbAFrame.getRotation());
        setMotorTargetInConstraintSpace(qConstraint);
    }

    public void setMotorTargetInConstraintSpace(btQuaternion q) {
        m_qTarget.set(q);

        // clamp motor target to within limits
        {
            double softness = (double) 1.f; // m_limitSoftness;

            // split into twist and cone
            btVector3 vTwisted = btQuaternion.quatRotate(m_qTarget, vTwist);
            btQuaternion qTargetCone = btQuaternion.shortestArcQuat(vTwist, vTwisted);
            qTargetCone.normalize();
            btQuaternion qTargetTwist = qTargetCone.inverse().mul(m_qTarget);
            qTargetTwist.normalize();

            // clamp cone
            if (m_swingSpan1 >= (double) 0.05f && m_swingSpan2 >= (double) 0.05f) {
                double[] swingAngle = {0}, swingLimit = {0};
                btVector3 swingAxis = new btVector3();
                computeConeLimitInfo(qTargetCone, swingAngle, swingAxis, swingLimit);

                if (Math.abs(swingAngle[0]) > btScalar.SIMD_EPSILON) {
                    if (swingAngle[0] > swingLimit[0] * softness)
                        swingAngle[0] = swingLimit[0] * softness;
                    else if (swingAngle[0] < -swingLimit[0] * softness)
                        swingAngle[0] = -swingLimit[0] * softness;
                    qTargetCone = new btQuaternion(swingAxis, swingAngle[0]);
                }
            }

            // clamp twist
            if (m_twistSpan >= (double) 0.05f) {
                double[] twistAngle = {0};
                btVector3 twistAxis = new btVector3();
                computeTwistLimitInfo(qTargetTwist, twistAngle, twistAxis);

                if (Math.abs(twistAngle[0]) > btScalar.SIMD_EPSILON) {
                    // eddy todo: limitSoftness used here???
                    if (twistAngle[0] > m_twistSpan * softness)
                        twistAngle[0] = m_twistSpan * softness;
                    else if (twistAngle[0] < -m_twistSpan * softness)
                        twistAngle[0] = -m_twistSpan * softness;
                    qTargetTwist = new btQuaternion(twistAxis, twistAngle[0]);
                }
            }

            m_qTarget.set(qTargetCone.mul(qTargetTwist));
        }
    }

    /**
     * override the default global value of a parameter (such as ERP or CFM), optionally provide the
     * axis (0..5). If no axis is provided, it uses the default axis for this constraint.
     */
    @Override
    public void setParam(int num, double value, int axis) {
        switch (num) {
            case BT_CONSTRAINT_ERP:
            case BT_CONSTRAINT_STOP_ERP:
                if ((axis >= 0) && (axis < 3)) {
                    m_linERP = value;
                    m_flags |= BT_CONETWIST_FLAGS_LIN_ERP;
                } else {
                    m_biasFactor = value;
                }
                break;
            case BT_CONSTRAINT_CFM:
            case BT_CONSTRAINT_STOP_CFM:
                if ((axis >= 0) && (axis < 3)) {
                    m_linCFM = value;
                    m_flags |= BT_CONETWIST_FLAGS_LIN_CFM;
                } else {
                    m_angCFM = value;
                    m_flags |= BT_CONETWIST_FLAGS_ANG_CFM;
                }
                break;
            default:
                break;
        }
    }

    /** return the local value of parameter */
    @Override
    public double getParam(int num, int axis) {
        double retVal = 0;
        switch (num) {
            case BT_CONSTRAINT_ERP:
            case BT_CONSTRAINT_STOP_ERP:
                if ((axis >= 0) && (axis < 3)) {
                    retVal = m_linERP;
                } else if ((axis >= 3) && (axis < 6)) {
                    retVal = m_biasFactor;
                }
                break;
            case BT_CONSTRAINT_CFM:
            case BT_CONSTRAINT_STOP_CFM:
                if ((axis >= 0) && (axis < 3)) {
                    retVal = m_linCFM;
                } else if ((axis >= 3) && (axis < 6)) {
                    retVal = m_angCFM;
                }
                break;
            default:
        }
        return retVal;
    }

    public void setFrames(btTransform frameA, btTransform frameB) {
        m_rbAFrame.set(frameA);
        m_rbBFrame.set(frameB);
        buildJacobian();
    }

    public btTransform getFrameOffsetA() {
        return m_rbAFrame;
    }

    public btTransform getFrameOffsetB() {
        return m_rbBFrame;
    }

    @Override
    public int calculateSerializeBufferSize() {
        return SIZEOF_btConeTwistConstraintDoubleData;
    }
}
