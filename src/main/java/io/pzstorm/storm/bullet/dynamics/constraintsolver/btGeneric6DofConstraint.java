// Port of BulletDynamics/ConstraintSolver/btGeneric6DofConstraint.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btMatrix3x3;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * btGeneric6DofConstraint can leave any of the 6 degree of freedom 'free' or 'locked'. currently
 * this limit supports rotational motors.
 */
public class btGeneric6DofConstraint extends btTypedConstraint {
    // enum bt6DofFlags
    public static final int BT_6DOF_FLAGS_CFM_NORM = 1;
    public static final int BT_6DOF_FLAGS_CFM_STOP = 2;
    public static final int BT_6DOF_FLAGS_ERP_STOP = 4;

    /** #define BT_6DOF_FLAGS_AXIS_SHIFT 3 // bits per axis */
    public static final int BT_6DOF_FLAGS_AXIS_SHIFT = 3;

    /** #define D6_USE_OBSOLETE_METHOD false */
    public static final boolean D6_USE_OBSOLETE_METHOD = false;

    /** #define D6_USE_FRAME_OFFSET true */
    public static final boolean D6_USE_FRAME_OFFSET = true;

    /** sizeof(btGeneric6DofConstraintDoubleData2) */
    public static final int SIZEOF_btGeneric6DofConstraintDoubleData2 = 472;

    protected final btTransform m_frameInA =
            new btTransform(); // !< the constraint space w.r.t body A
    protected final btTransform m_frameInB =
            new btTransform(); // !< the constraint space w.r.t body B

