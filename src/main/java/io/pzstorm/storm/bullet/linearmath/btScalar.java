// Port of LinearMath/btScalar.h (Bullet 2.82), BT_USE_DOUBLE_PRECISION, no SSE/NEON (the Linux .so
// has no SIMD path for doubles).
package io.pzstorm.storm.bullet.linearmath;

import io.pzstorm.storm.bullet.libm.GlibcMath;

/**
 * btScalar.h helpers and constants. {@code btScalar} is {@code double}.
 *
 * <p>Transcendentals go through {@link GlibcMath} (bit-exact glibc 2.x libm). {@code sqrt} is
 * {@link Math#sqrt} (IEEE correctly rounded, identical to sqrtsd). The .so imports only acos, asin,
 * atan2, cos, sin, sincos, fmod, pow, powf, sqrt, sqrtf from libm: tan/atan/exp/log are NOT linked,
 * so {@link #btTan}, {@link #btAtan}, {@link #btExp}, {@link #btLog} throw.
 *
 * <p>GCC fuses {@code sin(a)} and {@code cos(a)} with the same argument into one {@code sincos}
 * call; glibc's sincos returns the same bits as separate sin/cos, but ports should still use {@link
 * #btSinCos} at such sites to mirror the binary.
 *
 * <p><b>Macro hazard:</b> C++ {@code #define SIMD_2_PI btScalar(2.0) * SIMD_PI} is unparenthesised.
 * {@code x / SIMD_2_PI} in C++ means {@code x / 2.0 * SIMD_PI}. The only such use in 2.82 is
 * SIMD_DEGS_PER_RAD, reproduced verbatim below ({@code 360.0 / 2.0 * PI}, i.e. btDegrees is wrong
 * in upstream 2.82 and stays wrong here).
 */
public final class btScalar {
    private btScalar() {}

    public static final int BT_BULLET_VERSION = 282;

    public static int btGetVersion() {
        return BT_BULLET_VERSION;
    }

    public static final double BT_LARGE_FLOAT = 1e30;

    public static final double SIMD_PI = 3.1415926535897932384626433832795029;

    /** {@code btScalar(2.0) * SIMD_PI} (see macro hazard note). */
    public static final double SIMD_2_PI = 2.0 * SIMD_PI;

    public static final double SIMD_HALF_PI = (SIMD_PI * 0.5);

    /** {@code (SIMD_2_PI / btScalar(360.0))} = {@code (2.0 * PI / 360.0)}. */
    public static final double SIMD_RADS_PER_DEG = (2.0 * SIMD_PI / 360.0);

    /**
     * {@code (btScalar(360.0) / SIMD_2_PI)} expands to {@code (360.0 / 2.0 * PI)} (upstream bug).
     */
    public static final double SIMD_DEGS_PER_RAD = (360.0 / 2.0 * SIMD_PI);

    public static final double SIMDSQRT12 = 0.7071067811865475244008443621048490;

    /** DBL_EPSILON */
    public static final double SIMD_EPSILON = 2.220446049250313080847263336181640625e-16;

    /** DBL_MAX */
    public static final double SIMD_INFINITY = Double.MAX_VALUE;

    public static final double BT_ONE = 1.0;
    public static final double BT_ZERO = 0.0;
    public static final double BT_TWO = 2.0;
    public static final double BT_HALF = 0.5;

    public static double btSqrt(double x) {
        return Math.sqrt(x);
    }

    public static double btFabs(double x) {
        return Math.abs(x);
    }

    public static double btCos(double x) {
        return GlibcMath.cos(x);
    }

    public static double btSin(double x) {
        return GlibcMath.sin(x);
    }

    /**
     * GCC-fused {@code sin(x)}/{@code cos(x)} pair (one sincos call in the binary). out2[0]=sin,
     * out2[1]=cos.
     */
    public static void btSinCos(double x, double[] out2) {
        GlibcMath.sincos(x, out2);
    }

