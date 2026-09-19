// Port of LinearMath/btVector3.h class btVector4 (Bullet 2.82), BT_USE_DOUBLE_PRECISION.
package io.pzstorm.storm.bullet.linearmath;

/** btVector4 : btVector3 with a meaningful w. */
public class btVector4 extends btVector3 {
    public btVector4() {}

    public btVector4(double _x, double _y, double _z, double _w) {
        super(_x, _y, _z);
        w = _w;
    }

    public btVector4(btVector4 other) {
        super(other);
    }

    public btVector4 absolute4() {
        return new btVector4(Math.abs(x), Math.abs(y), Math.abs(z), Math.abs(w));
    }

    public double getW() {
        return w;
    }

    public int maxAxis4() {
        int maxIndex = -1;
        double maxVal = -btScalar.BT_LARGE_FLOAT;
        if (x > maxVal) {
            maxIndex = 0;
            maxVal = x;
        }
        if (y > maxVal) {
            maxIndex = 1;
            maxVal = y;
        }
        if (z > maxVal) {
            maxIndex = 2;
            maxVal = z;
        }
        if (w > maxVal) {
            maxIndex = 3;
        }
        return maxIndex;
    }

    public int minAxis4() {
        int minIndex = -1;
        double minVal = btScalar.BT_LARGE_FLOAT;
        if (x < minVal) {
            minIndex = 0;
            minVal = x;
        }
        if (y < minVal) {
            minIndex = 1;
            minVal = y;
        }
        if (z < minVal) {
            minIndex = 2;
            minVal = z;
        }
        if (w < minVal) {
            minIndex = 3;
        }
        return minIndex;
    }

    public int closestAxis4() {
        return absolute4().maxAxis4();
    }

    public void setValue(double _x, double _y, double _z, double _w) {
        x = _x;
        y = _y;
        z = _z;
        w = _w;
    }
}
