// Port of LinearMath/btTransformUtil.h class btConvexSeparatingDistanceUtil (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Conservative separating-distance tracker. Member vectors/quaternions are final and assigned via
 * {@code set} (C++ value members; uninitialised until the first init/update, zero here).
 */
public class btConvexSeparatingDistanceUtil {
    public final btQuaternion m_ornA = new btQuaternion();
    public final btQuaternion m_ornB = new btQuaternion();
    public final btVector3 m_posA = new btVector3();
    public final btVector3 m_posB = new btVector3();
    public final btVector3 m_separatingNormal = new btVector3();
    public double m_boundingRadiusA;
    public double m_boundingRadiusB;
    public double m_separatingDistance;

    public btConvexSeparatingDistanceUtil(double boundingRadiusA, double boundingRadiusB) {
        m_boundingRadiusA = boundingRadiusA;
        m_boundingRadiusB = boundingRadiusB;
        m_separatingDistance = 0.f;
    }

    public double getConservativeSeparatingDistance() {
        return m_separatingDistance;
    }

    public void updateSeparatingDistance(btTransform transA, btTransform transB) {
        btVector3 toPosA = transA.getOrigin();
        btVector3 toPosB = transB.getOrigin();
        btQuaternion toOrnA = transA.getRotation();
        btQuaternion toOrnB = transB.getRotation();

        if (m_separatingDistance > 0.f) {
            btVector3 linVelA = new btVector3(),
                    angVelA = new btVector3(),
                    linVelB = new btVector3(),
                    angVelB = new btVector3();
            btTransformUtil.calculateVelocityQuaternion(
                    m_posA, toPosA, m_ornA, toOrnA, 1., linVelA, angVelA);
            btTransformUtil.calculateVelocityQuaternion(
                    m_posB, toPosB, m_ornB, toOrnB, 1., linVelB, angVelB);
            double maxAngularProjectedVelocity =
                    angVelA.length() * m_boundingRadiusA + angVelB.length() * m_boundingRadiusB;
            btVector3 relLinVel = (linVelB.sub(linVelA));
            double relLinVelocLength = relLinVel.dot(m_separatingNormal);
            if (relLinVelocLength < 0.f) {
                relLinVelocLength = 0.f;
            }

            double projectedMotion = maxAngularProjectedVelocity + relLinVelocLength;
            m_separatingDistance -= projectedMotion;
        }

        m_posA.set(toPosA);
        m_posB.set(toPosB);
        m_ornA.set(toOrnA);
        m_ornB.set(toOrnB);
    }

    public void initSeparatingDistance(
            btVector3 separatingVector,
            double separatingDistance,
            btTransform transA,
            btTransform transB) {
        m_separatingDistance = separatingDistance;

        if (m_separatingDistance > 0.f) {
            m_separatingNormal.set(separatingVector);

            btVector3 toPosA = transA.getOrigin();
            btVector3 toPosB = transB.getOrigin();
            btQuaternion toOrnA = transA.getRotation();
            btQuaternion toOrnB = transB.getRotation();
            m_posA.set(toPosA);
            m_posB.set(toPosB);
            m_ornA.set(toOrnA);
            m_ornB.set(toOrnB);
        }
    }
}
