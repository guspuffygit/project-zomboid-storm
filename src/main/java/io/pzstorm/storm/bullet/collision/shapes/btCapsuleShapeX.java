// Port of btCapsuleShape.cpp (Bullet 2.82) class btCapsuleShapeX
package io.pzstorm.storm.bullet.collision.shapes;

public class btCapsuleShapeX extends btCapsuleShape {
    public btCapsuleShapeX(double radius, double height) {
        super();
        m_upAxis = 0;
        m_implicitShapeDimensions.setValue(0.5 * height, radius, radius);
    }

    @Override
    public String getName() {
        return "CapsuleX";
    }
}
