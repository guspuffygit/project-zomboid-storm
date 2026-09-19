// Port of PZ glue BodyPartInfo (0x40 bytes).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btVector3;

public class BodyPartInfo {
    /** +0 */
    public int part = -1;

    /** +4 */
    public boolean calculateLength = true;

    /** +8 */
    public float radius = 0.0f;

    /** +0xc */
    public float height = 0.0f;

    /** +0x10 */
    public float gap = 0.1f;

    /** +0x14 0 = capsule, 1 = box, 2 = sphere */
    public int shape = 0;

    /** +0x18 */
    public double mass = 1.0;

    /** +0x20 */
    public final btVector3 offset = new btVector3(0, 0, 0);

    public BodyPartInfo() {}

    public BodyPartInfo set(BodyPartInfo o) {
        part = o.part;
        calculateLength = o.calculateLength;
        radius = o.radius;
        height = o.height;
        gap = o.gap;
        shape = o.shape;
        mass = o.mass;
        offset.set(o.offset);
        return this;
    }
}
