// Port of BulletDynamics/ConstraintSolver/btPoint2PointConstraint.cpp (Bullet 2.82)
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.dynamics.btRigidBody;
import io.pzstorm.storm.bullet.linearmath.btScalar;
import io.pzstorm.storm.bullet.linearmath.btTransform;
import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * point to point constraint between two rigidbodies each with a pivotpoint that descibes the
 * 'ballsocket' location in local space
 */
public class btPoint2PointConstraint extends btTypedConstraint {
    // enum btPoint2PointFlags
    public static final int BT_P2P_FLAGS_ERP = 1;
    public static final int BT_P2P_FLAGS_CFM = 2;

    /** sizeof(btPoint2PointConstraintDoubleData2) */
    public static final int SIZEOF_btPoint2PointConstraintDoubleData2 = 144;

    /** 3 orthogonal linear constraints */
    public final btJacobianEntry[] m_jac = {
        new btJacobianEntry(), new btJacobianEntry(), new btJacobianEntry()
    };

    public final btVector3 m_pivotInA = new btVector3();
    public final btVector3 m_pivotInB = new btVector3();

    public int m_flags;

    /** uninitialised in C++; only read when the matching BT_P2P_FLAGS_* bit is set */
    public double m_erp;

    public double m_cfm;

    /** for backwards compatibility during the transition to 'getInfo/getInfo2' */
    public boolean m_useSolveConstraintObsolete;

    public final btConstraintSetting m_setting = new btConstraintSetting();

    public btPoint2PointConstraint(
            btRigidBody rbA, btRigidBody rbB, btVector3 pivotInA, btVector3 pivotInB) {
        super(POINT2POINT_CONSTRAINT_TYPE, rbA, rbB);
        m_pivotInA.set(pivotInA);
        m_pivotInB.set(pivotInB);
        m_flags = 0;
        m_useSolveConstraintObsolete = false;
    }

    public btPoint2PointConstraint(btRigidBody rbA, btVector3 pivotInA) {
        super(POINT2POINT_CONSTRAINT_TYPE, rbA);
        m_pivotInA.set(pivotInA);
        m_pivotInB.set(rbA.getCenterOfMassTransform().transform(pivotInA));
        m_flags = 0;
        m_useSolveConstraintObsolete = false;
    }

    @Override
    public void buildJacobian() {
        /// we need it for both methods
        {
            m_appliedImpulse = 0.;

            btVector3 normal = new btVector3(0, 0, 0);

            for (int i = 0; i < 3; i++) {
                normal.set(i, 1);
                m_jac[i].set(
                        new btJacobianEntry(
                                m_rbA.getCenterOfMassTransform().getBasis().transpose(),
                                m_rbB.getCenterOfMassTransform().getBasis().transpose(),
                                m_rbA.getCenterOfMassTransform()
                                        .mul(m_pivotInA)
                                        .sub(m_rbA.getCenterOfMassPosition()),
                                m_rbB.getCenterOfMassTransform()
                                        .mul(m_pivotInB)
                                        .sub(m_rbB.getCenterOfMassPosition()),
                                normal,
                                m_rbA.getInvInertiaDiagLocal(),
                                m_rbA.getInvMass(),
                                m_rbB.getInvInertiaDiagLocal(),
                                m_rbB.getInvMass()));
                normal.set(i, 0);
            }
        }
    }

    @Override
    public void getInfo1(btConstraintInfo1 info) {
        getInfo1NonVirtual(info);
    }

    public void getInfo1NonVirtual(btConstraintInfo1 info) {
        if (m_useSolveConstraintObsolete) {
            info.m_numConstraintRows = 0;
            info.nub = 0;
        } else {
            info.m_numConstraintRows = 3;
            info.nub = 3;
        }
    }

    @Override
    public void getInfo2(btConstraintInfo2 info) {
        getInfo2NonVirtual(
                info, m_rbA.getCenterOfMassTransform(), m_rbB.getCenterOfMassTransform());
    }

