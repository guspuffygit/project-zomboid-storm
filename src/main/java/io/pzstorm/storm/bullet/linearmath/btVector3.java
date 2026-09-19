// Port of LinearMath/btVector3.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, scalar (non-SSE) branch.
package io.pzstorm.storm.bullet.linearmath;

/**
 * btVector3: {@code btScalar m_floats[4]} = {@code x, y, z, w}.
 *
 * <p>w semantics (exactly as C++): the (x,y,z) constructor and {@link #setValue} set w=0; copy
 * constructor and {@link #set(btVector3)} copy w; {@code += -= *= /=} leave w untouched; every
 * binary operator returns a new vector with w=0; {@code operator==} ({@link #equalsValue}) compares
 * all four components; {@link #setMax}/{@link #setMin} also apply to w. The C++ default constructor
 * leaves m_floats uninitialised; here it is zero.
 *
 * <p>Do NOT use {@link Object#equals}/{@link Object#hashCode} (identity) for value comparison.
 *
 * <h2>C++ operator / free-function mapping</h2>
 *
 * <pre>
 * C++                                    Java
 * -------------------------------------  ------------------------------------------
 * btVector3 a = b;  (copy-construct)     btVector3 a = new btVector3(b);
 * a = b;                                 a.set(b);
 * v[i]  (read) / v[i] = s                v.get(i) / v.set(i, s)
 * a + b                                  a.add(b)            (new, w=0)
 * a - b                                  a.sub(b)            (new, w=0)
 * -a                                     a.negate()          (new, w=0)
 * a * s   and   s * a                    a.mul(s)            (new, w=0)
 * a * b   (elementwise)                  a.mul(b)            (new, w=0)
 * a / s   (= a * (1.0/s))                a.div(s)            (new, w=0)
 * a / b   (elementwise)                  a.div(b)            (new, w=0)
 * a += b / a -= b                        a.addLocal(b) / a.subLocal(b)   (returns this)
 * a *= s / a *= b                        a.mulLocal(s) / a.mulLocal(b)
 * a /= s  (= a *= 1.0/s)                 a.divLocal(s)
 * a == b / a != b                        a.equalsValue(b) / !a.equalsValue(b)
 * m * v   (btMatrix3x3)                  m.mul(v)
 * v * m   (btMatrix3x3)                  btMatrix3x3.mul(v, m)
 * t * v / t(v)  (btTransform)            t.transform(v)
 * q * v , v * q (btQuaternion)           btQuaternion.mul(q, v) / btQuaternion.mul(v, q)
 * quatRotate(q, v)                       btQuaternion.quatRotate(q, v)
 * btDot(a,b) btCross(a,b) btTriple       btVector3.btDot / btCross / btTriple
 * btDistance(a,b) btDistance2 btAngle    btVector3.btDistance / btDistance2 / btAngle
 * lerp(a,b,t)                            btVector3.lerp(a, b, t)
 * btPlaneSpace1(n, p, q)                 btVector3.btPlaneSpace1(n, p, q)
 * a.maxDot(arr, n, dotOut)               a.maxDot(arr, n, double[1] dotOut)
 * </pre>
 *
 * <p>Floating-point expressions follow the 2.82 inline bodies exactly. GCC 10 -O3 (no -ffast-math)
 * does not reassociate or contract them (verified against the disassembly, e.g.
 * btTransformUtil::integrateTransform @0x93b10: same operation order; only mulpd/addpd pairing).
 */
public class btVector3 {
    public double x, y, z, w;

    /** C++ leaves the storage uninitialised; Java zeroes it. */
    public btVector3() {}

    public btVector3(double _x, double _y, double _z) {
        x = _x;
        y = _y;
        z = _z;
        w = 0.0;
    }

    /** Implicit C++ copy constructor (copies all four floats). */
    public btVector3(btVector3 other) {
        x = other.x;
        y = other.y;
        z = other.z;
        w = other.w;
    }

    /** C++ {@code a = b} (copies all four floats). */
    public btVector3 set(btVector3 other) {
        x = other.x;
        y = other.y;
        z = other.z;
        w = other.w;
        return this;
    }

