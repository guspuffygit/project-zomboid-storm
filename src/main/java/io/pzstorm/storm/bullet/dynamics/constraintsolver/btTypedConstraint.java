// Port of BulletDynamics/ConstraintSolver/btTypedConstraint.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btAlignedObjectArray;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTypedObject;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/** btTypedConstraint is the baseclass for Bullet constraints and vehicles. */
public abstract class btTypedConstraint extends btTypedObject {
    // enum btTypedConstraintType
    public static final int POINT2POINT_CONSTRAINT_TYPE = 3;
    public static final int HINGE_CONSTRAINT_TYPE = 4;
    public static final int CONETWIST_CONSTRAINT_TYPE = 5;
    public static final int D6_CONSTRAINT_TYPE = 6;
    public static final int SLIDER_CONSTRAINT_TYPE = 7;
    public static final int CONTACT_CONSTRAINT_TYPE = 8;
    public static final int D6_SPRING_CONSTRAINT_TYPE = 9;
    public static final int GEAR_CONSTRAINT_TYPE = 10;
    public static final int FIXED_CONSTRAINT_TYPE = 11;
    public static final int MAX_CONSTRAINT_TYPE = 12;

    // enum btConstraintParams
    public static final int BT_CONSTRAINT_ERP = 1;
    public static final int BT_CONSTRAINT_STOP_ERP = 2;
    public static final int BT_CONSTRAINT_CFM = 3;
    public static final int BT_CONSTRAINT_STOP_CFM = 4;

    /** #define DEFAULT_DEBUGDRAW_SIZE btScalar(0.3f) */
    public static final double DEFAULT_DEBUGDRAW_SIZE = (double) 0.3f;

    /** sizeof(btTypedConstraintDoubleData) (BT_USE_DOUBLE_PRECISION). */
    public static final int SIZEOF_btTypedConstraintDoubleData = 80;

    private int m_userConstraintType;

    // union { int m_userConstraintId; void* m_userConstraintPtr; };
    private int m_userConstraintId;
    private Object m_userConstraintPtr;

    private double m_breakingImpulseThreshold;
    private boolean m_isEnabled;
    private boolean m_needsFeedback;
    private int m_overrideNumSolverIterations;

    protected final btRigidBody m_rbA;
    protected final btRigidBody m_rbB;
    protected double m_appliedImpulse;
    protected double m_dbgDrawSize;
    protected btJointFeedback m_jointFeedback;

    private static btRigidBody s_fixed;

    public btTypedConstraint(int type, btRigidBody rbA) {
        super(type);
        m_userConstraintType = -1;
        m_userConstraintId = -1;
        m_breakingImpulseThreshold = btScalar.SIMD_INFINITY;
        m_isEnabled = true;
        m_needsFeedback = false;
        m_overrideNumSolverIterations = -1;
        m_rbA = rbA;
        m_rbB = getFixedBody();
        m_appliedImpulse = 0.;
        m_dbgDrawSize = DEFAULT_DEBUGDRAW_SIZE;
        m_jointFeedback = null;
    }

    public btTypedConstraint(int type, btRigidBody rbA, btRigidBody rbB) {
        super(type);
        m_userConstraintType = -1;
        m_userConstraintId = -1;
        m_breakingImpulseThreshold = btScalar.SIMD_INFINITY;
        m_isEnabled = true;
        m_needsFeedback = false;
        m_overrideNumSolverIterations = -1;
        m_rbA = rbA;
        m_rbB = rbB;
        m_appliedImpulse = 0.;
        m_dbgDrawSize = DEFAULT_DEBUGDRAW_SIZE;
        m_jointFeedback = null;
    }

    /** virtual ~btTypedConstraint() */
    public void destroy() {}

