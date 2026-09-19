// Port of LinearMath/btQuadWord.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, scalar branch.
package io.pzstorm.storm.bullet.linearmath;

/**
 * btQuadWord: base of btQuaternion. {@code m_floats[4]} = {@code x, y, z, w}. The C++ default
 * constructor leaves storage uninitialised; here it is zero.
 */
public class btQuadWord {
    public double x, y, z, w;

    public btQuadWord() {}

    public btQuadWord(double _x, double _y, double _z) {
        x = _x;
        y = _y;
        z = _z;
        w = 0.0;
    }

    public btQuadWord(double _x, double _y, double _z, double _w) {
        x = _x;
        y = _y;
        z = _z;
        w = _w;
    }

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

    /** {@code operator==} (all four, w first). */
    public boolean equalsValue(btQuadWord other) {
        return ((w == other.w) && (z == other.z) && (y == other.y) && (x == other.x));
    }

    /** 3-arg setValue sets w = 0. */
    public void setValue(double _x, double _y, double _z) {
        x = _x;
        y = _y;
        z = _z;
        w = 0.0;
    }

    public void setValue(double _x, double _y, double _z, double _w) {
        x = _x;
        y = _y;
        z = _z;
        w = _w;
    }

    public void setMax(btQuadWord other) {
        if (x < other.x) x = other.x;
        if (y < other.y) y = other.y;
        if (z < other.z) z = other.z;
        if (w < other.w) w = other.w;
    }

    public void setMin(btQuadWord other) {
        if (other.x < x) x = other.x;
        if (other.y < y) y = other.y;
        if (other.z < z) z = other.z;
        if (other.w < w) w = other.w;
    }
}
