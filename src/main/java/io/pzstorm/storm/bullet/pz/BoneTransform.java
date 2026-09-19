// Port of PZ glue BoneTransform (BoneTransform.cpp; 0x80 bytes = one btTransform).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.linearmath.btTransform;

/** A btTransform whose default constructor is identity (vector default-append writes identity). */
public class BoneTransform extends btTransform {

    public BoneTransform() {
        setIdentity();
    }

    public BoneTransform(btTransform other) {
        super(other);
    }
}