    /** Not linked into the .so (no tan import). */
    public static double btTan(double x) {
        throw new UnsupportedOperationException("btTan: tan() is not linked in libPZBullet");
    }

    public static double btAcos(double x) {
        if (x < -1.0) x = -1.0;
        if (x > 1.0) x = 1.0;
        return GlibcMath.acos(x);
    }

    public static double btAsin(double x) {
        if (x < -1.0) x = -1.0;
        if (x > 1.0) x = 1.0;
        return GlibcMath.asin(x);
    }

    /** Not linked into the .so (no atan import). */
    public static double btAtan(double x) {
        throw new UnsupportedOperationException("btAtan: atan() is not linked in libPZBullet");
    }

    /** {@code btAtan2(x, y) { return atan2(x, y); }} (argument names as in C++). */
    public static double btAtan2(double x, double y) {
        return GlibcMath.atan2(x, y);
    }

    /** Not linked into the .so (no exp import). */
    public static double btExp(double x) {
        throw new UnsupportedOperationException("btExp: exp() is not linked in libPZBullet");
    }

    /** Not linked into the .so (no log import). */
    public static double btLog(double x) {
        throw new UnsupportedOperationException("btLog: log() is not linked in libPZBullet");
    }

    public static double btPow(double x, double y) {
        return GlibcMath.pow(x, y);
    }

    public static double btFmod(double x, double y) {
        return GlibcMath.fmod(x, y);
    }

    /** {@code #define btRecipSqrt(x) ((btScalar)(btScalar(1.0)/btSqrt(btScalar(x))))} */
    public static double btRecipSqrt(double x) {
        return 1.0 / Math.sqrt(x);
    }

    /** {@code #define btRecip(x) (btScalar(1.0)/btScalar(x))} */
    public static double btRecip(double x) {
        return 1.0 / x;
    }

    public static double btAtan2Fast(double y, double x) {
        double coeff_1 = SIMD_PI / 4.0;
        double coeff_2 = 3.0 * coeff_1;
        double abs_y = Math.abs(y);
        double angle;
        if (x >= 0.0) {
            double r = (x - abs_y) / (x + abs_y);
            angle = coeff_1 - coeff_1 * r;
        } else {
            double r = (x + abs_y) / (abs_y - x);
            angle = coeff_2 - coeff_1 * r;
        }
        return (y < 0.0) ? -angle : angle;
    }

    public static boolean btFuzzyZero(double x) {
        return Math.abs(x) < SIMD_EPSILON;
    }

    public static boolean btEqual(double a, double eps) {
        return ((a <= eps) && !(a < -eps));
    }

    public static boolean btGreaterEqual(double a, double eps) {
        return (!(a <= eps));
    }

    public static int btIsNegative(double x) {
        return x < 0.0 ? 1 : 0;
    }

    public static double btRadians(double x) {
        return x * SIMD_RADS_PER_DEG;
    }

    public static double btDegrees(double x) {
        return x * SIMD_DEGS_PER_RAD;
    }

    public static double btFsel(double a, double b, double c) {
        return a >= 0 ? b : c;
    }

    public static double btFsels(double a, double b, double c) {
        return btFsel(a, b, c);
    }

    public static boolean btMachineIsLittleEndian() {
        return true;
    }

    /** unsigned btSelect(unsigned condition, unsigned a, unsigned b) / int overload. */
    public static int btSelect(
            int condition, int valueIfConditionNonZero, int valueIfConditionZero) {
        int testNz = ((condition | -condition) >> 31);
        int testEqz = ~testNz;
        return ((valueIfConditionNonZero & testNz) | (valueIfConditionZero & testEqz));
    }

    /** float btSelect(unsigned condition, float, float): {@code condition ? a : b}. */
    public static float btSelect(
            int condition, float valueIfConditionNonZero, float valueIfConditionZero) {
        return (condition != 0) ? valueIfConditionNonZero : valueIfConditionZero;
    }

