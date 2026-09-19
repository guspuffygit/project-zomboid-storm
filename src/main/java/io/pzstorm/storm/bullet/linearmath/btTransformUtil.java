// Port of LinearMath/btTransformUtil.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION,
// QUATERNION_DERIVATIVE
// not defined.
package io.pzstorm.storm.bullet.linearmath;

/**
 * btTransformUtil static helpers plus the free function {@link #btAabbSupport}. See also {@link
 * btConvexSeparatingDistanceUtil} (same header).
 *
 * <p>C++ out-parameters: {@code btVector3&} outputs are written in place via {@code set}; {@code
 * btScalar& angle} is {@code double[] angle} (element 0).
 *
 * <p>{@code #define ANGULAR_MOTION_THRESHOLD btScalar(0.5)*SIMD_HALF_PI} is unparenthesised; both
 * uses ({@code x > THRESH} and {@code THRESH / timeStep}) evaluate left-to-right as written here.
 * integrateTransform calls sin and cos separately in the binary (@0x93b10: cos@plt, sin@plt; no
 * sincos) because their arguments are spelled differently.
 */
public final class btTransformUtil {
    private btTransformUtil() {}

    public static final double ANGULAR_MOTION_THRESHOLD = 0.5 * btScalar.SIMD_HALF_PI;

    public static btVector3 btAabbSupport(btVector3 halfExtents, btVector3 supportDir) {
        return new btVector3(
                supportDir.x() < 0.0 ? -halfExtents.x() : halfExtents.x(),
                supportDir.y() < 0.0 ? -halfExtents.y() : halfExtents.y(),
                supportDir.z() < 0.0 ? -halfExtents.z() : halfExtents.z());
    }

    public static void integrateTransform(
            btTransform curTrans,
            btVector3 linvel,
            btVector3 angvel,
            double timeStep,
            btTransform predictedTransform) {
        predictedTransform.setOrigin(curTrans.getOrigin().add(linvel.mul(timeStep)));
        btVector3 axis;
        double fAngle = angvel.length();
        if (fAngle * timeStep > 0.5 * btScalar.SIMD_HALF_PI) {
            fAngle = 0.5 * btScalar.SIMD_HALF_PI / timeStep;
        }
        if (fAngle < 0.001) {
            axis =
                    angvel.mul(
                            0.5 * timeStep
                                    - (timeStep * timeStep * timeStep)
                                            * (0.020833333333)
                                            * fAngle
                                            * fAngle);
        } else {
            axis = angvel.mul(btScalar.btSin(0.5 * fAngle * timeStep) / fAngle);
        }
        btQuaternion dorn =
                new btQuaternion(
                        axis.x(), axis.y(), axis.z(), btScalar.btCos(fAngle * timeStep * 0.5));
        btQuaternion orn0 = curTrans.getRotation();
        btQuaternion predictedOrn = dorn.mul(orn0);
        predictedOrn.normalize();
        predictedTransform.setRotation(predictedOrn);
    }

    public static void calculateVelocityQuaternion(
            btVector3 pos0,
            btVector3 pos1,
            btQuaternion orn0,
            btQuaternion orn1,
            double timeStep,
            btVector3 linVel,
            btVector3 angVel) {
        linVel.set(pos1.sub(pos0).div(timeStep));
        btVector3 axis = new btVector3();
        double[] angle = new double[1];
        if (!orn0.equalsValue(orn1)) {
            calculateDiffAxisAngleQuaternion(orn0, orn1, axis, angle);
            angVel.set(axis.mul(angle[0]).div(timeStep));
        } else {
            angVel.setValue(0, 0, 0);
        }
    }

    public static void calculateDiffAxisAngleQuaternion(
            btQuaternion orn0, btQuaternion orn1a, btVector3 axis, double[] angle) {
        btQuaternion orn1 = orn0.nearest(orn1a);
        btQuaternion dorn = orn1.mul(orn0.inverse());
        angle[0] = dorn.getAngle();
        axis.set(new btVector3(dorn.x(), dorn.y(), dorn.z()));
        axis.set(3, 0.);
        double len = axis.length2();
        if (len < btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON) {
            axis.set(new btVector3(1., 0., 0.));
        } else {
            axis.divLocal(Math.sqrt(len));
        }
    }

    public static void calculateVelocity(
            btTransform transform0,
            btTransform transform1,
            double timeStep,
            btVector3 linVel,
            btVector3 angVel) {
        linVel.set(transform1.getOrigin().sub(transform0.getOrigin()).div(timeStep));
        btVector3 axis = new btVector3();
        double[] angle = new double[1];
        calculateDiffAxisAngle(transform0, transform1, axis, angle);
        angVel.set(axis.mul(angle[0]).div(timeStep));
    }

    public static void calculateDiffAxisAngle(
            btTransform transform0, btTransform transform1, btVector3 axis, double[] angle) {
        btMatrix3x3 dmat = transform1.getBasis().mul(transform0.getBasis().inverse());
        btQuaternion dorn = new btQuaternion();
        dmat.getRotation(dorn);
        dorn.normalize();
        angle[0] = dorn.getAngle();
        axis.set(new btVector3(dorn.x(), dorn.y(), dorn.z()));
        axis.set(3, 0.);
        double len = axis.length2();
        if (len < btScalar.SIMD_EPSILON * btScalar.SIMD_EPSILON) {
            axis.set(new btVector3(1., 0., 0.));
        } else {
            axis.divLocal(Math.sqrt(len));
        }
    }
}