    /** internal method used by the constraint solver, don't use them directly */
    protected double getMotorFactor(
            double pos, double lowLim, double uppLim, double vel, double timeFact) {
        if (lowLim > uppLim) {
            return (double) 1.0f;
        } else if (lowLim == uppLim) {
            return (double) 0.0f;
        }
        double lim_fact = (double) 1.0f;
        double delta_max = vel / timeFact;
        if (delta_max < (double) 0.0f) {
            if ((pos >= lowLim) && (pos < (lowLim - delta_max))) {
                lim_fact = (lowLim - pos) / delta_max;
            } else if (pos < lowLim) {
                lim_fact = (double) 0.0f;
            } else {
                lim_fact = (double) 1.0f;
            }
        } else if (delta_max > (double) 0.0f) {
            if ((pos <= uppLim) && (pos > (uppLim - delta_max))) {
                lim_fact = (uppLim - pos) / delta_max;
            } else if (pos > uppLim) {
                lim_fact = (double) 0.0f;
            } else {
                lim_fact = (double) 1.0f;
            }
        } else {
            lim_fact = (double) 0.0f;
        }
        return lim_fact;
    }

    /** {@code static btRigidBody s_fixed(0, 0, 0)} constructed on first call (C++ local static). */
    public static btRigidBody getFixedBody() {
        if (s_fixed == null) {
            s_fixed = new btRigidBody(0, null, null);
        }
        s_fixed.setMassProps(0., new btVector3(0., 0., 0.));
        return s_fixed;
    }

    public static final class btConstraintInfo1 {
        public int m_numConstraintRows, nub;
    }

    public static final class btConstraintInfo2 {
        // integrator parameters: frames per second (1/stepsize), default error
        // reduction parameter (0..1).
        public double fps, erp;

        // for the first and second body, pointers to two (linear and angular)
        // n*3 jacobian sub matrices, stored by rows. these matrices will have
        // been initialized to 0 on entry. if the second body is zero then the
        // J2xx pointers may be 0.
        public btScalarPtr m_J1linearAxis, m_J1angularAxis, m_J2linearAxis, m_J2angularAxis;

        // elements to jump from one row to the next in J's
        public int rowskip;

        // right hand sides of the equation J*v = c + cfm * lambda. cfm is the
        // "constraint force mixing" vector. c is set to zero on entry, cfm is
        // set to a constant value (typically very small or zero) value on entry.
        public btScalarPtr m_constraintError, cfm;

        // lo and hi limits of variables (set to -/+ infinity on entry).
        public btScalarPtr m_lowerLimit, m_upperLimit;

        // findex vector for variables. see the LCP solver interface for a
        // description of what this does. this is set to -1 on entry.
        // note that the returned indexes are relative to the first index of
        // the constraint.
        public int[] findex;

        // number of solver iterations
        public int m_numIterations;

        // damping of the velocity
        public double m_damping;
    }

    public int getOverrideNumSolverIterations() {
        return m_overrideNumSolverIterations;
    }

    /**
     * override the number of constraint solver iterations used to solve this constraint -1 will use
     * the default number of iterations, as specified in SolverInfo.m_numIterations
     */
    public void setOverrideNumSolverIterations(int overideNumIterations) {
        m_overrideNumSolverIterations = overideNumIterations;
    }

    /** internal method used by the constraint solver, don't use them directly */
    public void buildJacobian() {}

    /** internal method used by the constraint solver, don't use them directly */
    public void setupSolverConstraint(
            btAlignedObjectArray<btSolverConstraint> ca,
            int solverBodyA,
            int solverBodyB,
            double timeStep) {}

    /** internal method used by the constraint solver, don't use them directly */
    public abstract void getInfo1(btConstraintInfo1 info);

    /** internal method used by the constraint solver, don't use them directly */
    public abstract void getInfo2(btConstraintInfo2 info);

    /** internal method used by the constraint solver, don't use them directly */
    public void internalSetAppliedImpulse(double appliedImpulse) {
        m_appliedImpulse = appliedImpulse;
    }

    /** internal method used by the constraint solver, don't use them directly */
    public double internalGetAppliedImpulse() {
        return m_appliedImpulse;
    }

    public double getBreakingImpulseThreshold() {
        return m_breakingImpulseThreshold;
    }

    public void setBreakingImpulseThreshold(double threshold) {
        m_breakingImpulseThreshold = threshold;
    }

    public boolean isEnabled() {
        return m_isEnabled;
    }

    public void setEnabled(boolean enabled) {
        m_isEnabled = enabled;
    }

    /** internal method used by the constraint solver, don't use them directly */
    public void solveConstraintObsolete(btSolverBody bodyA, btSolverBody bodyB, double timeStep) {}

