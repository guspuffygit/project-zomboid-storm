// Port of LinearMath/btQuaternion.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, scalar branch
// (BT_EULER_DEFAULT_ZYX not defined).
package io.pzstorm.storm.bullet.linearmath;

/**
 * btQuaternion (x, y, z, w). Mapping:
 *
 * <pre>
 * q1 * q2            q1.mul(q2)               (new)
 * q *= q2            q.mulLocal(q2)           (returns this)
 * q * s / q *= s     q.mul(s) / q.mulLocal(s)
 * q / s / q /= s     q.div(s) / q.divLocal(s) (both multiply by 1.0/s)
 * q + q2 / q += q2   q.add(q2) / q.addLocal(q2)
 * q - q2 / q -= q2   q.sub(q2) / q.subLocal(q2)
 * -q                 q.negate()
 * q * v  (btVector3) btQuaternion.mul(q, v)
 * v * q              btQuaternion.mul(v, q)
 * quatRotate(q, v)   btQuaternion.quatRotate(q, v)
 * shortestArcQuat    btQuaternion.shortestArcQuat(v0, v1)
 * a = b              a.set(b)
 * </pre>
 *
 * sin/cos pairs with the same argument (setRotation, setEuler, setEulerZYX) are one sincos call in
 * the binary; ported through {@link btScalar#btSinCos}.
 */
public class btQuaternion extends btQuadWord {
    private static final btQuaternion identityQuat = new btQuaternion(0., 0., 0., 1.);

    /** C++ leaves storage uninitialised; Java zeroes it. */
    public btQuaternion() {}

    public btQuaternion(double _x, double _y, double _z, double _w) {
        super(_x, _y, _z, _w);
    }

    public btQuaternion(btQuaternion other) {
        super(other.x, other.y, other.z, other.w);
    }

    public btQuaternion(btVector3 _axis, double _angle) {
        setRotation(_axis, _angle);
    }

    /** btQuaternion(yaw, pitch, roll) -> setEuler (BT_EULER_DEFAULT_ZYX not defined). */
    public btQuaternion(double yaw, double pitch, double roll) {
        setEuler(yaw, pitch, roll);
    }

    /** C++ {@code a = b}. */
    public btQuaternion set(btQuaternion other) {
        x = other.x;
        y = other.y;
        z = other.z;
        w = other.w;
        return this;
    }

    public void setRotation(btVector3 axis, double _angle) {
        double d = axis.length();
        double[] sc = new double[2];
        btScalar.btSinCos(_angle * 0.5, sc);
        double s = sc[0] / d;
        setValue(axis.x() * s, axis.y() * s, axis.z() * s, sc[1]);
    }

    public void setEuler(double yaw, double pitch, double roll) {
        double halfYaw = yaw * 0.5;
        double halfPitch = pitch * 0.5;
        double halfRoll = roll * 0.5;
        double[] sc = new double[2];
        btScalar.btSinCos(halfYaw, sc);
        double cosYaw = sc[1];
        double sinYaw = sc[0];
        btScalar.btSinCos(halfPitch, sc);
        double cosPitch = sc[1];
        double sinPitch = sc[0];
        btScalar.btSinCos(halfRoll, sc);
        double cosRoll = sc[1];
        double sinRoll = sc[0];
        setValue(
                cosRoll * sinPitch * cosYaw + sinRoll * cosPitch * sinYaw,
                cosRoll * cosPitch * sinYaw - sinRoll * sinPitch * cosYaw,
                sinRoll * cosPitch * cosYaw - cosRoll * sinPitch * sinYaw,
                cosRoll * cosPitch * cosYaw + sinRoll * sinPitch * sinYaw);
    }

    public void setEulerZYX(double yaw, double pitch, double roll) {
        double halfYaw = yaw * 0.5;
        double halfPitch = pitch * 0.5;
        double halfRoll = roll * 0.5;
        double[] sc = new double[2];
        btScalar.btSinCos(halfYaw, sc);
        double cosYaw = sc[1];
        double sinYaw = sc[0];
        btScalar.btSinCos(halfPitch, sc);
        double cosPitch = sc[1];
        double sinPitch = sc[0];
        btScalar.btSinCos(halfRoll, sc);
        double cosRoll = sc[1];
        double sinRoll = sc[0];
        setValue(
                sinRoll * cosPitch * cosYaw - cosRoll * sinPitch * sinYaw,
                cosRoll * sinPitch * cosYaw + sinRoll * cosPitch * sinYaw,
                cosRoll * cosPitch * sinYaw - sinRoll * sinPitch * cosYaw,
                cosRoll * cosPitch * cosYaw + sinRoll * sinPitch * sinYaw);
    }