    protected final btJacobianEntry[] m_jacLinear = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    }; // !< 3 orthogonal linear constraints
    protected final btJacobianEntry[] m_jacAng = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    }; // !< 3 orthogonal angular constraints

    protected final btTranslationalLimitMotor m_linearLimits = new btTranslationalLimitMotor();
    protected final btRotationalLimitMotor[] m_angularLimits = {
        new btRotationalLimitMotor(), new btRotationalLimitMotor(), new btRotationalLimitMotor()
    };

    protected double m_timeStep;
    protected final btTransform m_calculatedTransformA = new btTransform();
    protected final btTransform m_calculatedTransformB = new btTransform();
    protected final btVector3 m_calculatedAxisAngleDiff = new btVector3();
    protected final btVector3[] m_calculatedAxis = {
        new btVector3(), new btVector3(), new btVector3()
    };
    protected final btVector3 m_calculatedLinearDiff = new btVector3();
    protected double m_factA;
    protected double m_factB;
    protected boolean m_hasStaticBody;

    protected final btVector3 m_AnchorPos =
            new btVector3(); // point betwen pivots of bodies A and B

    protected boolean m_useLinearReferenceFrameA;
    protected boolean m_useOffsetForConstraintFrame;

    protected int m_flags;

    public boolean m_useSolveConstraintObsolete;

    public btGeneric6DofConstraint(
            btRigidBody rbA,
            btRigidBody rbB,
            btTransform frameInA,
            btTransform frameInB,
            boolean useLinearReferenceFrameA) {
        super(D6_CONSTRAINT_TYPE, rbA, rbB);
        m_frameInA.set(frameInA);
        m_frameInB.set(frameInB);
        m_useLinearReferenceFrameA = useLinearReferenceFrameA;
        m_useOffsetForConstraintFrame = D6_USE_FRAME_OFFSET;
        m_flags = 0;
        m_useSolveConstraintObsolete = D6_USE_OBSOLETE_METHOD;
        calculateTransforms();
    }

    public btGeneric6DofConstraint(
            btRigidBody rbB, btTransform frameInB, boolean useLinearReferenceFrameB) {
        super(D6_CONSTRAINT_TYPE, getFixedBody(), rbB);
        m_frameInB.set(frameInB);
        m_useLinearReferenceFrameA = useLinearReferenceFrameB;
        m_useOffsetForConstraintFrame = D6_USE_FRAME_OFFSET;
        m_flags = 0;
        m_useSolveConstraintObsolete = false;
        /// not providing rigidbody A means implicitly using worldspace for body A
        m_frameInA.set(rbB.getCenterOfMassTransform().mul(m_frameInB));
        calculateTransforms();
    }

    // #define GENERIC_D6_DISABLE_WARMSTARTING 1

    public static double btGetMatrixElem(btMatrix3x3 mat, int index) {
        int i = index % 3;
        int j = index / 3;
        return mat.get(i).get(j);
    }

    /// MatrixToEulerXYZ from
    // http://www.geometrictools.com/LibFoundation/Mathematics/Wm4Matrix3.inl.html
    public static boolean matrixToEulerXYZ(btMatrix3x3 mat, btVector3 xyz) {
        //	// rot =  cy*cz          -cy*sz           sy
        //	//        cz*sx*sy+cx*sz  cx*cz-sx*sy*sz -cy*sx
        //	//       -cx*cz*sy+sx*sz  cz*sx+cx*sy*sz  cx*cy
        //

        double fi = btGetMatrixElem(mat, 2);
        if (fi < (double) 1.0f) {
            if (fi > (double) -1.0f) {
                xyz.set(0, btScalar.btAtan2(-btGetMatrixElem(mat, 5), btGetMatrixElem(mat, 8)));
                xyz.set(1, btScalar.btAsin(btGetMatrixElem(mat, 2)));
                xyz.set(2, btScalar.btAtan2(-btGetMatrixElem(mat, 1), btGetMatrixElem(mat, 0)));
                return true;
            } else {
                // WARNING.  Not unique.  XA - ZA = -atan2(r10,r11)
                xyz.set(0, -btScalar.btAtan2(btGetMatrixElem(mat, 3), btGetMatrixElem(mat, 4)));
                xyz.set(1, -btScalar.SIMD_HALF_PI);
                xyz.set(2, 0.0);
                return false;
            }
        } else {
            // WARNING.  Not unique.  XAngle + ZAngle = atan2(r10,r11)
            xyz.set(0, btScalar.btAtan2(btGetMatrixElem(mat, 3), btGetMatrixElem(mat, 4)));
            xyz.set(1, btScalar.SIMD_HALF_PI);
            xyz.set(2, 0.0);
        }
        return false;
    }

    /** calcs the euler angles between the two bodies. */
    protected void calculateAngleInfo() {
        btMatrix3x3 relative_frame =
                m_calculatedTransformA.getBasis().inverse().mul(m_calculatedTransformB.getBasis());
        matrixToEulerXYZ(relative_frame, m_calculatedAxisAngleDiff);
        // in euler angle mode we do not actually constrain the angular velocity
        // along the axes axis[0] and axis[2] (although we do use axis[1]) :
        //
        //    to get			constrain w2-w1 along		...not
        //    ------			---------------------		------
        //    d(angle[0])/dt = 0	ax[1] x ax[2]			ax[0]
        //    d(angle[1])/dt = 0	ax[1]
        //    d(angle[2])/dt = 0	ax[0] x ax[1]			ax[2]
        //
        // constraining w2-w1 along an axis 'a' means that a'*(w2-w1)=0.
        // to prove the result for angle[0], write the expression for angle[0] from
        // GetInfo1 then take the derivative. to prove this for angle[2] it is
        // easier to take the euler rate expression for d(angle[2])/dt with respect
        // to the components of w and set that to 0.
        btVector3 axis0 = m_calculatedTransformB.getBasis().getColumn(0);
        btVector3 axis2 = m_calculatedTransformA.getBasis().getColumn(2);

        m_calculatedAxis[1].set(axis2.cross(axis0));
        m_calculatedAxis[0].set(m_calculatedAxis[1].cross(axis2));
        m_calculatedAxis[2].set(axis0.cross(m_calculatedAxis[1]));

        m_calculatedAxis[0].normalize();
        m_calculatedAxis[1].normalize();
        m_calculatedAxis[2].normalize();
    }

    public void calculateTransforms() {
        calculateTransforms(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
    }

    /**
     * Calcs the global transform for the joint offset for body A an B, and also calcs the agle
     * differences between the bodies.
     */
    public void calculateTransforms(btTransform transA, btTransform transB) {
        m_calculatedTransformA.set(transA.mul(m_frameInA));
        m_calculatedTransformB.set(transB.mul(m_frameInB));
        calculateLinearInfo();
        calculateAngleInfo();
        if (m_useOffsetForConstraintFrame) { //  get weight factors depending on masses
            double miA = getRigidBodyA().getInvMass();
            double miB = getRigidBodyB().getInvMass();
            m_hasStaticBody = (miA < btScalar.SIMD_EPSILON) || (miB < btScalar.SIMD_EPSILON);
            double miS = miA + miB;
            if (miS > (double) 0.f) {
                m_factA = miB / miS;
            } else {
                m_factA = (double) 0.5f;
            }
            m_factB = (double) 1.0f - m_factA;
        }
    }

    protected void buildLinearJacobian(
            btJacobianEntry jacLinear,
            btVector3 normalWorld,
            btVector3 pivotAInW,
            btVector3 pivotBInW) {
        jacLinear.set(
                new btJacobianEntry(
                        m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                        m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                        pivotAInW.sub(m_rbA.getCenterOfMassPosition()),
                        pivotBInW.sub(m_rbB.getCenterOfMassPosition()),
                        normalWorld,
                        m_rbA.getInvInertiaDiagLocal(),
                        m_rbA.getInvMass(),
                        m_rbB.getInvInertiaDiagLocal(),
                        m_rbB.getInvMass()));
    }

    protected void buildAngularJacobian(btJacobianEntry jacAngular, btVector3 jointAxisW) {
        jacAngular.set(
                new btJacobianEntry(
                        jointAxisW,
                        m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                        m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                        m_rbA.getInvInertiaDiagLocal(),
                        m_rbB.getInvInertiaDiagLocal()));
    }

    /**
     * Calculates angular correction and returns true if limit needs to be corrected. \pre
     * btGeneric6DofConstraint::calculateTransforms() must be called previously.
     */
    public boolean testAngularLimitMotor(int axis_index) {
        double angle = m_calculatedAxisAngleDiff.get(axis_index);
        angle =
                btAdjustAngleToLimits(
                        angle,
                        m_angularLimits[axis_index].m_loLimit,
                        m_angularLimits[axis_index].m_hiLimit);
        m_angularLimits[axis_index].m_currentPosition = angle;
        // test limits
        m_angularLimits[axis_index].testLimitValue(angle);
        return m_angularLimits[axis_index].needApplyTorques();
    }

    /** performs Jacobian calculation, and also calculates angle differences and axis */
    @Override
    public void buildJacobian() {
        if (m_useSolveConstraintObsolete) {

            // Clear accumulated impulses for the next simulation step
            m_linearLimits.m_accumulatedImpulse.setValue(0., 0., 0.);
            int i;
            for (i = 0; i < 3; i++) {
                m_angularLimits[i].m_accumulatedImpulse = 0.;
            }
            // calculates transform
            calculateTransforms(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());

            calcAnchorPos();
            btVector3 pivotAInW = new btVector3(m_AnchorPos);
            btVector3 pivotBInW = new btVector3(m_AnchorPos);

            btVector3 normalWorld;
            // linear part
            for (i = 0; i < 3; i++) {
                if (m_linearLimits.isLimited(i)) {
                    if (m_useLinearReferenceFrameA)
                        normalWorld = m_calculatedTransformA.getBasis().getColumn(i);
                    else normalWorld = m_calculatedTransformB.getBasis().getColumn(i);

                    buildLinearJacobian(m_jacLinear[i], normalWorld, pivotAInW, pivotBInW);
                }
            }

            // angular part
            for (i = 0; i < 3; i++) {
                // calculates error angle
                if (testAngularLimitMotor(i)) {
                    normalWorld = this.getAxis(i);
                    // Create angular atom
                    buildAngularJacobian(m_jacAng[i], normalWorld);
                }
            }
        }
    }

    @Override
    public void getInfo1(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            // prepare constraint
            calculateTransforms(m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
            info.m_numConstraintRows = 0;
            info.nub = 6;
            int i;
            // test linear limits
            for (i = 0; i < 3; i++) {
                if (m_linearLimits.needApplyForce(i)) {
                    info.m_numConstraintRows++;
                    info.nub--;
                }
            }
            // test angular limits
            for (i = 0; i < 3; i++) {
                if (testAngularLimitMotor(i)) {
                    info.m_numConstraintRows++;
                    info.nub--;
                }
            }
        }
    }

    public void getInfo1NonVirtual(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            // pre-allocate all 6
            info.m_numConstraintRows = 6;
            info.nub = 0;
        }
    }

    @Override
    public void getInfo2(btConstraintInfo2 info) {
        final btTransform transA = m_rbA.getCenterOfMassTransform();
        final btTransform transB = m_rbB.getCenterOfMassTransform();
        final btVector3 linVelA = m_rbA.getLinearVelocity();
        final btVector3 linVelB = m_rbB.getLinearVelocity();
        final btVector3 angVelA = m_rbA.getAngularVelocity();
        final btVector3 angVelB = m_rbB.getAngularVelocity();

        if (m_useOffsetForConstraintFrame) { // for stability better to solve angular limits first
            int row = setAngularLimits(info, 0, transA, transB, linVelA, linVelB, angVelA, angVelB);
            setLinearLimits(info, row, transA, transB, linVelA, linVelB, angVelA, angVelB);
        } else { // leave old version for compatibility
            int row = setLinearLimits(info, 0, transA, transB, linVelA, linVelB, angVelA, angVelB);
            setAngularLimits(info, row, transA, transB, linVelA, linVelB, angVelA, angVelB);
        }
    }

    public void getInfo2NonVirtual(
            btConstraintInfo2 info,
            btTransform transA,
            btTransform transB,
            btVector3 linVelA,
            btVector3 linVelB,
            btVector3 angVelA,
            btVector3 angVelB) {
        // prepare constraint
        calculateTransforms(transA, transB);

        int i;
        for (i = 0; i < 3; i++) {
            testAngularLimitMotor(i);
        }

        if (m_useOffsetForConstraintFrame) { // for stability better to solve angular limits first
            int row = setAngularLimits(info, 0, transA, transB, linVelA, linVelB, angVelA, angVelB);
            setLinearLimits(info, row, transA, transB, linVelA, linVelB, angVelA, angVelB);
        } else { // leave old version for compatibility
            int row = setLinearLimits(info, 0, transA, transB, linVelA, linVelB, angVelA, angVelB);
            setAngularLimits(info, row, transA, transB, linVelA, linVelB, angVelA, angVelB);
        }
    }

    protected int setLinearLimits(
            btConstraintInfo2 info,
            int row,
            btTransform transA,
            btTransform transB,
            btVector3 linVelA,
            btVector3 linVelB,
            btVector3 angVelA,
            btVector3 angVelB) {
        //	int row = 0;
        // solve linear limits
        btRotationalLimitMotor limot = new btRotationalLimitMotor();
        for (int i = 0; i < 3; i++) {
            if (m_linearLimits.needApplyForce(i)) { // re-use rotational motor code
                limot.m_bounce = (double) 0.f;
                limot.m_currentLimit = m_linearLimits.m_currentLimit[i];
                limot.m_currentPosition = m_linearLimits.m_currentLinearDiff.get(i);
                limot.m_currentLimitError = m_linearLimits.m_currentLimitError.get(i);
                limot.m_damping = m_linearLimits.m_damping;
                limot.m_enableMotor = m_linearLimits.m_enableMotor[i];
                limot.m_hiLimit = m_linearLimits.m_upperLimit.get(i);
                limot.m_limitSoftness = m_linearLimits.m_limitSoftness;
                limot.m_loLimit = m_linearLimits.m_lowerLimit.get(i);
                limot.m_maxLimitForce = (double) 0.f;
                limot.m_maxMotorForce = m_linearLimits.m_maxMotorForce.get(i);
                limot.m_targetVelocity = m_linearLimits.m_targetVelocity.get(i);
                btVector3 axis = m_calculatedTransformA.getBasis().getColumn(i);
                int flags = m_flags >> (i * BT_6DOF_FLAGS_AXIS_SHIFT);
                limot.m_normalCFM =
                        (flags & BT_6DOF_FLAGS_CFM_NORM) != 0
                                ? m_linearLimits.m_normalCFM.get(i)
                                : info.cfm.get(0);
                limot.m_stopCFM =
                        (flags & BT_6DOF_FLAGS_CFM_STOP) != 0
                                ? m_linearLimits.m_stopCFM.get(i)
                                : info.cfm.get(0);
                limot.m_stopERP =
                        (flags & BT_6DOF_FLAGS_ERP_STOP) != 0
                                ? m_linearLimits.m_stopERP.get(i)
                                : info.erp;
                if (m_useOffsetForConstraintFrame) {
                    int indx1 = (i + 1) % 3;
                    int indx2 = (i + 2) % 3;
                    int rotAllowed = 1; // rotations around orthos to current axis
                    if (m_angularLimits[indx1].m_currentLimit != 0
                            && m_angularLimits[indx2].m_currentLimit != 0) {
                        rotAllowed = 0;
                    }
                    row +=
                            get_limit_motor_info2(
                                    limot,
                                    transA,
                                    transB,
                                    linVelA,
                                    linVelB,
                                    angVelA,
                                    angVelB,
                                    info,
                                    row,
                                    axis,
                                    0,
                                    rotAllowed);
                } else {
                    row +=
                            get_limit_motor_info2(
                                    limot, transA, transB, linVelA, linVelB, angVelA, angVelB, info,
                                    row, axis, 0);
                }
            }
        }
        return row;
    }

    protected int setAngularLimits(
            btConstraintInfo2 info,
            int row_offset,
            btTransform transA,
            btTransform transB,
            btVector3 linVelA,
            btVector3 linVelB,
            btVector3 angVelA,
            btVector3 angVelB) {
        btGeneric6DofConstraint d6constraint = this;
        int row = row_offset;
        // solve angular limits
        for (int i = 0; i < 3; i++) {
            if (d6constraint.getRotationalLimitMotor(i).needApplyTorques()) {
                btVector3 axis = d6constraint.getAxis(i);
                int flags = m_flags >> ((i + 3) * BT_6DOF_FLAGS_AXIS_SHIFT);
                if ((flags & BT_6DOF_FLAGS_CFM_NORM) == 0) {
                    m_angularLimits[i].m_normalCFM = info.cfm.get(0);
                }
                if ((flags & BT_6DOF_FLAGS_CFM_STOP) == 0) {
                    m_angularLimits[i].m_stopCFM = info.cfm.get(0);
                }
                if ((flags & BT_6DOF_FLAGS_ERP_STOP) == 0) {
                    m_angularLimits[i].m_stopERP = info.erp;
                }
                row +=
                        get_limit_motor_info2(
                                d6constraint.getRotationalLimitMotor(i),
                                transA,
                                transB,
                                linVelA,
                                linVelB,
                                angVelA,
                                angVelB,
                                info,
                                row,
                                axis,
                                1);
            }
        }

        return row;
    }

    public void updateRHS(double timeStep) {}

    /** \pre btGeneric6DofConstraint.buildJacobian must be called previously. */
    public btVector3 getAxis(int axis_index) {
        return new btVector3(m_calculatedAxis[axis_index]);
    }

    /** \pre btGeneric6DofConstraint::calculateTransforms() must be called previously. */
    public double getAngle(int axisIndex) {
        return m_calculatedAxisAngleDiff.get(axisIndex);
    }

    /** \pre btGeneric6DofConstraint::calculateTransforms() must be called previously. */
    public double getRelativePivotPosition(int axisIndex) {
        return m_calculatedLinearDiff.get(axisIndex);
    }

    public void setFrames(btTransform frameA, btTransform frameB) {
        m_frameInA.set(frameA);
        m_frameInB.set(frameB);
        buildJacobian();
        calculateTransforms();
    }

    public btTransform getCalculatedTransformA() {
        return m_calculatedTransformA;
    }

    public btTransform getCalculatedTransformB() {
        return m_calculatedTransformB;
    }

    public btTransform getFrameOffsetA() {
        return m_frameInA;
    }

    public btTransform getFrameOffsetB() {
        return m_frameInB;
    }

    public void setLinearLowerLimit(btVector3 linearLower) {
        m_linearLimits.m_lowerLimit.set(linearLower);
    }

    public void getLinearLowerLimit(btVector3 linearLower) {
        linearLower.set(m_linearLimits.m_lowerLimit);
    }

    public void setLinearUpperLimit(btVector3 linearUpper) {
        m_linearLimits.m_upperLimit.set(linearUpper);
    }

    public void getLinearUpperLimit(btVector3 linearUpper) {
        linearUpper.set(m_linearLimits.m_upperLimit);
    }

    public void setAngularLowerLimit(btVector3 angularLower) {
        for (int i = 0; i < 3; i++)
            m_angularLimits[i].m_loLimit = btScalar.btNormalizeAngle(angularLower.get(i));
    }

    public void getAngularLowerLimit(btVector3 angularLower) {
        for (int i = 0; i < 3; i++) angularLower.set(i, m_angularLimits[i].m_loLimit);
    }

    public void setAngularUpperLimit(btVector3 angularUpper) {
        for (int i = 0; i < 3; i++)
            m_angularLimits[i].m_hiLimit = btScalar.btNormalizeAngle(angularUpper.get(i));
    }

    public void getAngularUpperLimit(btVector3 angularUpper) {
        for (int i = 0; i < 3; i++) angularUpper.set(i, m_angularLimits[i].m_hiLimit);
    }

    /** Retrieves the angular limit informacion */
    public btRotationalLimitMotor getRotationalLimitMotor(int index) {
        return m_angularLimits[index];
    }

    /** Retrieves the limit informacion */
    public btTranslationalLimitMotor getTranslationalLimitMotor() {
        return m_linearLimits;
    }

    // first 3 are linear, next 3 are angular
    public void setLimit(int axis, double lo, double hi) {
        if (axis < 3) {
            m_linearLimits.m_lowerLimit.set(axis, lo);
            m_linearLimits.m_upperLimit.set(axis, hi);
        } else {
            lo = btScalar.btNormalizeAngle(lo);
            hi = btScalar.btNormalizeAngle(hi);
            m_angularLimits[axis - 3].m_loLimit = lo;
            m_angularLimits[axis - 3].m_hiLimit = hi;
        }
    }

    /**
     * Test limit - free means upper &lt; lower, - locked means upper == lower - limited means upper
     * &gt; lower - limitIndex: first 3 are linear, next 3 are angular
     */
    public boolean isLimited(int limitIndex) {
        if (limitIndex < 3) {
            return m_linearLimits.isLimited(limitIndex);
        }
        return m_angularLimits[limitIndex - 3].isLimited();
    }

    public void calcAnchorPos() {
        double imA = m_rbA.getInvMass();
        double imB = m_rbB.getInvMass();
        double weight;
        if (imB == 0.0) {
            weight = 1.0;
        } else {
            weight = imA / (imA + imB);
        }
        final btVector3 pA = m_calculatedTransformA.getOrigin();
        final btVector3 pB = m_calculatedTransformB.getOrigin();
        m_AnchorPos.set(pA.mul(weight).add(pB.mul(1.0 - weight)));
        return;
    }

    protected void calculateLinearInfo() {
        m_calculatedLinearDiff.set(
                m_calculatedTransformB.getOrigin().sub(m_calculatedTransformA.getOrigin()));
        m_calculatedLinearDiff.set(
                m_calculatedTransformA.getBasis().inverse().mul(m_calculatedLinearDiff));
        for (int i = 0; i < 3; i++) {
            m_linearLimits.m_currentLinearDiff.set(i, m_calculatedLinearDiff.get(i));
            m_linearLimits.testLimitValue(i, m_calculatedLinearDiff.get(i));
        }
    }

    public int get_limit_motor_info2(
            btRotationalLimitMotor limot,
            btTransform transA,
            btTransform transB,
            btVector3 linVelA,
            btVector3 linVelB,
            btVector3 angVelA,
            btVector3 angVelB,
            btConstraintInfo2 info,
            int row,
            btVector3 ax1,
            int rotational) {
        return get_limit_motor_info2(
                limot,
                transA,
                transB,
                linVelA,
                linVelB,
                angVelA,
                angVelB,
                info,
                row,
                ax1,
                rotational,
                0);
    }

    public int get_limit_motor_info2(
            btRotationalLimitMotor limot,
            btTransform transA,
            btTransform transB,
            btVector3 linVelA,
            btVector3 linVelB,
            btVector3 angVelA,
            btVector3 angVelB,
            btConstraintInfo2 info,
            int row,
            btVector3 ax1,
            int rotational,
            int rotAllowed) {
        int srow = row * info.rowskip;
        int powered = limot.m_enableMotor ? 1 : 0;
        int limit = limot.m_currentLimit;
        if (powered != 0
                || limit != 0) { // if the joint is powered, or has joint limits, add in the extra
            // row
            btScalarPtr J1 = rotational != 0 ? info.m_J1angularAxis : info.m_J1linearAxis;
            btScalarPtr J2 = rotational != 0 ? info.m_J2angularAxis : info.m_J2linearAxis;
            J1.set(srow + 0, ax1.get(0));
            J1.set(srow + 1, ax1.get(1));
            J1.set(srow + 2, ax1.get(2));

            J2.set(srow + 0, -ax1.get(0));
            J2.set(srow + 1, -ax1.get(1));
            J2.set(srow + 2, -ax1.get(2));

            if ((rotational == 0)) {
                if (m_useOffsetForConstraintFrame) {
                    btVector3 tmpA, tmpB, relA, relB;
                    // get vector from bodyB to frameB in WCS
                    relB = m_calculatedTransformB.getOrigin().sub(transB.getOrigin());
                    // get its projection to constraint axis
                    btVector3 projB = ax1.mul(relB.dot(ax1));
                    // get vector directed from bodyB to constraint axis (and orthogonal to it)
                    btVector3 orthoB = relB.sub(projB);
                    // same for bodyA
                    relA = m_calculatedTransformA.getOrigin().sub(transA.getOrigin());
                    btVector3 projA = ax1.mul(relA.dot(ax1));
                    btVector3 orthoA = relA.sub(projA);
                    // get desired offset between frames A and B along constraint axis
                    double desiredOffs = limot.m_currentPosition - limot.m_currentLimitError;
                    // desired vector from projection of center of bodyA to projection of center
                    // of bodyB to constraint axis
                    btVector3 totalDist = projA.add(ax1.mul(desiredOffs)).sub(projB);
                    // get offset vectors relA and relB
                    relA = orthoA.add(totalDist.mul(m_factA));
                    relB = orthoB.sub(totalDist.mul(m_factB));
                    tmpA = relA.cross(ax1);
                    tmpB = relB.cross(ax1);
                    if (m_hasStaticBody && (rotAllowed == 0)) {
                        tmpA.mulLocal(m_factA);
                        tmpB.mulLocal(m_factB);
                    }
                    int i;
                    for (i = 0; i < 3; i++) info.m_J1angularAxis.set(srow + i, tmpA.get(i));
                    for (i = 0; i < 3; i++) info.m_J2angularAxis.set(srow + i, -tmpB.get(i));
                } else {
                    btVector3 ltd; // Linear Torque Decoupling vector
                    btVector3 c = m_calculatedTransformB.getOrigin().sub(transA.getOrigin());
                    ltd = c.cross(ax1);
                    info.m_J1angularAxis.set(srow + 0, ltd.get(0));
                    info.m_J1angularAxis.set(srow + 1, ltd.get(1));
                    info.m_J1angularAxis.set(srow + 2, ltd.get(2));

                    c = m_calculatedTransformB.getOrigin().sub(transB.getOrigin());
                    ltd = c.cross(ax1).negate();
                    info.m_J2angularAxis.set(srow + 0, ltd.get(0));
                    info.m_J2angularAxis.set(srow + 1, ltd.get(1));
                    info.m_J2angularAxis.set(srow + 2, ltd.get(2));
                }
            }
            // if we're limited low and high simultaneously, the joint motor is
            // ineffective
            if (limit != 0 && (limot.m_loLimit == limot.m_hiLimit)) powered = 0;
            info.m_constraintError.set(srow, (double) 0.f);
            if (powered != 0) {
                info.cfm.set(srow, limot.m_normalCFM);
                if (limit == 0) {
                    double tag_vel =
                            rotational != 0 ? limot.m_targetVelocity : -limot.m_targetVelocity;

                    double mot_fact =
                            getMotorFactor(
                                    limot.m_currentPosition,
                                    limot.m_loLimit,
                                    limot.m_hiLimit,
                                    tag_vel,
                                    info.fps * limot.m_stopERP);
                    info.m_constraintError.set(
                            srow,
                            info.m_constraintError.get(srow) + mot_fact * limot.m_targetVelocity);
                    info.m_lowerLimit.set(srow, -limot.m_maxMotorForce);
                    info.m_upperLimit.set(srow, limot.m_maxMotorForce);
                }
            }
            if (limit != 0) {
                double k = info.fps * limot.m_stopERP;
                if (rotational == 0) {
                    info.m_constraintError.set(
                            srow, info.m_constraintError.get(srow) + k * limot.m_currentLimitError);
                } else {
                    info.m_constraintError.set(
                            srow,
                            info.m_constraintError.get(srow) + -k * limot.m_currentLimitError);
                }
                info.cfm.set(srow, limot.m_stopCFM);
                if (limot.m_loLimit == limot.m_hiLimit) { // limited low and high simultaneously
                    info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                    info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                } else {
                    if (limit == 1) {
                        info.m_lowerLimit.set(srow, 0);
                        info.m_upperLimit.set(srow, btScalar.SIMD_INFINITY);
                    } else {
                        info.m_lowerLimit.set(srow, -btScalar.SIMD_INFINITY);
                        info.m_upperLimit.set(srow, 0);
                    }
                    // deal with bounce
                    if (limot.m_bounce > 0) {
                        // calculate joint velocity
                        double vel;
                        if (rotational != 0) {
                            vel = angVelA.dot(ax1);
                            // make sure that if no body -> angVelB == zero vec
                            //                        if (body1)
                            vel -= angVelB.dot(ax1);
                        } else {
                            vel = linVelA.dot(ax1);
                            // make sure that if no body -> angVelB == zero vec
                            //                        if (body1)
                            vel -= linVelB.dot(ax1);
                        }
                        // only apply bounce if the velocity is incoming, and if the
                        // resulting c[] exceeds what we already have.
                        if (limit == 1) {
                            if (vel < 0) {
                                double newc = -limot.m_bounce * vel;
                                if (newc > info.m_constraintError.get(srow))
                                    info.m_constraintError.set(srow, newc);
                            }
                        } else {
                            if (vel > 0) {
                                double newc = -limot.m_bounce * vel;
                                if (newc < info.m_constraintError.get(srow))
                                    info.m_constraintError.set(srow, newc);
                            }
                        }
                    }
                }
            }
            return 1;
        } else return 0;
    }

    // access for UseFrameOffset
    public boolean getUseFrameOffset() {
        return m_useOffsetForConstraintFrame;
    }

    public void setUseFrameOffset(boolean frameOffsetOnOff) {
        m_useOffsetForConstraintFrame = frameOffsetOnOff;
    }

    /// override the default global value of a parameter (such as ERP or CFM), optionally provide
    /// the axis (0..5). If no axis is provided, it uses the default axis for this constraint.
    @Override
    public void setParam(int num, double value, int axis) {
        if ((axis >= 0) && (axis < 3)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    m_linearLimits.m_stopERP.set(axis, value);
                    m_flags |= BT_6DOF_FLAGS_ERP_STOP << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    m_linearLimits.m_stopCFM.set(axis, value);
                    m_flags |= BT_6DOF_FLAGS_CFM_STOP << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                case BT_CONSTRAINT_CFM:
                    m_linearLimits.m_normalCFM.set(axis, value);
                    m_flags |= BT_6DOF_FLAGS_CFM_NORM << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else if ((axis >= 3) && (axis < 6)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    m_angularLimits[axis - 3].m_stopERP = value;
                    m_flags |= BT_6DOF_FLAGS_ERP_STOP << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    m_angularLimits[axis - 3].m_stopCFM = value;
                    m_flags |= BT_6DOF_FLAGS_CFM_STOP << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                case BT_CONSTRAINT_CFM:
                    m_angularLimits[axis - 3].m_normalCFM = value;
                    m_flags |= BT_6DOF_FLAGS_CFM_NORM << (axis * BT_6DOF_FLAGS_AXIS_SHIFT);
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else {
            // btAssertConstrParams(0);
        }
    }

    /// return the local value of parameter
    @Override
    public double getParam(int num, int axis) {
        double retVal = 0;
        if ((axis >= 0) && (axis < 3)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    retVal = m_linearLimits.m_stopERP.get(axis);
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    retVal = m_linearLimits.m_stopCFM.get(axis);
                    break;
                case BT_CONSTRAINT_CFM:
                    retVal = m_linearLimits.m_normalCFM.get(axis);
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else if ((axis >= 3) && (axis < 6)) {
            switch (num) {
                case BT_CONSTRAINT_STOP_ERP:
                    retVal = m_angularLimits[axis - 3].m_stopERP;
                    break;
                case BT_CONSTRAINT_STOP_CFM:
                    retVal = m_angularLimits[axis - 3].m_stopCFM;
                    break;
                case BT_CONSTRAINT_CFM:
                    retVal = m_angularLimits[axis - 3].m_normalCFM;
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        } else {
            // btAssertConstrParams(0);
        }
        return retVal;
    }

    public void setAxis(btVector3 axis1, btVector3 axis2) {
        btVector3 zAxis = axis1.normalized();
        btVector3 yAxis = axis2.normalized();
        btVector3 xAxis = yAxis.cross(zAxis); // we want right coordinate system

        btTransform frameInW = new btTransform();
        frameInW.setIdentity();
        frameInW.getBasis()
                .setValue(
                        xAxis.get(0),
                        yAxis.get(0),
                        zAxis.get(0),
                        xAxis.get(1),
                        yAxis.get(1),
                        zAxis.get(1),
                        xAxis.get(2),
                        yAxis.get(2),
                        zAxis.get(2));

        // now get constraint frame in local coordinate systems
        m_frameInA.set(m_rbA.getCenterOfMassTransform().inverse().mul(frameInW));
        m_frameInB.set(m_rbB.getCenterOfMassTransform().inverse().mul(frameInW));

        calculateTransforms();
    }

    @Override
    public int calculateSerializeBufferSize() {
        return SIZEOF_btGeneric6DofConstraintDoubleData2;
    }
}
