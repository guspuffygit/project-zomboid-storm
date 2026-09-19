// Port of PZ glue RagdollBodyDynamics (0x20 bytes).
package io.pzstorm.storm.bullet.pz;

/**
 * The C++ struct has no initialiser (vector resize value-initialises it to zero), so zero defaults
 * match for the script vectors.
 */
public class RagdollBodyDynamics {
    /** +0 */
    public int part;

    /** +4 */
    public float linearDamping;

    /** +8 */
    public float angularDamping;

    /** +0xc */
    public float deactivationTime;

    /** +0x10 */
    public float linearSleepingThreshold;

    /** +0x14 */
    public float angularSleepingThreshold;

    /** +0x18 */
    public float friction;

    /** +0x1c */
    public float rollingFriction;

    public RagdollBodyDynamics() {}

    public RagdollBodyDynamics set(RagdollBodyDynamics o) {
        part = o.part;
        linearDamping = o.linearDamping;
        angularDamping = o.angularDamping;
        deactivationTime = o.deactivationTime;
        linearSleepingThreshold = o.linearSleepingThreshold;
        angularSleepingThreshold = o.angularSleepingThreshold;
        friction = o.friction;
        rollingFriction = o.rollingFriction;
        return this;
    }
}