    // ---- element access ----

    /** {@code m_floats[i]} */
    public double get(int i) {
        switch (i) {
            case 0:
                return x;
            case 1:
                return y;
            case 2:
                return z;
            case 3:
                return w;
            default:
                throw new IndexOutOfBoundsException(i);
        }
    }

    /** {@code m_floats[i] = v} */
    public void set(int i, double v) {
        switch (i) {
            case 0:
                x = v;
                break;
            case 1:
                y = v;
                break;
            case 2:
                z = v;
                break;
            case 3:
                w = v;
                break;
            default:
                throw new IndexOutOfBoundsException(i);
        }
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public void setX(double _x) {
        x = _x;
    }

    public void setY(double _y) {
        y = _y;
    }

    public void setZ(double _z) {
        z = _z;
    }

    public void setW(double _w) {
        w = _w;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double w() {
        return w;
    }

    // ---- in-place operators ----

    /** {@code operator+=} */
    public btVector3 addLocal(btVector3 v) {
        x += v.x;
        y += v.y;
        z += v.z;
        return this;
    }

    /** {@code operator-=} */
    public btVector3 subLocal(btVector3 v) {
        x -= v.x;
        y -= v.y;
        z -= v.z;
        return this;
    }

    /** {@code operator*=(const btScalar&)} */
    public btVector3 mulLocal(double s) {
        x *= s;
        y *= s;
        z *= s;
        return this;
    }

    /** {@code operator*=(const btVector3&)} (elementwise) */
    public btVector3 mulLocal(btVector3 v) {
        x *= v.x;
        y *= v.y;
        z *= v.z;
        return this;
    }

    /** {@code operator/=(const btScalar&)}: {@code *this *= btScalar(1.0) / s}. */
    public btVector3 divLocal(double s) {
        return mulLocal(1.0 / s);
    }

    // ---- value operators (new object, w = 0) ----

    /** {@code operator+(v1, v2)} */
    public btVector3 add(btVector3 v2) {
        return new btVector3(x + v2.x, y + v2.y, z + v2.z);
    }

    /** {@code operator-(v1, v2)} */
    public btVector3 sub(btVector3 v2) {
        return new btVector3(x - v2.x, y - v2.y, z - v2.z);
    }

    /** {@code operator*(v, s)} and {@code operator*(s, v)} */
    public btVector3 mul(double s) {
        return new btVector3(x * s, y * s, z * s);
    }

    /** {@code operator*(v1, v2)} (elementwise) */
    public btVector3 mul(btVector3 v2) {
        return new btVector3(x * v2.x, y * v2.y, z * v2.z);
    }

    /** {@code operator/(v, s)}: {@code v * (btScalar(1.0) / s)}. */
    public btVector3 div(double s) {
        return mul(1.0 / s);
    }

    /** {@code operator/(v1, v2)} (elementwise) */
    public btVector3 div(btVector3 v2) {
        return new btVector3(x / v2.x, y / v2.y, z / v2.z);
    }

    /** unary {@code operator-(v)} */
    public btVector3 negate() {
        return new btVector3(-x, -y, -z);
    }

    // ---- products / norms ----

    public double dot(btVector3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    public double length2() {
        return dot(this);
    }

    public double length() {
        return Math.sqrt(length2());
    }

    public double norm() {
        return length();
    }

    /** {@code (v - *this).length2()} */
    public double distance2(btVector3 v) {
        return v.sub(this).length2();
    }

    /** {@code (v - *this).length()} */
    public double distance(btVector3 v) {
        return v.sub(this).length();
    }

    public btVector3 safeNormalize() {
        btVector3 absVec = this.absolute();
        int maxIndex = absVec.maxAxis();
        if (absVec.get(maxIndex) > 0) {
            this.divLocal(absVec.get(maxIndex));
            return this.divLocal(length());
        }
        setValue(1, 0, 0);
        return this;
    }

    /** {@code return *this /= length();} (in place, returns this) */
    public btVector3 normalize() {
        return divLocal(length());
    }

    /** {@code btVector3 norm = *this; return norm.normalize();} (keeps w) */
    public btVector3 normalized() {
        btVector3 norm = new btVector3(this);
        return norm.normalize();
    }

    /** GCC fuses btCos/btSin(_angle) into one sincos call. */
    public btVector3 rotate(btVector3 wAxis, double _angle) {
        btVector3 o = wAxis.mul(wAxis.dot(this));
        btVector3 _x = this.sub(o);
        btVector3 _y = new btVector3();
        _y.set(wAxis.cross(this));
        double[] sc = new double[2];
        btScalar.btSinCos(_angle, sc);
        return o.add(_x.mul(sc[1])).add(_y.mul(sc[0]));
    }

    public double angle(btVector3 v) {
        double s = Math.sqrt(length2() * v.length2());
        return btScalar.btAcos(dot(v) / s);
    }

    public btVector3 absolute() {
        return new btVector3(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public btVector3 cross(btVector3 v) {
        return new btVector3(y * v.z - z * v.y, z * v.x - x * v.z, x * v.y - y * v.x);
    }

    public double triple(btVector3 v1, btVector3 v2) {
        return x * (v1.y * v2.z - v1.z * v2.y)
                + y * (v1.z * v2.x - v1.x * v2.z)
                + z * (v1.x * v2.y - v1.y * v2.x);
    }

    public int minAxis() {
        return x < y ? (x < z ? 0 : 2) : (y < z ? 1 : 2);
    }

    public int maxAxis() {
        return x < y ? (y < z ? 2 : 1) : (x < z ? 2 : 0);
    }

    public int furthestAxis() {
        return absolute().minAxis();
    }

    public int closestAxis() {
        return absolute().maxAxis();
    }

    public void setInterpolate3(btVector3 v0, btVector3 v1, double rt) {
        double s = 1.0 - rt;
        x = s * v0.x + rt * v1.x;
        y = s * v0.y + rt * v1.y;
        z = s * v0.z + rt * v1.z;
    }

    public btVector3 lerp(btVector3 v, double t) {
        return new btVector3(x + (v.x - x) * t, y + (v.y - y) * t, z + (v.z - z) * t);
    }

    /** {@code operator==} (all four components, w first as in C++). */
    public boolean equalsValue(btVector3 other) {
        return ((w == other.w) && (z == other.z) && (y == other.y) && (x == other.x));
    }

    public void setMax(btVector3 other) {
        if (x < other.x) x = other.x;
        if (y < other.y) y = other.y;
        if (z < other.z) z = other.z;
        if (w < other.w) w = other.w;
    }

    public void setMin(btVector3 other) {
        if (other.x < x) x = other.x;
        if (other.y < y) y = other.y;
        if (other.z < z) z = other.z;
        if (other.w < w) w = other.w;
    }

    public btVector3 setValue(double _x, double _y, double _z) {
        x = _x;
        y = _y;
        z = _z;
        w = 0.0;
        return this;
    }

    public void getSkewSymmetricMatrix(btVector3 v0, btVector3 v1, btVector3 v2) {
        v0.setValue(0., -z(), y());
        v1.setValue(z(), 0., -x());
        v2.setValue(-y(), x(), 0.);
    }

    public void setZero() {
        setValue(0., 0., 0.);
    }

    public boolean isZero() {
        return x == 0 && y == 0 && z == 0;
    }

    public boolean fuzzyZero() {
        return length2() < btScalar.SIMD_EPSILON;
    }

    /** Returns the index; {@code dotOut[0]} receives the max dot. array[i].dot(*this). */
    public int maxDot(btVector3[] array, int array_count, double[] dotOut) {
        double maxDot = -btScalar.SIMD_INFINITY;
        int ptIndex = -1;
        for (int i = 0; i < array_count; i++) {
            double dot = array[i].dot(this);
            if (dot > maxDot) {
                maxDot = dot;
                ptIndex = i;
            }
        }
        dotOut[0] = maxDot;
        return ptIndex;
    }

    /** Returns the index; {@code dotOut[0]} receives the min dot. array[i].dot(*this). */
    public int minDot(btVector3[] array, int array_count, double[] dotOut) {
        double minDot = btScalar.SIMD_INFINITY;
        int ptIndex = -1;
        for (int i = 0; i < array_count; i++) {
            double dot = array[i].dot(this);
            if (dot < minDot) {
                minDot = dot;
                ptIndex = i;
            }
        }
        dotOut[0] = minDot;
        return ptIndex;
    }

    /** {@code btVector3(dot(v0), dot(v1), dot(v2))} */
    public btVector3 dot3(btVector3 v0, btVector3 v1, btVector3 v2) {
        return new btVector3(dot(v0), dot(v1), dot(v2));
    }

    /** Serialization helper (btVector3::serialize / serializeDouble): writes m_floats[0..3]. */
    public void serialize(double[] dataOut, int off) {
        dataOut[off] = x;
        dataOut[off + 1] = y;
        dataOut[off + 2] = z;
        dataOut[off + 3] = w;
    }

    public void deSerialize(double[] dataIn, int off) {
        x = dataIn[off];
        y = dataIn[off + 1];
        z = dataIn[off + 2];
        w = dataIn[off + 3];
    }

    /** btVector3::serializeFloat: {@code float(m_floats[i])}. */
    public void serializeFloat(float[] dataOut, int off) {
        dataOut[off] = (float) x;
        dataOut[off + 1] = (float) y;
        dataOut[off + 2] = (float) z;
        dataOut[off + 3] = (float) w;
    }

    public void deSerializeFloat(float[] dataIn, int off) {
        x = dataIn[off];
        y = dataIn[off + 1];
        z = dataIn[off + 2];
        w = dataIn[off + 3];
    }

    @Override
    public String toString() {
        return "btVector3(" + x + ", " + y + ", " + z + ", " + w + ")";
    }

    // ---- free functions from btVector3.h ----

    public static double btDot(btVector3 v1, btVector3 v2) {
        return v1.dot(v2);
    }

    public static double btDistance2(btVector3 v1, btVector3 v2) {
        return v1.distance2(v2);
    }

    public static double btDistance(btVector3 v1, btVector3 v2) {
        return v1.distance(v2);
    }

    public static double btAngle(btVector3 v1, btVector3 v2) {
        return v1.angle(v2);
    }

    public static btVector3 btCross(btVector3 v1, btVector3 v2) {
        return v1.cross(v2);
    }

    public static double btTriple(btVector3 v1, btVector3 v2, btVector3 v3) {
        return v1.triple(v2, v3);
    }

    public static btVector3 lerp(btVector3 v1, btVector3 v2, double t) {
        return v1.lerp(v2, t);
    }

    /** btPlaneSpace1&lt;btVector3&gt;(n, p, q) */
    public static void btPlaneSpace1(btVector3 n, btVector3 p, btVector3 q) {
        if (Math.abs(n.z) > btScalar.SIMDSQRT12) {
            double a = n.y * n.y + n.z * n.z;
            double k = btScalar.btRecipSqrt(a);
            p.x = 0;
            p.y = -n.z * k;
            p.z = n.y * k;
            q.x = a * k;
            q.y = -n.x * p.z;
            q.z = n.x * p.y;
        } else {
            double a = n.x * n.x + n.y * n.y;
            double k = btScalar.btRecipSqrt(a);
            p.x = -n.y * k;
            p.y = n.x * k;
            p.z = 0;
            q.x = -n.z * p.y;
            q.y = n.z * p.x;
            q.z = a * k;
        }
    }

    public static void btSwapScalarEndian(double sourceVal, double[] destVal, int off) {
        destVal[off] =
                Double.longBitsToDouble(Long.reverseBytes(Double.doubleToRawLongBits(sourceVal)));
    }

    public static void btSwapVector3Endian(btVector3 sourceVec, btVector3 destVec) {
        for (int i = 0; i < 4; i++) {
            destVec.set(
                    i,
                    Double.longBitsToDouble(
                            Long.reverseBytes(Double.doubleToRawLongBits(sourceVec.get(i)))));
        }
    }

    public static void btUnSwapVector3Endian(btVector3 vector) {
        btVector3 swappedVec = new btVector3();
        btSwapVector3Endian(vector, swappedVec);
        vector.set(swappedVec);
    }
}
