// Port of PZ glue BoneInfo (0x110 bytes; allocated in RagdollBuilder::setupDefaulSkeleton).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btTransform;

public class BoneInfo {
    /** +0 bone index */
    public int index;

    /** +8 absolute (model-space) bind transform. */
    public final btTransform m_transform = new BoneTransform();

    /** +0x88 local (parent-relative) bind transform. */
    public final btTransform m_localTransform = new BoneTransform();

    /** +0x108 parent (null for the root / -1 parent). */
    public BoneInfo parent;

    public BoneInfo() {}
}
