// Port of LinearMath/btMinMax.h (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * btMin / btMax / btClamped for double and int (the only instantiations Bullet uses on scalars).
 * The in-place templates {@code btSetMin(T& a, b)}, {@code btSetMax}, {@code btClamp} cannot take a
 * Java primitive by reference: write them inline as {@code a = btMinMax.btSetMin(a, b)} (returns
 * the new value), or use {@link btVector3#setMin}/{@link btVector3#setMax} for vectors. Comparison
 * direction is preserved exactly (matters for NaN and for -0.0 vs 0.0).
 */
public final class btMinMax {
    private btMinMax() {}

    public static double btMin(double a, double b) {
        return a < b ? a : b;
    }

    public static double btMax(double a, double b) {
        return a > b ? a : b;
    }

    public static double btClamped(double a, double lb, double ub) {
        return a < lb ? lb : (ub < a ? ub : a);
    }

    /** {@code if (b < a) a = b;} returns the resulting a. */
    public static double btSetMin(double a, double b) {
        if (b < a) {
            a = b;
        }
        return a;
    }

    /** {@code if (a < b) a = b;} returns the resulting a. */
    public static double btSetMax(double a, double b) {
        if (a < b) {
            a = b;
        }
        return a;
    }

    /** {@code if (a < lb) a = lb; else if (ub < a) a = ub;} returns the resulting a. */
    public static double btClamp(double a, double lb, double ub) {
        if (a < lb) {
            a = lb;
        } else if (ub < a) {
            a = ub;
        }
        return a;
    }

    public static int btMin(int a, int b) {
        return a < b ? a : b;
    }

    public static int btMax(int a, int b) {
        return a > b ? a : b;
    }

    public static int btClamped(int a, int lb, int ub) {
        return a < lb ? lb : (ub < a ? ub : a);
    }

    public static int btSetMin(int a, int b) {
        if (b < a) {
            a = b;
        }
        return a;
    }

    public static int btSetMax(int a, int b) {
        if (a < b) {
            a = b;
        }
        return a;
    }

    public static int btClamp(int a, int lb, int ub) {
        if (a < lb) {
            a = lb;
        } else if (ub < a) {
            a = ub;
        }
        return a;
    }
}