    public static int btSwapEndian(int val) {
        return Integer.reverseBytes(val);
    }

    /** unsigned short btSwapEndian(unsigned short); value masked to 16 bits. */
    public static int btSwapEndianShort(int val) {
        return (((val & 0xff00) >>> 8) | ((val & 0x00ff) << 8)) & 0xFFFF;
    }

    public static int btSwapEndianFloat(float d) {
        return Integer.reverseBytes(Float.floatToRawIntBits(d));
    }

    public static float btUnswapEndianFloat(int a) {
        return Float.intBitsToFloat(Integer.reverseBytes(a));
    }

    public static void btSwapEndianDouble(double d, byte[] dst, int off) {
        long bits = Double.doubleToRawLongBits(d);
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (bits >>> (8 * (7 - i)));
        }
    }

    public static double btUnswapEndianDouble(byte[] src, int off) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits |= (src[off + i] & 0xFFL) << (8 * (7 - i));
        }
        return Double.longBitsToDouble(bits);
    }

    public static void btSetZero(double[] a, int off, int n) {
        for (int i = 0; i < n; i++) a[off + i] = 0;
    }

    /** btLargeDot(const btScalar* a, const btScalar* b, int n) with array offsets. */
    public static double btLargeDot(double[] aArr, int a, double[] bArr, int b, int n) {
        double p0, q0, m0, p1, q1, m1, sum;
        sum = 0;
        n -= 2;
        while (n >= 0) {
            p0 = aArr[a];
            q0 = bArr[b];
            m0 = p0 * q0;
            p1 = aArr[a + 1];
            q1 = bArr[b + 1];
            m1 = p1 * q1;
            sum += m0;
            sum += m1;
            a += 2;
            b += 2;
            n -= 2;
        }
        n += 2;
        while (n > 0) {
            sum += aArr[a] * bArr[b];
            a++;
            b++;
            n--;
        }
        return sum;
    }

    public static double btLargeDot(double[] a, double[] b, int n) {
        return btLargeDot(a, 0, b, 0, n);
    }

    public static double btNormalizeAngle(double angleInRadians) {
        angleInRadians = btFmod(angleInRadians, SIMD_2_PI);
        if (angleInRadians < -SIMD_PI) {
            return angleInRadians + SIMD_2_PI;
        } else if (angleInRadians > SIMD_PI) {
            return angleInRadians - SIMD_2_PI;
        } else {
            return angleInRadians;
        }
    }

    // ---------------------------------------------------------------------------------------
    // x86-64 conversion helpers (not in btScalar.h; shared by every port). Java casts saturate,
    // x86 cvttsd2si returns the "integer indefinite" value on NaN/overflow. Use these wherever C++
    // casts a double to an integer type and the value could be out of range.
    // ---------------------------------------------------------------------------------------

    /** {@code (int)d} as compiled by GCC on x86-64 (cvttsd2si r32): NaN/out-of-range -> INT_MIN. */
    public static int cvttsd2si(double d) {
        if (d != d || d >= 2147483648.0 || d <= -2147483649.0) {
            return Integer.MIN_VALUE;
        }
        return (int) d;
    }

    /**
     * {@code (int64_t)d} as compiled by GCC on x86-64 (cvttsd2si r64): NaN/out-of-range ->
     * LONG_MIN.
     */
    public static long cvttsd2si64(double d) {
        if (d != d || d >= 9.223372036854775808e18 || d < -9.223372036854775808e18) {
            return Long.MIN_VALUE;
        }
        return (long) d;
    }

    /**
     * {@code (double)(uint64_t)u} as compiled by GCC on x86-64 (correctly rounded; the shr/or/add
     * sequence for values with the top bit set).
     */
    public static double u64ToDouble(long u) {
        if (u >= 0) {
            return (double) u;
        }
        double d = (double) ((u >>> 1) | (u & 1L));
        return d + d;
    }
}
