// Port of BulletDynamics/ConstraintSolver/btHingeConstraint.cpp (Bullet 2.82),
// _BT_USE_CENTER_LIMIT_ defined
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btQuaternion;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * hinge constraint between two rigidbodies each with a pivotpoint that descibes the axis location
 * in local space; axis defines the orientation of the hinge axis
 */
public class btHingeConstraint extends btTypedConstraint {
    // enum btHingeFlags
    public static final int BT_HINGE_FLAGS_CFM_STOP = 1;
    public static final int BT_HINGE_FLAGS_ERP_STOP = 2;
    public static final int BT_HINGE_FLAGS_CFM_NORM = 4;

    /** #define HINGE_USE_OBSOLETE_SOLVER false */
    public static final boolean HINGE_USE_OBSOLETE_SOLVER = false;

    /** #define HINGE_USE_FRAME_OFFSET true */
    public static final boolean HINGE_USE_FRAME_OFFSET = true;

    /** sizeof(btHingeConstraintDoubleData2) */
    public static final int SIZEOF_btHingeConstraintDoubleData2 = 336;

    /** file static {@code static btVector3 vHinge(0, 0, btScalar(1));} */
    private static final btVector3 vHinge = new btVector3(0, 0, 1.);

    public final btJacobianEntry[] m_jac = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    }; // 3 orthogonal linear constraints
    public final btJacobianEntry[] m_jacAng = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    }; // 2 orthogonal angular constraints+ 1 for limit/motor

    public final btTransform m_rbAFrame =
            new btTransform(); // constraint axii. Assumes z is hinge axis.
    public final btTransform m_rbBFrame = new btTransform();

    public double m_motorTargetVelocity;
    public double m_maxMotorImpulse;

    public final btAngularLimit m_limit = new btAngularLimit();

    public double m_kHinge;

    public double m_accLimitImpulse;
    public double m_hingeAngle;
    public double m_referenceSign;

    public boolean m_angularOnly;
    public boolean m_enableAngularMotor;
    public boolean m_useSolveConstraintObsolete;
    public boolean m_useOffsetForConstraintFrame;
    public boolean m_useReferenceFrameA;

    public double m_accMotorImpulse;

    public int m_flags;
    public double m_normalCFM;
    public double m_stopCFM;
    public double m_stopERP;

    public btHingeConstraint(
            btRigidBody rbA,
            btRigidBody rbB,
            btVector3 pivotInA,
            btVector3 pivotInB,
            btVector3 axisInA,
            btVector3 axisInB,
            boolean useReferenceFrameA) {
        super(HINGE_CONSTRAINT_TYPE, rbA, rbB);
        m_angularOnly = false;
        m_enableAngularMotor = false;
        m_useSolveConstraintObsolete = HINGE_USE_OBSOLETE_SOLVER;
        m_useOffsetForConstraintFrame = HINGE_USE_FRAME_OFFSET;
        m_useReferenceFrameA = useReferenceFrameA;
        m_flags = 0;

        m_rbAFrame.getOrigin().set(pivotInA);

        // since no frame is given, assume this to be zero angle and just pick rb transform axis
        btVector3 rbAxisA1 = rbA.getCenterOfMassTransform().getBasis().getColumn(0);

        btVector3 rbAxisA2;
        double projection = axisInA.dot(rbAxisA1);
        if (projection >= 1.0f - btScalar.SIMD_EPSILON) {
            rbAxisA1 = rbA.getCenterOfMassTransform().getBasis().getColumn(2).negate();
            rbAxisA2 = rbA.getCenterOfMassTransform().getBasis().getColumn(1);
        } else if (projection <= -1.0f + btScalar.SIMD_EPSILON) {
            rbAxisA1 = rbA.getCenterOfMassTransform().getBasis().getColumn(2);
            rbAxisA2 = rbA.getCenterOfMassTransform().getBasis().getColumn(1);
        } else {
            rbAxisA2 = axisInA.cross(rbAxisA1);
            rbAxisA1 = rbAxisA2.cross(axisInA);
        }

        m_rbAFrame
                .getBasis()
                .setValue(
                        rbAxisA1.getX(),
                        rbAxisA2.getX(),
                        axisInA.getX(),
                        rbAxisA1.getY(),
                        rbAxisA2.getY(),
                        axisInA.getY(),
                        rbAxisA1.getZ(),
                        rbAxisA2.getZ(),
                        axisInA.getZ());

        btQuaternion rotationArc = btQuaternion.shortestArcQuat(axisInA, axisInB);
        btVector3 rbAxisB1 = btQuaternion.quatRotate(rotationArc, rbAxisA1);
        btVector3 rbAxisB2 = axisInB.cross(rbAxisB1);

        m_rbBFrame.getOrigin().set(pivotInB);
        m_rbBFrame
                .getBasis()
                .setValue(
                        rbAxisB1.getX(),
                        rbAxisB2.getX(),
                        axisInB.getX(),
                        rbAxisB1.getY(),
                        rbAxisB2.getY(),
                        axisInB.getY(),
                        rbAxisB1.getZ(),
                        rbAxisB2.getZ(),
                        axisInB.getZ());

        m_referenceSign = m_useReferenceFrameA ? (double) -1.f : (double) 1.f;
    }

    public btHingeConstraint(
            btRigidBody rbA,
            btRigidBody rbB,
            btVector3 pivotInA,
            btVector3 pivotInB,
            btVector3 axisInA,
            btVector3 axisInB) {
        this(rbA, rbB, pivotInA, pivotInB, axisInA, axisInB, false);
    }

    public btHingeConstraint(
            btRigidBody rbA, btVector3 pivotInA, btVector3 axisInA, boolean useReferenceFrameA) {
        super(HINGE_CONSTRAINT_TYPE, rbA);
        m_angularOnly = false;
        m_enableAngularMotor = false;
        m_useSolveConstraintObsolete = HINGE_USE_OBSOLETE_SOLVER;
        m_useOffsetForConstraintFrame = HINGE_USE_FRAME_OFFSET;
        m_useReferenceFrameA = useReferenceFrameA;
        m_flags = 0;

        // since no frame is given, assume this to be zero angle and just pick rb transform axis
        // fixed axis in worldspace
        btVector3 rbAxisA1 = new btVector3(), rbAxisA2 = new btVector3();
        btVector3.btPlaneSpace1(axisInA, rbAxisA1, rbAxisA2);

        m_rbAFrame.getOrigin().set(pivotInA);
        m_rbAFrame
                .getBasis()
                .setValue(
                        rbAxisA1.getX(),
                        rbAxisA2.getX(),
                        axisInA.getX(),
                        rbAxisA1.getY(),
                        rbAxisA2.getY(),
                        axisInA.getY(),
                        rbAxisA1.getZ(),
                        rbAxisA2.getZ(),
                        axisInA.getZ());

        btVector3 axisInB = rbA.getCenterOfMassTransform().getBasis().mul(axisInA);

        btQuaternion rotationArc = btQuaternion.shortestArcQuat(axisInA, axisInB);
        btVector3 rbAxisB1 = btQuaternion.quatRotate(rotationArc, rbAxisA1);
        btVector3 rbAxisB2 = axisInB.cross(rbAxisB1);

        m_rbBFrame.getOrigin().set(rbA.getCenterOfMassTransform().transform(pivotInA));
        m_rbBFrame
                .getBasis()
                .setValue(
                        rbAxisB1.getX(),
                        rbAxisB2.getX(),
                        axisInB.getX(),
                        rbAxisB1.getY(),
                        rbAxisB2.getY(),
                        axisInB.getY(),
                        rbAxisB1.getZ(),
                        rbAxisB2.getZ(),
                        axisInB.getZ());

        m_referenceSign = m_useReferenceFrameA ? (double) -1.f : (double) 1.f;
    }

    public btHingeConstraint(btRigidBody rbA, btVector3 pivotInA, btVector3 axisInA) {
        this(rbA, pivotInA, axisInA, false);
    }

    public btHingeConstraint(
            btRigidBody rbA,
            btRigidBody rbB,
            btTransform rbAFrame,
            btTransform rbBFrame,
            boolean useReferenceFrameA) {
        super(HINGE_CONSTRAINT_TYPE, rbA, rbB);
        m_rbAFrame.set(rbAFrame);
        m_rbBFrame.set(rbBFrame);
        m_angularOnly = false;
        m_enableAngularMotor = false;
        m_useSolveConstraintObsolete = HINGE_USE_OBSOLETE_SOLVER;
        m_useOffsetForConstraintFrame = HINGE_USE_FRAME_OFFSET;
        m_useReferenceFrameA = useReferenceFrameA;
        m_flags = 0;
        m_referenceSign = m_useReferenceFrameA ? (double) -1.f : (double) 1.f;
    }

    public btHingeConstraint(
            btRigidBody rbA, btRigidBody rbB, btTransform rbAFrame, btTransform rbBFrame) {
        this(rbA, rbB, rbAFrame, rbBFrame, false);
    }

    public btHingeConstraint(btRigidBody rbA, btTransform rbAFrame, boolean useReferenceFrameA) {
        super(HINGE_CONSTRAINT_TYPE, rbA);
        m_rbAFrame.set(rbAFrame);
        m_rbBFrame.set(rbAFrame);
        m_angularOnly = false;
        m_enableAngularMotor = false;
        m_useSolveConstraintObsolete = HINGE_USE_OBSOLETE_SOLVER;
        m_useOffsetForConstraintFrame = HINGE_USE_FRAME_OFFSET;
        m_useReferenceFrameA = useReferenceFrameA;
        m_flags = 0;
        /// not providing rigidbody B means implicitly using worldspace for body B

        m_rbBFrame
                .getOrigin()
                .set(m_rbA.getCenterOfMassTransform().transform(m_rbAFrame.getOrigin()));
        m_referenceSign = m_useReferenceFrameA ? (double) -1.f : (double) 1.f;
    }

    public btHingeConstraint(btRigidBody rbA, btTransform rbAFrame) {
        this(rbA, rbAFrame, false);
    }

    @Override
    public void buildJacobian() {
        if (m_useSolveConstraintObsolete) {
            m_appliedImpulse = 0.;
            m_accMotorImpulse = 0.;

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

            // calculate two perpendicular jointAxis, orthogonal to hingeAxis
            // these two jointAxis require equal angular velocities for both bodies

            // this is unused for now, it's a todo
            btVector3 jointAxis0local = new btVector3();
            btVector3 jointAxis1local = new btVector3();

            btVector3.btPlaneSpace1(
                    m_rbAFrame.getBasis().getColumn(2), jointAxis0local, jointAxis1local);

            btVector3 jointAxis0 =
                    getRigidBodyA().getCenterOfMassTransform().getBasis().mul(jointAxis0local);
            btVector3 jointAxis1 =
                    getRigidBodyA().getCenterOfMassTransform().getBasis().mul(jointAxis1local);
            btVector3 hingeAxisWorld =
                    getRigidBodyA()
                            .getCenterOfMassTransform()
                            .getBasis()
                            .mul(m_rbAFrame.getBasis().getColumn(2));

            m_jacAng[0].set(
                    new btJacobianEntry(
                            jointAxis0,
                            m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbA.getInvInertiaDiagLocal(),
                            m_rbB.getInvInertiaDiagLocal()));

            m_jacAng[1].set(
                    new btJacobianEntry(
                            jointAxis1,
                            m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbA.getInvInertiaDiagLocal(),
                            m_rbB.getInvInertiaDiagLocal()));

            m_jacAng[2].set(
                    new btJacobianEntry(
                            hingeAxisWorld,
                            m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                            m_rbA.getInvInertiaDiagLocal(),
                            m_rbB.getInvInertiaDiagLocal()));

            // clear accumulator
            m_accLimitImpulse = 0.;

            // test angular limit
            testLimit(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());

            // Compute K = J*W*J' for hinge axis
            btVector3 axisA =
                    getRigidBodyA()
                            .getCenterOfMassTransform()
                            .getBasis()
                            .mul(m_rbAFrame.getBasis().getColumn(2));
            m_kHinge =
                    1.0f
                            / (getRigidBodyA().computeAngularImpulseDenominator(axisA)
                                    + getRigidBodyB().computeAngularImpulseDenominator(axisA));
        }
    }

    @Override
    public void getInfo1(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            info.m_numConstraintRows = 5; // Fixed 3 linear + 2 angular
            info.nub = 1;
            // always add the row, to avoid computation (data is not available yet)
            // prepare constraint
            testLimit(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
            if (getSolveLimit() != 0 || getEnableAngularMotor()) {
                info.m_numConstraintRows++; // limit 3rd anguar as well
                info.nub--;
            }
        }
    }

    public void getInfo1NonVirtual(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            // always add the 'limit' row, to avoid computation (data is not available yet)
            info.m_numConstraintRows = 6; // Fixed 3 linear + 2 angular
            info.nub = 0;
        }
    }

    @Override
    public void getInfo2(btConstraintInfo2 info) {
        if (m_useOffsetForConstraintFrame) {
            getInfo2InternalUsingFrameOffset(
                    info,
                    m_rbA.getCenterOfMassTransform(),
                    m_rbB.getCenterOfMassTransform(),
                    m_rbA.getAngularVelocity(),
                    m_rbB.getAngularVelocity());
        } else {
            getInfo2Internal(
                    info,
                    m_rbA.getCenterOfMassTransform(),
                    m_rbB.getCenterOfMassTransform(),
                    m_rbA.getAngularVelocity(),
                    m_rbB.getAngularVelocity());
        }
    }

    public void getInfo2NonVirtual(
            btConstraintInfo2 info,
            btTransform transA,
            btTransform transB,
            btVector3 angVelA,
            btVector3 angVelB) {
        /// the regular (virtual) implementation getInfo2 already performs 'testLimit' during
        // getInfo1, so we need to do it now
        testLimit(transA, transB);

        getInfo2Internal(info, transA, transB, angVelA, angVelB);
    }

    public void getInfo2Internal(
            btConstraintInfo2 info,
            btTransform transA,
            btTransform transB,
            btVector3 angVelA,
            btVector3 angVelB) {
        int i, skip = info.rowskip;
        // transforms in world space
        btTransform trA = transA.mul(m_rbAFrame);
        btTransform trB = transB.mul(m_rbBFrame);
        // pivot point
        btVector3 pivotAInW = new btVector3(trA.getOrigin());
        btVector3 pivotBInW = new btVector3(trB.getOrigin());

        // linear (all fixed)

        if (!m_angularOnly) {
            info.m_J1linearAxis.set(0, 1);
            info.m_J1linearAxis.set(skip + 1, 1);
            info.m_J1linearAxis.set(2 * skip + 2, 1);

            info.m_J2linearAxis.set(0, -1);
            info.m_J2linearAxis.set(skip + 1, -1);
            info.m_J2linearAxis.set(2 * skip + 2, -1);
        }

        btVector3 a1 = pivotAInW.sub(transA.getOrigin());
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
        btVector3 a2 = pivotBInW.sub(transB.getOrigin());
        {
            btVector3 angular0 = new btVector3();
            btVector3 angular1 = new btVector3();
            btVector3 angular2 = new btVector3();
            a2.getSkewSymmetricMatrix(angular0, angular1, angular2);
            info.m_J2angularAxis.setVec(0, angular0);
            info.m_J2angularAxis.setVec(skip, angular1);
            info.m_J2angularAxis.setVec(2 * skip, angular2);
        }
        // linear RHS
        double k = info.fps * info.erp;
        if (!m_angularOnly) {
            for (i = 0; i < 3; i++) {
                info.m_constraintError.set(i * skip, k * (pivotBInW.get(i) - pivotAInW.get(i)));
            }
        }
        // make rotations around X and Y equal
        // the hinge axis should be the only unconstrained
        // rotational axis, the angular velocity of the two bodies perpendicular to
        // the hinge axis should be equal. thus the constraint equations are
        //    p*w1 - p*w2 = 0
        //    q*w1 - q*w2 = 0
        // where p and q are unit vectors normal to the hinge axis, and w1 and w2
        // are the angular velocity vectors of the two bodies.
        // get hinge axis (Z)
        btVector3 ax1 = trA.getBasis().getColumn(2);
        // get 2 orthos to hinge axis (X, Y)
        btVector3 p = trA.getBasis().getColumn(0);
        btVector3 q = trA.getBasis().getColumn(1);
        // set the two hinge angular rows
        int s3 = 3 * info.rowskip;
        int s4 = 4 * info.rowskip;

        info.m_J1angularAxis.set(s3 + 0, p.get(0));
        info.m_J1angularAxis.set(s3 + 1, p.get(1));
        info.m_J1angularAxis.set(s3 + 2, p.get(2));
        info.m_J1angularAxis.set(s4 + 0, q.get(0));
        info.m_J1angularAxis.set(s4 + 1, q.get(1));
        info.m_J1angularAxis.set(s4 + 2, q.get(2));

        info.m_J2angularAxis.set(s3 + 0, -p.get(0));
        info.m_J2angularAxis.set(s3 + 1, -p.get(1));
        info.m_J2angularAxis.set(s3 + 2, -p.get(2));
        info.m_J2angularAxis.set(s4 + 0, -q.get(0));
        info.m_J2angularAxis.set(s4 + 1, -q.get(1));
        info.m_J2angularAxis.set(s4 + 2, -q.get(2));
        // compute the right hand side of the constraint equation. set relative
        // body velocities along p and q to bring the hinge back into alignment.
        // if ax1,ax2 are the unit length hinge axes as computed from body1 and
        // body2, we need to rotate both bodies along the axis u = (ax1 x ax2).
        // if `theta' is the angle between ax1 and ax2, we need an angular velocity
        // along u to cover angle erp*theta in one step :
        //   |angular_velocity| = angle/time = erp*theta / stepsize
        //                      = (erp*fps) * theta
        //    angular_velocity  = |angular_velocity| * (ax1 x ax2) / |ax1 x ax2|
        //                      = (erp*fps) * theta * (ax1 x ax2) / sin(theta)
        // ...as ax1 and ax2 are unit length. if theta is smallish,
        // theta ~= sin(theta), so
        //    angular_velocity  = (erp*fps) * (ax1 x ax2)
        // ax1 x ax2 is in the plane space of ax1, so we project the angular
        // velocity to p and q to find the right hand side.
        btVector3 ax2 = trB.getBasis().getColumn(2);
        btVector3 u = ax1.cross(ax2);
        info.m_constraintError.set(s3, k * u.dot(p));
        info.m_constraintError.set(s4, k * u.dot(q));

        limitMotorRows(info, ax1, angVelA, angVelB);
    }

    /**
     * The limit/motor tail shared verbatim by getInfo2Internal and getInfo2InternalUsingFrameOffset
     * ({@code nrow = 4; ... } up to the end of the function). {@code k} is re-assigned there before
     * use, so it is local here.
     */
    private void limitMotorRows(
            btConstraintInfo2 info, btVector3 ax1, btVector3 angVelA, btVector3 angVelB) {
        double k;
        // check angular limits
        int nrow = 4; // last filled row
        int srow;
        double limit_err = 0.0;
        int limit = 0;
        if (getSolveLimit() != 0) {
            limit_err = m_limit.getCorrection() * m_referenceSign;
            limit = (limit_err > 0.0) ? 1 : 2;
        }
        // if the hinge has joint limits or motor, add in the extra row
        int powered = 0;
        if (getEnableAngularMotor()) {
            powered = 1;
        }
        if (limit != 0 || powered != 0) {
            nrow++;
            srow = nrow * info.rowskip;
            info.m_J1angularAxis.set(srow + 0, ax1.get(0));
            info.m_J1angularAxis.set(srow + 1, ax1.get(1));
            info.m_J1angularAxis.set(srow + 2, ax1.get(2));

            info.m_J2angularAxis.set(srow + 0, -ax1.get(0));
            info.m_J2angularAxis.set(srow + 1, -ax1.get(1));
            info.m_J2angularAxis.set(srow + 2, -ax1.get(2));

            double lostop = getLowerLimit();
            double histop = getUpperLimit();
            if (limit != 0 && (lostop == histop)) { // the joint motor is ineffective
                powered = 0;
            }
            info.m_constraintError.set(srow, (double) 0.0f);
            double currERP = (m_flags & BT_HINGE_FLAGS_ERP_STOP) != 0 ? m_stopERP : info.erp;
            if (powered != 0) {
                if ((m_flags & BT_HINGE_FLAGS_CFM_NORM) != 0) {
                    info.cfm.set(srow, m_normalCFM);
                }
                double mot_fact =
                        getMotorFactor(
                                m_hingeAngle,
                                lostop,
                                histop,
                                m_motorTargetVelocity,
                                info.fps * currERP);
                info.m_constraintError.set(
                        srow,
                        info.m_constraintError.get(srow)
                                + mot_fact * m_motorTargetVelocity * m_referenceSign);
                info.m_lowerLimit.set(srow, -m_maxMotorImpulse);
                info.m_upperLimit.set(srow, m_maxMotorImpulse);
            }
            if (limit != 0) {
                k = info.fps * currERP;
                info.m_constraintError.set(srow, info.m_constraintError.get(srow) + k * limit_err);
                if ((m_flags & BT_HINGE_FLAGS_CFM_STOP) != 0) {
                    info.cfm.set(srow, m_stopCFM);
                }
                if (lostop == histop) {
                    // limited low and high simultaneously
                    info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                    info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                } else if (limit == 1) { // low limit
                    info.m_lowerLimit.set(srow, 0);
                    info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                } else { // high limit
                    info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                    info.m_upperLimit.set(srow, 0);
                }
                // bounce (we'll use slider parameter abs(1.0 - m_dampingLimOrtho) for that)
                double bounce = m_limit.getRelaxationFactor();
                if (bounce > 0.0) {
                    double vel = angVelA.dot(ax1);
                    vel -= angVelB.dot(ax1);
                    // only apply bounce if the velocity is incoming, and if the
                    // resulting c[] exceeds what we already have.
                    if (limit == 1) { // low limit
                        if (vel < 0) {
                            double newc = -bounce * vel;
                            if (newc > info.m_constraintError.get(srow)) {
                                info.m_constraintError.set(srow, newc);
                            }
                        }
                    } else { // high limit - all those computations are reversed
                        if (vel > 0) {
                            double newc = -bounce * vel;
                            if (newc < info.m_constraintError.get(srow)) {
                                info.m_constraintError.set(srow, newc);
                            }
                        }
                    }
                }
                info.m_constraintError.set(
                        srow, info.m_constraintError.get(srow) * m_limit.getBiasFactor());
            } // if(limit)
        } // if angular limit or powered
    }

    public void getInfo2InternalUsingFrameOffset(
            btConstraintInfo2 info,
            btTransform transA,
            btTransform transB,
            btVector3 angVelA,
            btVector3 angVelB) {
        int i, s = info.rowskip;
        // transforms in world space
        btTransform trA = transA.mul(m_rbAFrame);
        btTransform trB = transB.mul(m_rbBFrame);
        // pivot point
        // difference between frames in WCS
        btVector3 ofs = trB.getOrigin().sub(trA.getOrigin());
        // now get weight factors depending on masses
        double miA = getRigidBodyA().getInvMass();
        double miB = getRigidBodyB().getInvMass();
        boolean hasStaticBody = (miA < btScalar.SIMD_EPSILON) || (miB < btScalar.SIMD_EPSILON);
        double miS = miA + miB;
        double factA, factB;
        if (miS > (double) 0.f) {
            factA = miB / miS;
        } else {
            factA = (double) 0.5f;
        }
        factB = (double) 1.0f - factA;
        // get the desired direction of hinge axis
        // as weighted sum of Z-orthos of frameA and frameB in WCS
        btVector3 ax1A = trA.getBasis().getColumn(2);
        btVector3 ax1B = trB.getBasis().getColumn(2);
        btVector3 ax1 = ax1A.mul(factA).add(ax1B.mul(factB));
        ax1.normalize();
        // fill first 3 rows
        // we want: velA + wA x relA == velB + wB x relB
        btTransform bodyA_trans = new btTransform(transA);
        btTransform bodyB_trans = new btTransform(transB);
        int s0 = 0;
        int s1 = s;
        int s2 = s * 2;
        int nrow = 2; // last filled row
        btVector3 tmpA, tmpB, relA, relB, p, q;
        // get vector from bodyB to frameB in WCS
        relB = trB.getOrigin().sub(bodyB_trans.getOrigin());
        // get its projection to hinge axis
        btVector3 projB = ax1.mul(relB.dot(ax1));
        // get vector directed from bodyB to hinge axis (and orthogonal to it)
        btVector3 orthoB = relB.sub(projB);
        // same for bodyA
        relA = trA.getOrigin().sub(bodyA_trans.getOrigin());
        btVector3 projA = ax1.mul(relA.dot(ax1));
        btVector3 orthoA = relA.sub(projA);
        btVector3 totalDist = projA.sub(projB);
        // get offset vectors relA and relB
        relA = orthoA.add(totalDist.mul(factA));
        relB = orthoB.sub(totalDist.mul(factB));
        // now choose average ortho to hinge axis
        p = orthoB.mul(factA).add(orthoA.mul(factB));
        double len2 = p.length2();
        if (len2 > btScalar.SIMD_EPSILON) {
            p.divLocal(btScalar.btSqrt(len2));
        } else {
            p = trA.getBasis().getColumn(1);
        }
        // make one more ortho
        q = ax1.cross(p);
        // fill three rows
        tmpA = relA.cross(p);
        tmpB = relB.cross(p);
        for (i = 0; i < 3; i++) info.m_J1angularAxis.set(s0 + i, tmpA.get(i));
        for (i = 0; i < 3; i++) info.m_J2angularAxis.set(s0 + i, -tmpB.get(i));
        tmpA = relA.cross(q);
        tmpB = relB.cross(q);
        if (hasStaticBody && getSolveLimit() != 0) {
            // to make constraint between static and dynamic objects more rigid
            // remove wA (or wB) from equation if angular limit is hit
            tmpB.mulLocal(factB);
            tmpA.mulLocal(factA);
        }
        for (i = 0; i < 3; i++) info.m_J1angularAxis.set(s1 + i, tmpA.get(i));
        for (i = 0; i < 3; i++) info.m_J2angularAxis.set(s1 + i, -tmpB.get(i));
        tmpA = relA.cross(ax1);
        tmpB = relB.cross(ax1);
        if (hasStaticBody) {
            // to make constraint between static and dynamic objects more rigid
            // remove wA (or wB) from equation
            tmpB.mulLocal(factB);
            tmpA.mulLocal(factA);
        }
        for (i = 0; i < 3; i++) info.m_J1angularAxis.set(s2 + i, tmpA.get(i));
        for (i = 0; i < 3; i++) info.m_J2angularAxis.set(s2 + i, -tmpB.get(i));

        double k = info.fps * info.erp;

        if (!m_angularOnly) {
            for (i = 0; i < 3; i++) info.m_J1linearAxis.set(s0 + i, p.get(i));
            for (i = 0; i < 3; i++) info.m_J1linearAxis.set(s1 + i, q.get(i));
            for (i = 0; i < 3; i++) info.m_J1linearAxis.set(s2 + i, ax1.get(i));

            for (i = 0; i < 3; i++) info.m_J2linearAxis.set(s0 + i, -p.get(i));
            for (i = 0; i < 3; i++) info.m_J2linearAxis.set(s1 + i, -q.get(i));
            for (i = 0; i < 3; i++) info.m_J2linearAxis.set(s2 + i, -ax1.get(i));

            // compute three elements of right hand side

            double rhs = k * p.dot(ofs);
            info.m_constraintError.set(s0, rhs);
            rhs = k * q.dot(ofs);
            info.m_constraintError.set(s1, rhs);
            rhs = k * ax1.dot(ofs);
            info.m_constraintError.set(s2, rhs);
        }
        // the hinge axis should be the only unconstrained
        // rotational axis, the angular velocity of the two bodies perpendicular to
        // the hinge axis should be equal. thus the constraint equations are
        //    p*w1 - p*w2 = 0
        //    q*w1 - q*w2 = 0
        // where p and q are unit vectors normal to the hinge axis, and w1 and w2
        // are the angular velocity vectors of the two bodies.
        int s3 = 3 * s;
        int s4 = 4 * s;
        info.m_J1angularAxis.set(s3 + 0, p.get(0));
        info.m_J1angularAxis.set(s3 + 1, p.get(1));
        info.m_J1angularAxis.set(s3 + 2, p.get(2));
        info.m_J1angularAxis.set(s4 + 0, q.get(0));
        info.m_J1angularAxis.set(s4 + 1, q.get(1));
        info.m_J1angularAxis.set(s4 + 2, q.get(2));

        info.m_J2angularAxis.set(s3 + 0, -p.get(0));
        info.m_J2angularAxis.set(s3 + 1, -p.get(1));
        info.m_J2angularAxis.set(s3 + 2, -p.get(2));
        info.m_J2angularAxis.set(s4 + 0, -q.get(0));
        info.m_J2angularAxis.set(s4 + 1, -q.get(1));
        info.m_J2angularAxis.set(s4 + 2, -q.get(2));
        // compute the right hand side of the constraint equation. set relative
        // body velocities along p and q to bring the hinge back into alignment.
        // if ax1A,ax1B are the unit length hinge axes as computed from bodyA and
        // bodyB, we need to rotate both bodies along the axis u = (ax1 x ax2).
        // if "theta" is the angle between ax1 and ax2, we need an angular velocity
        // along u to cover angle erp*theta in one step :
        //   |angular_velocity| = angle/time = erp*theta / stepsize
        //                      = (erp*fps) * theta
        //    angular_velocity  = |angular_velocity| * (ax1 x ax2) / |ax1 x ax2|
        //                      = (erp*fps) * theta * (ax1 x ax2) / sin(theta)
        // ...as ax1 and ax2 are unit length. if theta is smallish,
        // theta ~= sin(theta), so
        //    angular_velocity  = (erp*fps) * (ax1 x ax2)
        // ax1 x ax2 is in the plane space of ax1, so we project the angular
        // velocity to p and q to find the right hand side.
        k = info.fps * info.erp;
        btVector3 u = ax1A.cross(ax1B);
        info.m_constraintError.set(s3, k * u.dot(p));
        info.m_constraintError.set(s4, k * u.dot(q));

        limitMotorRows(info, ax1, angVelA, angVelB);
    }

    public void updateRHS(double timeStep) {}

    public btTransform getFrameOffsetA() {
        return m_rbAFrame;
    }

    public btTransform getFrameOffsetB() {
        return m_rbBFrame;
    }

    public void setFrames(btTransform frameA, btTransform frameB) {
        m_rbAFrame.set(frameA);
        m_rbBFrame.set(frameB);
        buildJacobian();
    }

    public void setAngularOnly(boolean angularOnly) {
        m_angularOnly = angularOnly;
    }

    public void enableAngularMotor(
            boolean enableMotor, double targetVelocity, double maxMotorImpulse) {
        m_enableAngularMotor = enableMotor;
        m_motorTargetVelocity = targetVelocity;
        m_maxMotorImpulse = maxMotorImpulse;
    }

    // extra motor API, including ability to set a target rotation (as opposed to angular velocity)
    // note: setMotorTarget sets angular velocity under the hood, so you must call it every tick to
    //       maintain a given angular target.
    public void enableMotor(boolean enableMotor) {
        m_enableAngularMotor = enableMotor;
    }

    public void setMaxMotorImpulse(double maxMotorImpulse) {
        m_maxMotorImpulse = maxMotorImpulse;
    }

    /** qAinB is rotation of body A wrt body B. */
    public void setMotorTarget(btQuaternion qAinB, double dt) {
        // convert target from body to constraint space
        btQuaternion qConstraint =
                m_rbBFrame.getRotation().inverse().mul(qAinB).mul(m_rbAFrame.getRotation());
        qConstraint.normalize();

        // extract "pure" hinge component
        btVector3 vNoHinge = btQuaternion.quatRotate(qConstraint, vHinge);
        vNoHinge.normalize();
        btQuaternion qNoHinge = btQuaternion.shortestArcQuat(vHinge, vNoHinge);
        btQuaternion qHinge = qNoHinge.inverse().mul(qConstraint);
        qHinge.normalize();

        // compute angular target, clamped to limits
        double targetAngle = qHinge.getAngle();
        if (targetAngle > btScalar.SIMD_PI) // long way around. flip quat and recalculate.
        {
            qHinge = qHinge.negate();
            targetAngle = qHinge.getAngle();
        }
        if (qHinge.getZ() < 0) targetAngle = -targetAngle;

        setMotorTarget(targetAngle, dt);
    }

    public void setMotorTarget(double targetAngle, double dt) {
        double[] ta = {targetAngle};
        m_limit.fit(ta);
        targetAngle = ta[0];
        // compute angular velocity
        double curAngle =
                getHingeAngle(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
        double dAngle = targetAngle - curAngle;
        m_motorTargetVelocity = dAngle / dt;
    }

    public void setLimit(
            double low,
            double high,
            double _softness,
            double _biasFactor,
            double _relaxationFactor) {
        m_limit.set(low, high, _softness, _biasFactor, _relaxationFactor);
    }

    public void setLimit(double low, double high, double _softness, double _biasFactor) {
        setLimit(low, high, _softness, _biasFactor, (double) 1.0f);
    }

    public void setLimit(double low, double high, double _softness) {
        setLimit(low, high, _softness, (double) 0.3f, (double) 1.0f);
    }

    public void setLimit(double low, double high) {
        setLimit(low, high, (double) 0.9f, (double) 0.3f, (double) 1.0f);
    }

    public void setAxis(btVector3 axisInA) {
        btVector3 rbAxisA1 = new btVector3(), rbAxisA2 = new btVector3();
        btVector3.btPlaneSpace1(axisInA, rbAxisA1, rbAxisA2);
        btVector3 pivotInA = new btVector3(m_rbAFrame.getOrigin());
        //		m_rbAFrame.getOrigin() = pivotInA;
        m_rbAFrame
                .getBasis()
                .setValue(
                        rbAxisA1.getX(),
                        rbAxisA2.getX(),
                        axisInA.getX(),
                        rbAxisA1.getY(),
                        rbAxisA2.getY(),
                        axisInA.getY(),
                        rbAxisA1.getZ(),
                        rbAxisA2.getZ(),
                        axisInA.getZ());

        btVector3 axisInB = m_rbA.getCenterOfMassTransform().getBasis().mul(axisInA);

        btQuaternion rotationArc = btQuaternion.shortestArcQuat(axisInA, axisInB);
        btVector3 rbAxisB1 = btQuaternion.quatRotate(rotationArc, rbAxisA1);
        btVector3 rbAxisB2 = axisInB.cross(rbAxisB1);

        m_rbBFrame
                .getOrigin()
                .set(
                        m_rbB.getCenterOfMassTransform()
                                .inverse()
                                .transform(m_rbA.getCenterOfMassTransform().transform(pivotInA)));

        m_rbBFrame
                .getBasis()
                .setValue(
                        rbAxisB1.getX(),
                        rbAxisB2.getX(),
                        axisInB.getX(),
                        rbAxisB1.getY(),
                        rbAxisB2.getY(),
                        axisInB.getY(),
                        rbAxisB1.getZ(),
                        rbAxisB2.getZ(),
                        axisInB.getZ());
        m_rbBFrame
                .getBasis()
                .set(
                        m_rbB.getCenterOfMassTransform()
                                .getBasis()
                                .inverse()
                                .mul(m_rbBFrame.getBasis()));
    }

    public double getLowerLimit() {
        return m_limit.getLow();
    }

    public double getUpperLimit() {
        return m_limit.getHigh();
    }

    /** The getHingeAngle gives the hinge angle in range [-PI,PI] */
    public double getHingeAngle() {
        return getHingeAngle(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
    }

    public double getHingeAngle(btTransform transA, btTransform transB) {
        final btVector3 refAxis0 = transA.getBasis().mul(m_rbAFrame.getBasis().getColumn(0));
        final btVector3 refAxis1 = transA.getBasis().mul(m_rbAFrame.getBasis().getColumn(1));
        final btVector3 swingAxis = transB.getBasis().mul(m_rbBFrame.getBasis().getColumn(1));
        //	btScalar angle = btAtan2Fast(swingAxis.dot(refAxis0), swingAxis.dot(refAxis1));
        double angle = btScalar.btAtan2(swingAxis.dot(refAxis0), swingAxis.dot(refAxis1));
        return m_referenceSign * angle;
    }

    public void testLimit(btTransform transA, btTransform transB) {
        // Compute limit information
        m_hingeAngle = getHingeAngle(transA, transB);
        m_limit.test(m_hingeAngle);
    }

    public btTransform getAFrame() {
        return m_rbAFrame;
    }

    public btTransform getBFrame() {
        return m_rbBFrame;
    }

    public int getSolveLimit() {
        return m_limit.isLimit() ? 1 : 0;
    }

    public double getLimitSign() {
        return m_limit.getSign();
    }

    public boolean getAngularOnly() {
        return m_angularOnly;
    }

    public boolean getEnableAngularMotor() {
        return m_enableAngularMotor;
    }

    public double getMotorTargetVelosity() {
        return m_motorTargetVelocity;
    }

    public double getMaxMotorImpulse() {
        return m_maxMotorImpulse;
    }

    // access for UseFrameOffset
    public boolean getUseFrameOffset() {
        return m_useOffsetForConstraintFrame;
    }

    public void setUseFrameOffset(boolean frameOffsetOnOff) {
        m_useOffsetForConstraintFrame = frameOffsetOnOff;
    }

    /**
     * override the default global value of a parameter (such as ERP or CFM), optionally provide the
     * axis (0..5). If no axis is provided, it uses the default axis for this constraint.
     */
    @Override
    public void setParam(int num, double value, int axis) {
        if ((axis == -1) || (axis == 5)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    m_stopERP = value;
                    m_flags |= BT_HINGE_FLAGS_ERP_STOP;
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    m_stopCFM = value;
                    m_flags |= BT_HINGE_FLAGS_CFM_STOP;
                    break;
                case BT_CONSTRAINT_CFM:
                    m_normalCFM = value;
                    m_flags |= BT_HINGE_FLAGS_CFM_NORM;
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else {
            // btAssertConstrParams(0);
        }
    }

    /** return the local value of parameter */
    @Override
    public double getParam(int num, int axis) {
        double retVal = 0;
        if ((axis == -1) || (axis == 5)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    retVal = m_stopERP;
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    retVal = m_stopCFM;
                    break;
                case BT_CONSTRAINT_CFM:
                    retVal = m_normalCFM;
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else {
            // btAssertConstrParams(0);
        }
        return retVal;
    }

    @Override
    public int calculateSerializeBufferSize() {
        return SIZEOF_btHingeConstraintDoubleData2;
    }
}