    public void getInfo2NonVirtual(
            btConstraintInfo2 info, btTransform body0_trans, btTransform body1_trans) {
        // set jacobian
        info.m_J1linearAxis.set(0, 1);
        info.m_J1linearAxis.set(info.rowskip + 1, 1);
        info.m_J1linearAxis.set(2 * info.rowskip + 2, 1);

        btVector3 a1 = body0_trans.getBasis().mul(getPivotInA());
        {
            btVector3 angular0 = new btVector3();
            btVector3 angular1 = new btVector3();
            btVector3 angular2 = new btVector3();
            btVector3 a1neg = a1.negate();
            a1neg.getSkewSymmetricMatrix(angular0, angular1, angular2);
            info.m_J1angularAxis.setVec(0, angular0);
            info.m_J1angularAxis.setVec(info.rowskip, angular1);
            info.m_J1angularAxis.setVec(2 * info.rowskip, angular2);
        }

        info.m_J2linearAxis.set(0, -1);
        info.m_J2linearAxis.set(info.rowskip + 1, -1);
        info.m_J2linearAxis.set(2 * info.rowskip + 2, -1);

        btVector3 a2 = body1_trans.getBasis().mul(getPivotInB());

        {
            btVector3 angular0 = new btVector3();
            btVector3 angular1 = new btVector3();
            btVector3 angular2 = new btVector3();
            a2.getSkewSymmetricMatrix(angular0, angular1, angular2);
            info.m_J2angularAxis.setVec(0, angular0);
            info.m_J2angularAxis.setVec(info.rowskip, angular1);
            info.m_J2angularAxis.setVec(2 * info.rowskip, angular2);
        }

        // set right hand side
        double currERP = (m_flags & BT_P2P_FLAGS_ERP) != 0 ? m_erp : info.erp;
        double k = info.fps * currERP;
        int j;
        for (j = 0; j < 3; j++) {
            info.m_constraintError.set(
                    j * info.rowskip,
                    k
                            * (a2.get(j)
                                    + body1_trans.getOrigin().get(j)
                                    - a1.get(j)
                                    - body0_trans.getOrigin().get(j)));
        }
        if ((m_flags & BT_P2P_FLAGS_CFM) != 0) {
            for (j = 0; j < 3; j++) {
                info.cfm.set(j * info.rowskip, m_cfm);
            }
        }

        double impulseClamp = m_setting.m_impulseClamp; //
        for (j = 0; j < 3; j++) {
            if (m_setting.m_impulseClamp > 0) {
                info.m_lowerLimit.set(j * info.rowskip, -impulseClamp);
                info.m_upperLimit.set(j * info.rowskip, impulseClamp);
            }
        }
        info.m_damping = m_setting.m_damping;
    }

    public void updateRHS(double timeStep) {}

    public void setPivotA(btVector3 pivotA) {
        m_pivotInA.set(pivotA);
    }

    public void setPivotB(btVector3 pivotB) {
        m_pivotInB.set(pivotB);
    }

    public btVector3 getPivotInA() {
        return m_pivotInA;
    }

    public btVector3 getPivotInB() {
        return m_pivotInB;
    }

    /**
     * override the default global value of a parameter (such as ERP or CFM), optionally provide the
     * axis (0..5). If no axis is provided, it uses the default axis for this constraint.
     */
    @Override
    public void setParam(int num, double value, int axis) {
        if (axis != -1) {
            // btAssertConstrParams(0);
        } else {
            switch (num) {
                case BT_CONSTRAINT_ERP:
                case BT_CONSTRAINT_STOP_ERP:
                    m_erp = value;
                    m_flags |= BT_P2P_FLAGS_ERP;
                    break;
                case BT_CONSTRAINT_CFM:
                case BT_CONSTRAINT_STOP_CFM:
                    m_cfm = value;
                    m_flags |= BT_P2P_FLAGS_CFM;
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        }
    }

    /** return the local value of parameter */
    @Override
    public double getParam(int num, int axis) {
        double retVal = btScalar.SIMD_INFINITY;
        if (axis != -1) {
            // btAssertConstrParams(0);
        } else {
            switch (num) {
                case BT_CONSTRAINT_ERP:
                case BT_CONSTRAINT_STOP_ERP:
                    retVal = m_erp;
                    break;
                case BT_CONSTRAINT_CFM:
                case BT_CONSTRAINT_STOP_CFM:
                    retVal = m_cfm;
                    break;
                default:
                    // btAssertConstrParams(0);
            }
        }
        return retVal;
    }

    @Override
    public int calculateSerializeBufferSize() {
        return SIZEOF_btPoint2PointConstraintDoubleData2;
    }
}
