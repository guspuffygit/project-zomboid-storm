// Port of PZ glue RagdollConstraint (0xd0 bytes; default all zero).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public class RagdollConstraint {
    /** +0 */
    public int joint;

    /** +4 btTypedConstraintType (4 = hinge, 5 = cone-twist) */
    public int constraintType;

    /** +8 */
    public int constraintPartA;

    /** +0xc */
    public int constraintPartB;

    /** +0x10 euler (z, y, x read as doubles +0x20,+0x18,+0x10 in loadConstraint) */
    public final btVector3 constraintAxisA = new btVector3(0, 0, 0);

    /** +0x30 */
    public final btVector3 constraintAxisB = new btVector3(0, 0, 0);

    /** +0x50 */
    public final btVector3 constraintPositionOffsetA = new btVector3(0, 0, 0);

    /** +0x70 */
    public final btVector3 constraintPositionOffsetB = new btVector3(0, 0, 0);

    /** +0x90 */
    public final btVector3 constraintLimit = new btVector3(0, 0, 0);

    /** +0xb0 */
    public final btVector3 constraintLimitExtended = new btVector3(0, 0, 0);

    public RagdollConstraint() {}

    public RagdollConstraint set(RagdollConstraint o) {
        joint = o.joint;
        constraintType = o.constraintType;
        constraintPartA = o.constraintPartA;
        constraintPartB = o.constraintPartB;
        constraintAxisA.set(o.constraintAxisA);
        constraintAxisB.set(o.constraintAxisB);
        constraintPositionOffsetA.set(o.constraintPositionOffsetA);
        constraintPositionOffsetB.set(o.constraintPositionOffsetB);
        constraintLimit.set(o.constraintLimit);
        constraintLimitExtended.set(o.constraintLimitExtended);
        return this;
    }
}
