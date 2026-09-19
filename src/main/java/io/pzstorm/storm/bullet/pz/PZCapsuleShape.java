// Port of PZ glue PZCapsuleShape (RagdollBuilder.cpp; vtable @002494a8, dtors @00185870/@00185890).
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.collision.shapes.btCapsuleShape;

/**
 * A plain btCapsuleShape subclass (0x70 bytes). The binary only defines the two destructors; every
 * other virtual is inherited from btCapsuleShape.
 */
public class PZCapsuleShape extends btCapsuleShape {

    public PZCapsuleShape(double radius, double height) {
        super(radius, height);
    }
}
