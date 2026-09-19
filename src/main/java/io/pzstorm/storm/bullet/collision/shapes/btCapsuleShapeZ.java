// Port of btCapsuleShape.cpp (Bullet 2.82) class btCapsuleShapeZ
package io.pzstorm.storm.bullet.collision.shapes;

public class btCapsuleShapeZ extends btCapsuleShape {
    public btCapsuleShapeZ(double radius, double height) {
        super();
        m_upAxis = 2;
        m_implicitShapeDimensions.setValue(radius, radius, 0.5 * height);
    }

    @Override
    public String getName() {
        return "CapsuleZ";
    }
}
