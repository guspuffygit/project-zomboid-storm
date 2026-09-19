// Port of LinearMath/btMatrix3x3.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, scalar branch.
package io.pzstorm.storm.bullet.linearmath;

/**
 * btMatrix3x3: three row vectors {@code m_el0, m_el1, m_el2} ({@code m_el[3]} in C++).
 *
 * <p>Rows are final btVector3 fields; {@link #getRow}/{@link #get(int)} return the row object
 * itself (C++ returns a reference), so {@code m[i][j] = s} is {@code m.get(i).set(j, s)} or {@code
 * m.set(i, j, s)}. {@link #getColumn} returns a new vector. {@code setValue} on the rows sets their
 * w to 0; copy/assignment copy w (as C++).
 *
 * <pre>
 * C++                                Java
 * ---------------------------------  -------------------------------------------
 * btMatrix3x3 a(b);  a = b;          new btMatrix3x3(b);  a.set(b)
 * m[i]  / m[i][j]                    m.get(i) / m.get(i, j)   (row by reference)
 * m[i][j] = s                        m.set(i, j, s)
 * m1 * m2                            m1.mul(m2)               (new)
 * m *= m2                            m.mulLocal(m2)           (returns this)
 * m += m2 / m -= m2                  m.addLocal(m2) / m.subLocal(m2)
 * m1 + m2 / m1 - m2                  m1.add(m2) / m1.sub(m2)
 * m * k   (scalar)                   m.mul(k)
 * m * v                              m.mul(v)
 * v * m                              btMatrix3x3.mul(v, m)
 * m1 == m2                           m1.equalsValue(m2)
 * getRotation(q)                     m.getRotation(q)         (writes q)
 * getEulerYPR(y,p,r)                 m.getEulerYPR(double[3] {yaw,pitch,roll})
 * getEulerZYX(y,p,r,sol)             m.getEulerZYX(double[3], sol)
 * getOpenGLSubMatrix(btScalar* m)    getOpenGLSubMatrix(double[] m, int off)
 * </pre>
 *
 * sin/cos of the same Euler angle in setEulerZYX are one sincos call in the binary ({@link
 * btScalar#btSinCos}).
 */
public class btMatrix3x3 {
    public final btVector3 m_el0 = new btVector3();
    public final btVector3 m_el1 = new btVector3();
    public final btVector3 m_el2 = new btVector3();

