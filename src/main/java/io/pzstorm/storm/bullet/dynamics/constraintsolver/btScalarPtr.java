// Java helper (no C++ counterpart): a btScalar* into an array of btSolverConstraint rows.
package io.pzstorm.storm.bullet.dynamics.constraintsolver;

import io.pzstorm.storm.bullet.linearmath.btVector3;

/**
 * Emulates a {@code btScalar*} that points into contiguous btSolverConstraint storage, as used by
 * btTypedConstraint::btConstraintInfo2 ({@code m_J1linearAxis = &row->m_contactNormal1.x}, {@code
 * cfm = &row->m_cfm}, ..., indexed with {@code srow = row * rowskip + k}). Element {@code i}
 * addresses scalar slot {@code baseSlot + i} counted from the start of {@code rows[baseRow]}, with
 * {@link btSolverConstraint#ROWSKIP} (36) scalars per row, exactly like the C++ pointer arithmetic.
 */
public final class btScalarPtr {
    public final Object[] rows;
    public final int baseRow;
    public final int baseSlot;

    public btScalarPtr(Object[] rows, int baseRow, int baseSlot) {
        this.rows = rows;
        this.baseRow = baseRow;
        this.baseSlot = baseSlot;
    }

    private btSolverConstraint row(int idx) {
        return (btSolverConstraint) rows[baseRow + Math.floorDiv(idx, btSolverConstraint.ROWSKIP)];
    }

    /** {@code p[i]} */
    public double get(int i) {
        int idx = baseSlot + i;
        return row(idx).getSlot(Math.floorMod(idx, btSolverConstraint.ROWSKIP));
    }

    /** {@code p[i] = v} */
    public void set(int i, double v) {
        int idx = baseSlot + i;
        row(idx).setSlot(Math.floorMod(idx, btSolverConstraint.ROWSKIP), v);
    }

    /** {@code p + n} */
    public btScalarPtr offset(int n) {
        int idx = baseSlot + n;
        return new btScalarPtr(
                rows,
                baseRow + Math.floorDiv(idx, btSolverConstraint.ROWSKIP),
                Math.floorMod(idx, btSolverConstraint.ROWSKIP));
    }

    /** {@code *(btVector3*)(p + i) = v} (copies all four lanes, including w). */
    public void setVec(int i, btVector3 v) {
        set(i, v.x);
        set(i + 1, v.y);
        set(i + 2, v.z);
        set(i + 3, v.w);
    }

    /** {@code *(btVector3*)(p + i)} read as a new btVector3 (x, y, z, w). */
    public btVector3 getVec(int i) {
        btVector3 r = new btVector3(get(i), get(i + 1), get(i + 2));
        r.w = get(i + 3);
        return r;
    }
}
