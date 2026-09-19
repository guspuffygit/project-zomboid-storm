// Port of PZ glue RagdollAnchor (0xc bytes; default zero).
package io.pzstorm.storm.bullet.pz;

public class RagdollAnchor {
    /** +0 SkeletonBone::Type */
    public int bone;

    /** +4 */
    public int bodyPart;

    /** +8 */
    public boolean reverse;

    /** +9 */
    public boolean original;

    /** +0xa */
    public boolean enabled;

    public RagdollAnchor() {}

    public RagdollAnchor set(RagdollAnchor o) {
        bone = o.bone;
        bodyPart = o.bodyPart;
        reverse = o.reverse;
        original = o.original;
        enabled = o.enabled;
        return this;
    }
}