    private static final btMatrix3x3 identityMatrix =
            new btMatrix3x3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);

    /** C++ leaves storage uninitialised; Java zeroes it. */
    public btMatrix3x3() {}

    public btMatrix3x3(btQuaternion q) {
        setRotation(q);
    }

    public btMatrix3x3(
            double xx,
            double xy,
            double xz,
            double yx,
            double yy,
            double yz,
            double zx,
            double zy,
            double zz) {
        setValue(xx, xy, xz, yx, yy, yz, zx, zy, zz);
    }

    public btMatrix3x3(btMatrix3x3 other) {
        m_el0.set(other.m_el0);
        m_el1.set(other.m_el1);
        m_el2.set(other.m_el2);
    }

    /** {@code operator=} */
    public btMatrix3x3 set(btMatrix3x3 other) {
        m_el0.set(other.m_el0);
        m_el1.set(other.m_el1);
        m_el2.set(other.m_el2);
        return this;
    }

    public btVector3 getColumn(int i) {
        return new btVector3(m_el0.get(i), m_el1.get(i), m_el2.get(i));
    }

    public btVector3 getRow(int i) {
        return get(i);
    }

    /** {@code operator[](i)}: the row itself (by reference). */
    public btVector3 get(int i) {
        switch (i) {
            case 0:
                return m_el0;
            case 1:
                return m_el1;
            case 2:
                return m_el2;
            default:
                throw new IndexOutOfBoundsException(i);
        }
    }

    /** {@code m[i][j]} */
    public double get(int i, int j) {
        return get(i).get(j);
    }

    /** {@code m[i][j] = v} */
    public void set(int i, int j, double v) {
        get(i).set(j, v);
    }

    /** {@code operator*=}: this = this * m */
    public btMatrix3x3 mulLocal(btMatrix3x3 m) {
        setValue(
                m.tdotx(m_el0),
                m.tdoty(m_el0),
                m.tdotz(m_el0),
                m.tdotx(m_el1),
                m.tdoty(m_el1),
                m.tdotz(m_el1),
                m.tdotx(m_el2),
                m.tdoty(m_el2),
                m.tdotz(m_el2));
        return this;
    }

    /** {@code operator+=} */
    public btMatrix3x3 addLocal(btMatrix3x3 m) {
        setValue(
                m_el0.x + m.m_el0.x,
                m_el0.y + m.m_el0.y,
                m_el0.z + m.m_el0.z,
                m_el1.x + m.m_el1.x,
                m_el1.y + m.m_el1.y,
                m_el1.z + m.m_el1.z,
                m_el2.x + m.m_el2.x,
                m_el2.y + m.m_el2.y,
                m_el2.z + m.m_el2.z);
        return this;
    }

    /** {@code operator-=} */
    public btMatrix3x3 subLocal(btMatrix3x3 m) {
        setValue(
                m_el0.x - m.m_el0.x,
                m_el0.y - m.m_el0.y,
                m_el0.z - m.m_el0.z,
                m_el1.x - m.m_el1.x,
                m_el1.y - m.m_el1.y,
                m_el1.z - m.m_el1.z,
                m_el2.x - m.m_el2.x,
                m_el2.y - m.m_el2.y,
                m_el2.z - m.m_el2.z);
        return this;
    }

    /** setFromOpenGLSubMatrix(const btScalar* m) with array offset. */
    public void setFromOpenGLSubMatrix(double[] m, int off) {
        m_el0.setValue(m[off], m[off + 4], m[off + 8]);
        m_el1.setValue(m[off + 1], m[off + 5], m[off + 9]);
        m_el2.setValue(m[off + 2], m[off + 6], m[off + 10]);
    }

    public void setFromOpenGLSubMatrix(double[] m) {
        setFromOpenGLSubMatrix(m, 0);
    }

    public void setValue(
            double xx,
            double xy,
            double xz,
            double yx,
            double yy,
            double yz,
            double zx,
            double zy,
            double zz) {
        m_el0.setValue(xx, xy, xz);
        m_el1.setValue(yx, yy, yz);
        m_el2.setValue(zx, zy, zz);
    }

    public void setRotation(btQuaternion q) {
        double d = q.length2();
        double s = 2.0 / d;

        double xs = q.x() * s, ys = q.y() * s, zs = q.z() * s;
        double wx = q.w() * xs, wy = q.w() * ys, wz = q.w() * zs;
        double xx = q.x() * xs, xy = q.x() * ys, xz = q.x() * zs;
        double yy = q.y() * ys, yz = q.y() * zs, zz = q.z() * zs;
        setValue(
                1.0 - (yy + zz),
                xy - wz,
                xz + wy,
                xy + wz,
                1.0 - (xx + zz),
                yz - wx,
                xz - wy,
                yz + wx,
                1.0 - (xx + yy));
    }

    public void setEulerYPR(double yaw, double pitch, double roll) {
        setEulerZYX(roll, pitch, yaw);
    }

    public void setEulerZYX(double eulerX, double eulerY, double eulerZ) {
        double[] sc = new double[2];
        btScalar.btSinCos(eulerX, sc);
        double ci = sc[1];
        double si = sc[0];
        btScalar.btSinCos(eulerY, sc);
        double cj = sc[1];
        double sj = sc[0];
        btScalar.btSinCos(eulerZ, sc);
        double ch = sc[1];
        double sh = sc[0];
        double cc = ci * ch;
        double cs = ci * sh;
        double sc_ = si * ch;
        double ss = si * sh;

        setValue(
                cj * ch,
                sj * sc_ - cs,
                sj * cc + ss,
                cj * sh,
                sj * ss + cc,
                sj * cs - sc_,
                -sj,
                cj * si,
                cj * ci);
    }

    public void setIdentity() {
        setValue(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
    }

    /** Shared constant (C++ returns a const reference; do not mutate). */
    public static btMatrix3x3 getIdentity() {
        return identityMatrix;
    }

    public void getOpenGLSubMatrix(double[] m, int off) {
        m[off] = m_el0.x();
        m[off + 1] = m_el1.x();
        m[off + 2] = m_el2.x();
        m[off + 3] = 0.0;
        m[off + 4] = m_el0.y();
        m[off + 5] = m_el1.y();
        m[off + 6] = m_el2.y();
        m[off + 7] = 0.0;
        m[off + 8] = m_el0.z();
        m[off + 9] = m_el1.z();
        m[off + 10] = m_el2.z();
        m[off + 11] = 0.0;
    }

    public void getOpenGLSubMatrix(double[] m) {
        getOpenGLSubMatrix(m, 0);
    }

    public void getRotation(btQuaternion q) {
        double trace = m_el0.x() + m_el1.y() + m_el2.z();

        double[] temp = new double[4];

        if (trace > 0.0) {
            double s = Math.sqrt(trace + 1.0);
            temp[3] = (s * 0.5);
            s = 0.5 / s;

            temp[0] = ((m_el2.y() - m_el1.z()) * s);
            temp[1] = ((m_el0.z() - m_el2.x()) * s);
            temp[2] = ((m_el1.x() - m_el0.y()) * s);
        } else {
            int i =
                    m_el0.x() < m_el1.y()
                            ? (m_el1.y() < m_el2.z() ? 2 : 1)
                            : (m_el0.x() < m_el2.z() ? 2 : 0);
            int j = (i + 1) % 3;
            int k = (i + 2) % 3;

            double s = Math.sqrt(get(i, i) - get(j, j) - get(k, k) + 1.0);
            temp[i] = s * 0.5;
            s = 0.5 / s;

            temp[3] = (get(k, j) - get(j, k)) * s;
            temp[j] = (get(j, i) + get(i, j)) * s;
            temp[k] = (get(k, i) + get(i, k)) * s;
        }
        q.setValue(temp[0], temp[1], temp[2], temp[3]);
    }

    /** Convenience: {@code btQuaternion q; m.getRotation(q); return q;} */
    public btQuaternion getRotation() {
        btQuaternion q = new btQuaternion();
        getRotation(q);
        return q;
    }

    /** out[0]=yaw, out[1]=pitch, out[2]=roll. */
    public void getEulerYPR(double[] out) {
        double yaw = btScalar.btAtan2(m_el1.x(), m_el0.x());
        double pitch = btScalar.btAsin(-m_el2.x());
        double roll = btScalar.btAtan2(m_el2.y(), m_el2.z());

        if (Math.abs(pitch) == btScalar.SIMD_HALF_PI) {
            if (yaw > 0) yaw -= btScalar.SIMD_PI;
            else yaw += btScalar.SIMD_PI;

            if (roll > 0) roll -= btScalar.SIMD_PI;
            else roll += btScalar.SIMD_PI;
        }
        out[0] = yaw;
        out[1] = pitch;
        out[2] = roll;
    }

    public void getEulerZYX(double[] out) {
        getEulerZYX(out, 1);
    }

    /** out[0]=yaw, out[1]=pitch, out[2]=roll. solution_number is unsigned (1 or 2). */
    public void getEulerZYX(double[] out, int solution_number) {
        double out_yaw, out_pitch, out_roll;
        double out2_yaw, out2_pitch, out2_roll;

        if (Math.abs(m_el2.x()) >= 1) {
            out_yaw = 0;
            out2_yaw = 0;

            double delta = btScalar.btAtan2(m_el0.x(), m_el0.z());
            if (m_el2.x() > 0) {
                out_pitch = btScalar.SIMD_PI / 2.0;
                out2_pitch = btScalar.SIMD_PI / 2.0;
                out_roll = out_pitch + delta;
                out2_roll = out_pitch + delta;
            } else {
                out_pitch = -btScalar.SIMD_PI / 2.0;
                out2_pitch = -btScalar.SIMD_PI / 2.0;
                out_roll = -out_pitch + delta;
                out2_roll = -out_pitch + delta;
            }
        } else {
            out_pitch = -btScalar.btAsin(m_el2.x());
            out2_pitch = btScalar.SIMD_PI - out_pitch;

            // btCos(pitch) is evaluated repeatedly in C++; cos is pure so the value is identical.
            double c1 = btScalar.btCos(out_pitch);
            double c2 = btScalar.btCos(out2_pitch);
            out_roll = btScalar.btAtan2(m_el2.y() / c1, m_el2.z() / c1);
            out2_roll = btScalar.btAtan2(m_el2.y() / c2, m_el2.z() / c2);

            out_yaw = btScalar.btAtan2(m_el1.x() / c1, m_el0.x() / c1);
            out2_yaw = btScalar.btAtan2(m_el1.x() / c2, m_el0.x() / c2);
        }

        if (solution_number == 1) {
            out[0] = out_yaw;
            out[1] = out_pitch;
            out[2] = out_roll;
        } else {
            out[0] = out2_yaw;
            out[1] = out2_pitch;
            out[2] = out2_roll;
        }
    }

    public btMatrix3x3 scaled(btVector3 s) {
        return new btMatrix3x3(
                m_el0.x() * s.x(),
                m_el0.y() * s.y(),
                m_el0.z() * s.z(),
                m_el1.x() * s.x(),
                m_el1.y() * s.y(),
                m_el1.z() * s.z(),
                m_el2.x() * s.x(),
                m_el2.y() * s.y(),
                m_el2.z() * s.z());
    }

    public double determinant() {
        return btVector3.btTriple(m_el0, m_el1, m_el2);
    }

    public btMatrix3x3 adjoint() {
        return new btMatrix3x3(
                cofac(1, 1, 2, 2),
                cofac(0, 2, 2, 1),
                cofac(0, 1, 1, 2),
                cofac(1, 2, 2, 0),
                cofac(0, 0, 2, 2),
                cofac(0, 2, 1, 0),
                cofac(1, 0, 2, 1),
                cofac(0, 1, 2, 0),
                cofac(0, 0, 1, 1));
    }

    public btMatrix3x3 absolute() {
        return new btMatrix3x3(
                Math.abs(m_el0.x()),
                Math.abs(m_el0.y()),
                Math.abs(m_el0.z()),
                Math.abs(m_el1.x()),
                Math.abs(m_el1.y()),
                Math.abs(m_el1.z()),
                Math.abs(m_el2.x()),
                Math.abs(m_el2.y()),
                Math.abs(m_el2.z()));
    }

    public btMatrix3x3 transpose() {
        return new btMatrix3x3(
                m_el0.x(), m_el1.x(), m_el2.x(), m_el0.y(), m_el1.y(), m_el2.y(), m_el0.z(),
                m_el1.z(), m_el2.z());
    }

    public btMatrix3x3 inverse() {
        btVector3 co = new btVector3(cofac(1, 1, 2, 2), cofac(1, 2, 2, 0), cofac(1, 0, 2, 1));
        double det = m_el0.dot(co);
        double s = 1.0 / det;
        return new btMatrix3x3(
                co.x() * s,
                cofac(0, 2, 2, 1) * s,
                cofac(0, 1, 1, 2) * s,
                co.y() * s,
                cofac(0, 0, 2, 2) * s,
                cofac(0, 2, 1, 0) * s,
                co.z() * s,
                cofac(0, 1, 2, 0) * s,
                cofac(0, 0, 1, 1) * s);
    }

    public btMatrix3x3 transposeTimes(btMatrix3x3 m) {
        btVector3 m0 = m.m_el0, m1 = m.m_el1, m2 = m.m_el2;
        return new btMatrix3x3(
                m_el0.x() * m0.x() + m_el1.x() * m1.x() + m_el2.x() * m2.x(),
                m_el0.x() * m0.y() + m_el1.x() * m1.y() + m_el2.x() * m2.y(),
                m_el0.x() * m0.z() + m_el1.x() * m1.z() + m_el2.x() * m2.z(),
                m_el0.y() * m0.x() + m_el1.y() * m1.x() + m_el2.y() * m2.x(),
                m_el0.y() * m0.y() + m_el1.y() * m1.y() + m_el2.y() * m2.y(),
                m_el0.y() * m0.z() + m_el1.y() * m1.z() + m_el2.y() * m2.z(),
                m_el0.z() * m0.x() + m_el1.z() * m1.x() + m_el2.z() * m2.x(),
                m_el0.z() * m0.y() + m_el1.z() * m1.y() + m_el2.z() * m2.y(),
                m_el0.z() * m0.z() + m_el1.z() * m1.z() + m_el2.z() * m2.z());
    }

    public btMatrix3x3 timesTranspose(btMatrix3x3 m) {
        return new btMatrix3x3(
                m_el0.dot(m.m_el0),
                m_el0.dot(m.m_el1),
                m_el0.dot(m.m_el2),
                m_el1.dot(m.m_el0),
                m_el1.dot(m.m_el1),
                m_el1.dot(m.m_el2),
                m_el2.dot(m.m_el0),
                m_el2.dot(m.m_el1),
                m_el2.dot(m.m_el2));
    }

    public double tdotx(btVector3 v) {
        return m_el0.x() * v.x() + m_el1.x() * v.y() + m_el2.x() * v.z();
    }

    public double tdoty(btVector3 v) {
        return m_el0.y() * v.x() + m_el1.y() * v.y() + m_el2.y() * v.z();
    }

    public double tdotz(btVector3 v) {
        return m_el0.z() * v.x() + m_el1.z() * v.y() + m_el2.z() * v.z();
    }

    /** Jacobi diagonalisation (matrix assumed symmetric). */
    public void diagonalize(btMatrix3x3 rot, double threshold, int maxSteps) {
        rot.setIdentity();
        for (int step = maxSteps; step > 0; step--) {
            int p = 0;
            int q = 1;
            int r = 2;
            double max = Math.abs(get(0, 1));
            double v = Math.abs(get(0, 2));
            if (v > max) {
                q = 2;
                r = 1;
                max = v;
            }
            v = Math.abs(get(1, 2));
            if (v > max) {
                p = 1;
                q = 2;
                r = 0;
                max = v;
            }

            double t =
                    threshold * (Math.abs(get(0, 0)) + Math.abs(get(1, 1)) + Math.abs(get(2, 2)));
            if (max <= t) {
                if (max <= btScalar.SIMD_EPSILON * t) {
                    return;
                }
                step = 1;
            }

            double mpq = get(p, q);
            double theta = (get(q, q) - get(p, p)) / (2 * mpq);
            double theta2 = theta * theta;
            double cos;
            double sin;
            if (theta2 * theta2 < (10 / btScalar.SIMD_EPSILON)) {
                t =
                        (theta >= 0)
                                ? 1 / (theta + Math.sqrt(1 + theta2))
                                : 1 / (theta - Math.sqrt(1 + theta2));
                cos = 1 / Math.sqrt(1 + t * t);
                sin = cos * t;
            } else {
                t = 1 / (theta * (2 + 0.5 / theta2));
                cos = 1 - 0.5 * t * t;
                sin = cos * t;
            }

            set(q, p, 0);
            set(p, q, 0);
            set(p, p, get(p, p) - t * mpq);
            set(q, q, get(q, q) + t * mpq);
            double mrp = get(r, p);
            double mrq = get(r, q);
            double a = cos * mrp - sin * mrq;
            set(p, r, a);
            set(r, p, a);
            double b = cos * mrq + sin * mrp;
            set(q, r, b);
            set(r, q, b);

            for (int i = 0; i < 3; i++) {
                btVector3 row = rot.get(i);
                mrp = row.get(p);
                mrq = row.get(q);
                row.set(p, cos * mrp - sin * mrq);
                row.set(q, cos * mrq + sin * mrp);
            }
        }
    }

    public double cofac(int r1, int c1, int r2, int c2) {
        return get(r1, c1) * get(r2, c2) - get(r1, c2) * get(r2, c1);
    }

    /** btMatrix3x3DoubleData: 3 x btVector3DoubleData (4 doubles each) = 12 doubles. */
    public void serialize(double[] dataOut, int off) {
        m_el0.serialize(dataOut, off);
        m_el1.serialize(dataOut, off + 4);
        m_el2.serialize(dataOut, off + 8);
    }

    public void serializeFloat(float[] dataOut, int off) {
        m_el0.serializeFloat(dataOut, off);
        m_el1.serializeFloat(dataOut, off + 4);
        m_el2.serializeFloat(dataOut, off + 8);
    }

    public void deSerialize(double[] dataIn, int off) {
        m_el0.deSerialize(dataIn, off);
        m_el1.deSerialize(dataIn, off + 4);
        m_el2.deSerialize(dataIn, off + 8);
    }

    public void deSerializeFloat(float[] dataIn, int off) {
        m_el0.deSerializeFloat(dataIn, off);
        m_el1.deSerializeFloat(dataIn, off + 4);
        m_el2.deSerializeFloat(dataIn, off + 8);
    }

    // ---- free operators ----

    /** {@code operator*(m, k)} */
    public btMatrix3x3 mul(double k) {
        return new btMatrix3x3(
                m_el0.x() * k,
                m_el0.y() * k,
                m_el0.z() * k,
                m_el1.x() * k,
                m_el1.y() * k,
                m_el1.z() * k,
                m_el2.x() * k,
                m_el2.y() * k,
                m_el2.z() * k);
    }

    /** {@code operator+(m1, m2)} */
    public btMatrix3x3 add(btMatrix3x3 m2) {
        return new btMatrix3x3(
                m_el0.x + m2.m_el0.x,
                m_el0.y + m2.m_el0.y,
                m_el0.z + m2.m_el0.z,
                m_el1.x + m2.m_el1.x,
                m_el1.y + m2.m_el1.y,
                m_el1.z + m2.m_el1.z,
                m_el2.x + m2.m_el2.x,
                m_el2.y + m2.m_el2.y,
                m_el2.z + m2.m_el2.z);
    }

    /** {@code operator-(m1, m2)} */
    public btMatrix3x3 sub(btMatrix3x3 m2) {
        return new btMatrix3x3(
                m_el0.x - m2.m_el0.x,
                m_el0.y - m2.m_el0.y,
                m_el0.z - m2.m_el0.z,
                m_el1.x - m2.m_el1.x,
                m_el1.y - m2.m_el1.y,
                m_el1.z - m2.m_el1.z,
                m_el2.x - m2.m_el2.x,
                m_el2.y - m2.m_el2.y,
                m_el2.z - m2.m_el2.z);
    }

    /** {@code operator*(m, v)} */
    public btVector3 mul(btVector3 v) {
        return new btVector3(m_el0.dot(v), m_el1.dot(v), m_el2.dot(v));
    }

    /** {@code operator*(v, m)} */
    public static btVector3 mul(btVector3 v, btMatrix3x3 m) {
        return new btVector3(m.tdotx(v), m.tdoty(v), m.tdotz(v));
    }

    /** {@code operator*(m1, m2)} */
    public btMatrix3x3 mul(btMatrix3x3 m2) {
        btMatrix3x3 m1 = this;
        return new btMatrix3x3(
                m2.tdotx(m1.m_el0),
                m2.tdoty(m1.m_el0),
                m2.tdotz(m1.m_el0),
                m2.tdotx(m1.m_el1),
                m2.tdoty(m1.m_el1),
                m2.tdotz(m1.m_el1),
                m2.tdotx(m1.m_el2),
                m2.tdoty(m1.m_el2),
                m2.tdotz(m1.m_el2));
    }

    /** {@code operator==} (column-major comparison order as C++). */
    public boolean equalsValue(btMatrix3x3 m2) {
        btMatrix3x3 m1 = this;
        return (m1.get(0, 0) == m2.get(0, 0)
                && m1.get(1, 0) == m2.get(1, 0)
                && m1.get(2, 0) == m2.get(2, 0)
                && m1.get(0, 1) == m2.get(0, 1)
                && m1.get(1, 1) == m2.get(1, 1)
                && m1.get(2, 1) == m2.get(2, 1)
                && m1.get(0, 2) == m2.get(0, 2)
                && m1.get(1, 2) == m2.get(1, 2)
                && m1.get(2, 2) == m2.get(2, 2));
    }

    @Override
    public String toString() {
        return "btMatrix3x3[" + m_el0 + ", " + m_el1 + ", " + m_el2 + "]";
    }
}