    /** {@code operator+=} */
    public btQuaternion addLocal(btQuaternion q) {
        x += q.x;
        y += q.y;
        z += q.z;
        w += q.w;
        return this;
    }

    /** {@code operator-=} */
    public btQuaternion subLocal(btQuaternion q) {
        x -= q.x;
        y -= q.y;
        z -= q.z;
        w -= q.w;
        return this;
    }

    /** {@code operator*=(const btScalar&)} */
    public btQuaternion mulLocal(double s) {
        x *= s;
        y *= s;
        z *= s;
        w *= s;
        return this;
    }

    /** {@code operator*=(const btQuaternion&)} */
    public btQuaternion mulLocal(btQuaternion q) {
        setValue(
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y + y * q.w + z * q.x - x * q.z,
                w * q.z + z * q.w + x * q.y - y * q.x,
                w * q.w - x * q.x - y * q.y - z * q.z);
        return this;
    }

    public double dot(btQuaternion q) {
        return x * q.x + y * q.y + z * q.z + w * q.w;
    }

    public double length2() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(length2());
    }

    /** {@code return *this /= length();} */
    public btQuaternion normalize() {
        return divLocal(length());
    }

    /** {@code operator*(const btScalar&)} */
    public btQuaternion mul(double s) {
        return new btQuaternion(x * s, y * s, z * s, w * s);
    }

    /** {@code operator/}: {@code *this * (btScalar(1.0) / s)} */
    public btQuaternion div(double s) {
        return mul(1.0 / s);
    }

    /** {@code operator/=}: {@code *this *= btScalar(1.0) / s} */
    public btQuaternion divLocal(double s) {
        return mulLocal(1.0 / s);
    }

    /** {@code return *this / length();} */
    public btQuaternion normalized() {
        return div(length());
    }

    public double angle(btQuaternion q) {
        double s = Math.sqrt(length2() * q.length2());
        return btScalar.btAcos(dot(q) / s);
    }

    public double angleShortestPath(btQuaternion q) {
        double s = Math.sqrt(length2() * q.length2());
        if (dot(q) < 0) {
            return btScalar.btAcos(dot(q.negate()) / s) * 2.0;
        } else {
            return btScalar.btAcos(dot(q) / s) * 2.0;
        }
    }

    public double getAngle() {
        double s = 2. * btScalar.btAcos(w);
        return s;
    }

    public double getAngleShortestPath() {
        double s;
        if (dot(this) < 0) {
            s = 2. * btScalar.btAcos(w);
        } else {
            s = 2. * btScalar.btAcos(-w);
        }
        return s;
    }

    public btVector3 getAxis() {
        double s_squared = 1.0 - w * w;
        if (s_squared < 10. * btScalar.SIMD_EPSILON) {
            return new btVector3(1.0, 0.0, 0.0);
        }
        double s = 1.0 / Math.sqrt(s_squared);
        return new btVector3(x * s, y * s, z * s);
    }

    public btQuaternion inverse() {
        return new btQuaternion(-x, -y, -z, w);
    }

    /** {@code operator+(q2)} */
    public btQuaternion add(btQuaternion q2) {
        return new btQuaternion(x + q2.x, y + q2.y, z + q2.z, w + q2.w);
    }

    /** {@code operator-(q2)} */
    public btQuaternion sub(btQuaternion q2) {
        return new btQuaternion(x - q2.x, y - q2.y, z - q2.z, w - q2.w);
    }

    /** unary {@code operator-()} */
    public btQuaternion negate() {
        return new btQuaternion(-x, -y, -z, -w);
    }

    public btQuaternion farthest(btQuaternion qd) {
        btQuaternion diff = this.sub(qd);
        btQuaternion sum = this.add(qd);
        if (diff.dot(diff) > sum.dot(sum)) {
            return new btQuaternion(qd);
        }
        return qd.negate();
    }

    public btQuaternion nearest(btQuaternion qd) {
        btQuaternion diff = this.sub(qd);
        btQuaternion sum = this.add(qd);
        if (diff.dot(diff) < sum.dot(sum)) {
            return new btQuaternion(qd);
        }
        return qd.negate();
    }

    public btQuaternion slerp(btQuaternion q, double t) {
        double magnitude = Math.sqrt(length2() * q.length2());
        double product = dot(q) / magnitude;
        if (Math.abs(product) < 1.0) {
            final double sign = (product < 0) ? -1.0 : 1.0;
            final double theta = btScalar.btAcos(sign * product);
            final double s1 = btScalar.btSin(sign * t * theta);
            final double d = 1.0 / btScalar.btSin(theta);
            final double s0 = btScalar.btSin((1.0 - t) * theta);
            return new btQuaternion(
                    (x * s0 + q.x * s1) * d,
                    (y * s0 + q.y * s1) * d,
                    (z * s0 + q.z * s1) * d,
                    (w * s0 + q.w * s1) * d);
        } else {
            return new btQuaternion(this);
        }
    }

    /** Returns the shared identity constant (C++ returns a const reference; do not mutate). */
    public static btQuaternion getIdentity() {
        return identityQuat;
    }

    public double getW() {
        return w;
    }

    /** {@code operator*(q1, q2)} */
    public btQuaternion mul(btQuaternion q2) {
        btQuaternion q1 = this;
        return new btQuaternion(
                q1.w() * q2.x() + q1.x() * q2.w() + q1.y() * q2.z() - q1.z() * q2.y(),
                q1.w() * q2.y() + q1.y() * q2.w() + q1.z() * q2.x() - q1.x() * q2.z(),
                q1.w() * q2.z() + q1.z() * q2.w() + q1.x() * q2.y() - q1.y() * q2.x(),
                q1.w() * q2.w() - q1.x() * q2.x() - q1.y() * q2.y() - q1.z() * q2.z());
    }

    /** {@code operator*(const btQuaternion& q, const btVector3& w)} */
    public static btQuaternion mul(btQuaternion q, btVector3 w) {
        return new btQuaternion(
                q.w() * w.x() + q.y() * w.z() - q.z() * w.y(),
                q.w() * w.y() + q.z() * w.x() - q.x() * w.z(),
                q.w() * w.z() + q.x() * w.y() - q.y() * w.x(),
                -q.x() * w.x() - q.y() * w.y() - q.z() * w.z());
    }

    /** {@code operator*(const btVector3& w, const btQuaternion& q)} */
    public static btQuaternion mul(btVector3 w, btQuaternion q) {
        return new btQuaternion(
                +w.x() * q.w() + w.y() * q.z() - w.z() * q.y(),
                +w.y() * q.w() + w.z() * q.x() - w.x() * q.z(),
                +w.z() * q.w() + w.x() * q.y() - w.y() * q.x(),
                -w.x() * q.x() - w.y() * q.y() - w.z() * q.z());
    }

    // ---- free functions ----

    public static double dot(btQuaternion q1, btQuaternion q2) {
        return q1.dot(q2);
    }

    public static double length(btQuaternion q) {
        return q.length();
    }

    public static double btAngle(btQuaternion q1, btQuaternion q2) {
        return q1.angle(q2);
    }

    public static btQuaternion inverse(btQuaternion q) {
        return q.inverse();
    }

    public static btQuaternion slerp(btQuaternion q1, btQuaternion q2, double t) {
        return q1.slerp(q2, t);
    }

    public static btVector3 quatRotate(btQuaternion rotation, btVector3 v) {
        btQuaternion q = mul(rotation, v);
        q.mulLocal(rotation.inverse());
        return new btVector3(q.getX(), q.getY(), q.getZ());
    }

    public static btQuaternion shortestArcQuat(btVector3 v0, btVector3 v1) {
        btVector3 c = v0.cross(v1);
        double d = v0.dot(v1);
        if (d < -1.0 + btScalar.SIMD_EPSILON) {
            btVector3 n = new btVector3();
            btVector3 unused = new btVector3();
            btVector3.btPlaneSpace1(v0, n, unused);
            return new btQuaternion(n.x(), n.y(), n.z(), 0.0);
        }
        double s = Math.sqrt((1.0 + d) * 2.0);
        double rs = 1.0 / s;
        return new btQuaternion(c.getX() * rs, c.getY() * rs, c.getZ() * rs, s * 0.5);
    }

    public static btQuaternion shortestArcQuatNormalize2(btVector3 v0, btVector3 v1) {
        v0.normalize();
        v1.normalize();
        return shortestArcQuat(v0, v1);
    }

    @Override
    public String toString() {
        return "btQuaternion(" + x + ", " + y + ", " + z + ", " + w + ")";
    }
}