    public btRigidBody getRigidBodyA() {
        return m_rbA;
    }

    public btRigidBody getRigidBodyB() {
        return m_rbB;
    }

    public int getUserConstraintType() {
        return m_userConstraintType;
    }

    public void setUserConstraintType(int userConstraintType) {
        m_userConstraintType = userConstraintType;
    }

    /** union with m_userConstraintPtr: writing the id clears the pointer. */
    public void setUserConstraintId(int uid) {
        m_userConstraintId = uid;
        m_userConstraintPtr = null;
    }

    public int getUserConstraintId() {
        return m_userConstraintId;
    }

    /** union with m_userConstraintId: writing the pointer leaves the id unspecified (kept). */
    public void setUserConstraintPtr(Object ptr) {
        m_userConstraintPtr = ptr;
    }

    public Object getUserConstraintPtr() {
        return m_userConstraintPtr;
    }

    public void setJointFeedback(btJointFeedback jointFeedback) {
        m_jointFeedback = jointFeedback;
    }

    public btJointFeedback getJointFeedback() {
        return m_jointFeedback;
    }

    public int getUid() {
        return m_userConstraintId;
    }

    public boolean needsFeedback() {
        return m_needsFeedback;
    }

    /**
     * enableFeedback will allow to read the applied linear and angular impulse use
     * getAppliedImpulse, getAppliedLinearImpulse and getAppliedAngularImpulse to read feedback
     * information
     */
    public void enableFeedback(boolean needsFeedback) {
        m_needsFeedback = needsFeedback;
    }

    /**
     * getAppliedImpulse is an estimated total applied impulse. This feedback could be used to
     * determine breaking constraints or playing sounds.
     */
    public double getAppliedImpulse() {
        return m_appliedImpulse;
    }

    public int getConstraintType() {
        return m_objectType;
    }

    public void setDbgDrawSize(double dbgDrawSize) {
        m_dbgDrawSize = dbgDrawSize;
    }

    public double getDbgDrawSize() {
        return m_dbgDrawSize;
    }

    /**
     * override the default global value of a parameter (such as ERP or CFM), optionally provide the
     * axis (0..5). If no axis is provided, it uses the default axis for this constraint.
     */
    public abstract void setParam(int num, double value, int axis);

    public final void setParam(int num, double value) {
        setParam(num, value, -1);
    }

    /** return the local value of parameter */
    public abstract double getParam(int num, int axis);

    public final double getParam(int num) {
        return getParam(num, -1);
    }

    public int calculateSerializeBufferSize() {
        return SIZEOF_btTypedConstraintDoubleData;
    }

    /** fills the dataBuffer and returns the struct name (and 0 on failure) */
    public String serialize(Object dataBuffer, Object serializer) {
        throw new UnsupportedOperationException("btSerializer not ported");
    }

    /** SIMD_FORCE_INLINE btScalar btAdjustAngleToLimits(...) (free function in the header) */
    public static double btAdjustAngleToLimits(
            double angleInRadians,
            double angleLowerLimitInRadians,
            double angleUpperLimitInRadians) {
        if (angleLowerLimitInRadians >= angleUpperLimitInRadians) {
            return angleInRadians;
        } else if (angleInRadians < angleLowerLimitInRadians) {
            double diffLo =
                    btScalar.btFabs(
                            btScalar.btNormalizeAngle(angleLowerLimitInRadians - angleInRadians));
            double diffHi =
                    btScalar.btFabs(
                            btScalar.btNormalizeAngle(angleUpperLimitInRadians - angleInRadians));
            return (diffLo < diffHi) ? angleInRadians : (angleInRadians + btScalar.SIMD_2_PI);
        } else if (angleInRadians > angleUpperLimitInRadians) {
            double diffHi =
                    btScalar.btFabs(
                            btScalar.btNormalizeAngle(angleInRadians - angleUpperLimitInRadians));
            double diffLo =
                    btScalar.btFabs(
                            btScalar.btNormalizeAngle(angleInRadians - angleLowerLimitInRadians));
            return (diffLo < diffHi) ? (angleInRadians - btScalar.SIMD_2_PI) : angleInRadians;
        } else {
            return angleInRadians;
        }
    }
}
