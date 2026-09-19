// Port of the input generator in src/test/resources/io/pzstorm/storm/bullet/libm/libm_refgen.c.
// Must stay in lock-step with it: every draw from the stream, in the same order.
package io.pzstorm.storm.bullet.libm;

/** Deterministic libm test inputs, identical to libm_refgen.c. One instance per thread. */
final class LibmInputs {

    static final int F_SIN = 0;
    static final int F_COS = 1;
    static final int F_SINCOS = 2;
    static final int F_ASIN = 3;
    static final int F_ACOS = 4;
    static final int F_ATAN2 = 5;
    static final int F_POW = 6;
    static final int F_FMOD = 7;
    static final int F_SQRT = 8;
    static final int F_POWF = 9;
    static final int F_SQRTF = 10;
    static final int NF = 11;
    static final String[] NAMES = {
        "sin", "cos", "sincos", "asin", "acos", "atan2", "pow", "fmod", "sqrt", "powf", "sqrtf"
    };

    static int nin(int f) {
        return (f == F_ATAN2 || f == F_POW || f == F_FMOD || f == F_POWF) ? 2 : 1;
    }

    static int nout(int f) {
        return f == F_SINCOS ? 2 : 1;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static long hash(long h, long v) {
        h = (h ^ v) * 0x9e3779b97f4a7c15L;
        return h ^ (h >>> 29);
    }

    private long st;

    private void seed(int f, long i) {
        st = mix(((long) f << 40) ^ i ^ 0x5eed5eed00000000L);
    }

    private long nx() {
        st += 0x9e3779b97f4a7c15L;
        return mix(st);
    }

    private long nxMod(long n) {
        return Long.remainderUnsigned(nx(), n);
    }

    private static final long[] SPECIAL_D = {
        0x0000000000000000L, 0x0000000000000001L, 0x000fffffffffffffL, 0x0010000000000000L,
        0x3fe0000000000000L, 0x3ff0000000000000L, 0x3fefffffffffffffL, 0x3ff0000000000001L,
        0x3ff8000000000000L, 0x4000000000000000L, 0x4008000000000000L, 0x3fd0000000000000L,
        0x4024000000000000L, 0x400921fb54442d18L, 0x3ff921fb54442d18L, 0x3fe921fb54442d18L,
        0x7fefffffffffffffL, 0x7ff0000000000000L, 0x7ff8000000000000L, 0x7ff0000000000001L,
        0x7ff8dead0000beefL, 0x7ff4000000001234L, 0x7fffffffffffffffL, 0x7e37e43c8800759cL,
        0x01a56e1fc2f8f359L, 0x3fb999999999999aL, 0x7fe0000000000000L, 0x4330000000000000L,
        0x433fffffffffffffL, 0x43f0000000000000L, 0x3fe8000000000000L, 0x44b52d02c7e14af6L,
        0x7506ac5b262ca1ffL, 0x4014000000000000L, 0x3e40000000000000L, 0x3e50000000000000L,
        0x3feb600000000000L, 0x400368fd00000000L, 0x419921fb00000000L, 0x3fc020c49ba5e354L,
        0x3c88000000000000L, 0x3fc0000000000000L, 0x3fef000000000000L, 0x4340000000000000L,
        0x4340000000000001L, 0x3cb0000000000000L, 0x0000000000000003L, 0x0008000000000000L,
    };
    static final int NSD = SPECIAL_D.length * 2;

    static long specialD(long k) {
        k = Long.remainderUnsigned(k, NSD);
        return SPECIAL_D[(int) (k >>> 1)] | ((k & 1) << 63);
    }

    private static final int[] SPECIAL_F = {
        0x00000000, 0x00000001, 0x007fffff, 0x00800000, 0x3f000000, 0x3f800000, 0x3f7fffff,
        0x3f800001, 0x40000000, 0x40400000, 0x3e800000, 0x41200000, 0x7f7fffff, 0x7f800000,
        0x7fc00000, 0x7f800001, 0x7fc0dead, 0x7fa00123, 0x7fffffff, 0x4b800000, 0x4b7fffff,
        0x4b000001, 0x3dcccccd, 0x43000000, 0x3fc00000, 0x7149f2ca, 0x0da24260, 0x00000003,
        0x00400000, 0x4f000000, 0x3f3504f3, 0x40490fdb,
    };
    static final int NSF = SPECIAL_F.length * 2;

    static int specialF(long k) {
        k = Long.remainderUnsigned(k, NSF);
        return SPECIAL_F[(int) (k >>> 1)] | ((int) (k & 1) << 31);
    }

    private long dExp(int lo, int hi) {
        long e = lo + nxMod(hi - lo + 1);
        long m = nx() & 0x000fffffffffffffL;
        long s = nx() & 1;
        return (s << 63) | (e << 52) | m;
    }

    private long dExpPos(int lo, int hi) {
        return dExp(lo, hi) & 0x7fffffffffffffffL;
    }

    private long delta(int maxbits) {
        int k = (int) nxMod(maxbits);
        long d = nx() & ((1L << k) - 1);
        return (nx() & 1) != 0 ? -d : d;
    }

    private long nearBits(long base, int maxbits) {
        return base + delta(maxbits);
    }

    private long randSign(long b) {
        return b ^ ((nx() & 1) << 63);
    }

    private int fExp(int lo, int hi) {
        int e = lo + (int) nxMod(hi - lo + 1);
        int m = (int) nx() & 0x7fffff;
        int s = (int) nx() & 1;
        return (s << 31) | (e << 23) | m;
    }

    private static long b(double d) {
        return Double.doubleToRawLongBits(d);
    }

    private static double d(long b) {
        return Double.longBitsToDouble(b);
    }

    private static final long[] TRIG_EDGES = {
        0x3e40000000000000L, 0x3e50000000000000L, 0x3feb600000000000L, 0x400368fd00000000L,
        0x419921fb00000000L, 0x3fc020c49ba5e354L, 0x7fefffffffffffffL, 0x3ff921fb54442d18L
    };
    private static final double PIO2 = 0x1.921fb54442d18p0;

    private long genTrig(long i) {
        switch ((int) (i % 8)) {
            case 0:
                return nx();
            case 1:
                return dExp(0x3c0, 0x41f);
            case 2:
                return dExp(0x400, 0x7fe);
            case 3:
                {
                    int sh = 11 + (int) nxMod(53);
                    double k = (double) (nx() >>> sh);
                    double kp = k * PIO2;
                    return randSign(nearBits(b(kp), 1 + (int) nxMod(12)));
                }
            case 4:
                return randSign(nearBits(TRIG_EDGES[(int) nxMod(8)], 41));
            case 5:
                return dExp(0x3f0, 0x402);
            case 6:
                return specialD(i / 8);
            default:
                return dExp(0x000, 0x3e5);
        }
    }

    private static final int[] ASIN_EDGES = {
        0x3c880000,
        0x3e500000,
        0x3fc00000,
        0x3fd00000,
        0x3fe00000,
        0x3fe80000,
        0x3fed8000,
        0x3fee8000,
        0x3fef0000,
        0x3ff00000
    };

    private long genAsin(long i) {
        switch ((int) (i % 8)) {
            case 0:
                return nx();
            case 1:
                return dExp(0x3c0, 0x3fe);
            case 2:
                return dExp(0x000, 0x3c9);
            case 3:
                {
                    int sh = (int) nxMod(53);
                    long dd = nxMod(1L << sh);
                    return randSign(
                            (nx() & 3) != 0 ? 0x3ff0000000000000L - dd : 0x3ff0000000000000L + dd);
                }
            case 4:
                return randSign(nearBits((long) ASIN_EDGES[(int) nxMod(10)] << 32, 41));
            case 5:
                return dExp(0x3fc, 0x3fe);
            case 6:
                return specialD(i / 8);
            default:
                return dExp(0x3ff, 0x7ff);
        }
    }

    private long genSqrt(long i) {
        switch ((int) (i % 4)) {
            case 0:
                return nx();
            case 1:
                return dExp(0x3c0, 0x440);
            case 2:
                return specialD(i / 4);
            default:
                return dExp(0x000, 0x001);
        }
    }

    private void genAtan2(long i, long[] in) {
        switch ((int) (i % 10)) {
            case 0:
                in[0] = nx();
                in[1] = nx();
                return;
            case 1:
                in[0] = dExp(0x3e0, 0x41f);
                in[1] = dExp(0x3e0, 0x41f);
                return;
            case 2:
                {
                    int e = 1 + (int) nxMod(0x7fe);
                    int ey = e + (int) nxMod(129) - 64;
                    if (ey < 0) {
                        ey = 0;
                    }
                    if (ey > 0x7fe) {
                        ey = 0x7fe;
                    }
                    in[1] = dExp(e, e);
                    in[0] = dExp(ey, ey);
                    return;
                }
            case 3:
                {
                    double xv = d(dExp(0x3f0, 0x410));
                    double t = d(dExpPos(0x3f6, 0x3ff));
                    long yv = randSign(b(xv * t));
                    if ((nx() & 1) != 0) {
                        in[0] = yv;
                        in[1] = b(xv);
                    } else {
                        in[0] = b(xv);
                        in[1] = yv;
                    }
                    return;
                }
            case 4:
                in[0] = (nx() & 1) != 0 ? dExp(0x000, 0x040) : dExp(0x7b0, 0x7fe);
                in[1] = (nx() & 1) != 0 ? dExp(0x000, 0x040) : dExp(0x7b0, 0x7fe);
                return;
            case 5:
                in[0] = specialD(i / 10);
                in[1] = specialD(i / 10 / NSD);
                return;
            case 6:
                if ((nx() & 1) != 0) {
                    in[0] = specialD(nx());
                    in[1] = nx();
                } else {
                    in[0] = nx();
                    in[1] = specialD(nx());
                }
                return;
            case 7:
                {
                    long bb = dExp(0x3c0, 0x440);
                    in[1] = bb;
                    in[0] = randSign(nearBits(bb & 0x7fffffffffffffffL, 30));
                    return;
                }
            case 8:
                in[0] = dExp(0x000, 0x003);
                in[1] = dExp(0x000, 0x003);
                return;
            default:
                in[0] = dExp(0x3f0, 0x40f);
                in[1] = dExp(0x3f0, 0x40f);
        }
    }

    private static final double[] POW_TARGETS = {
        1023.0, 1024.0, -1022.0, -1074.0, -1075.0, 1025.0, -1021.0, 0.5
    };

    private void genPow(long i, long[] in) {
        switch ((int) (i % 12)) {
            case 0:
                in[0] = nx();
                in[1] = nx();
                return;
            case 1:
                in[0] = dExpPos(0x3f0, 0x40f);
                in[1] = dExp(0x3f0, 0x40f);
                return;
            case 2:
                {
                    int sh = (int) nxMod(53);
                    long dd = nxMod(1L << sh);
                    in[0] = (nx() & 1) != 0 ? 0x3ff0000000000000L - dd : 0x3ff0000000000000L + dd;
                    in[1] = dExp(0x3f0, 0x43f);
                    return;
                }
            case 3:
                {
                    int sh = 4 + (int) nxMod(60);
                    in[1] = randSign(b((double) (nx() >>> sh)));
                    in[0] = dExp(0x380, 0x47f);
                    return;
                }
            case 4:
                in[0] = nx() | 0x8000000000000000L;
                in[1] = b((double) ((int) nxMod(81) - 40));
                return;
            case 5:
                {
                    int e = 0x3f0 + (int) nxMod(32);
                    if (e == 0x3ff) {
                        e = 0x400;
                    }
                    in[0] = dExpPos(e, e);
                    double t = POW_TARGETS[(int) nxMod(8)] / (double) (e - 0x3ff);
                    in[1] = nearBits(b(t), 1 + (int) nxMod(30));
                    if ((nx() & 1) != 0) {
                        in[0] |= 0x8000000000000000L;
                    }
                    return;
                }
            case 6:
                in[0] = specialD(i / 12);
                in[1] = specialD(i / 12 / NSD);
                return;
            case 7:
                if ((nx() & 1) != 0) {
                    in[0] = specialD(nx());
                    in[1] = nx();
                } else {
                    in[0] = nx();
                    in[1] = specialD(nx());
                }
                return;
            case 8:
                in[0] = dExp(0x000, 0x000);
                in[1] = dExp(0x3f0, 0x40a);
                return;
            case 9:
                in[0] = dExpPos(0x3c0, 0x440);
                in[1] = (nx() & 1) != 0 ? dExp(0x380, 0x3cf) : dExp(0x43e, 0x7fe);
                return;
            case 10:
                in[0] = dExpPos(0x000, 0x7fe);
                in[1] = dExp(0x3e0, 0x40f);
                return;
            default:
                in[0] = b((double) nxMod(1000));
                in[1] = b((double) ((int) nxMod(1401) - 700) * 0.5);
        }
    }

    private void genFmod(long i, long[] in) {
        switch ((int) (i % 10)) {
            case 0:
                in[0] = nx();
                in[1] = nx();
                return;
            case 1:
                in[0] = dExp(0x3c0, 0x440);
                in[1] = dExp(0x3c0, 0x440);
                return;
            case 2:
                {
                    int ey = 53 + (int) nxMod(2035 - 53 + 1);
                    int ex = ey + (int) nxMod(13);
                    if (ex > 0x7fe) {
                        ex = 0x7fe;
                    }
                    in[0] = dExp(ex, ex);
                    in[1] = dExp(ey, ey);
                    return;
                }
            case 3:
                {
                    int ey = (int) nxMod(0x7ff);
                    int ex = ey + (int) nxMod(2047);
                    if (ex > 0x7fe) {
                        ex = 0x7fe;
                    }
                    in[0] = dExp(ex, ex);
                    in[1] = dExp(ey, ey);
                    return;
                }
            case 4:
                in[0] = dExp(0x000, 0x7fe);
                in[1] = dExp(0x000, 0x000);
                return;
            case 5:
                in[0] = dExp(0x000, 0x000);
                in[1] = dExp(0x000, 0x000);
                return;
            case 6:
                in[0] = specialD(i / 10);
                in[1] = specialD(i / 10 / NSD);
                return;
            case 7:
                if ((nx() & 1) != 0) {
                    in[0] = specialD(nx());
                    in[1] = nx();
                } else {
                    in[0] = nx();
                    in[1] = specialD(nx());
                }
                return;
            case 8:
                {
                    double yv = d(dExp(0x300, 0x500));
                    int sh = 4 + (int) nxMod(60);
                    double k = (double) (nx() >>> sh);
                    in[1] = b(yv);
                    in[0] = randSign(nearBits(b(yv * k) & 0x7fffffffffffffffL, 1 + (int) nxMod(8)));
                    return;
                }
            default:
                in[1] = dExp(0x001, 0x7fe) & 0xfff0000000000000L;
                in[0] = dExp(0x000, 0x7fe);
        }
    }

    private static final float[] POWF_TARGETS = {
        128.0f, 127.0f, -126.0f, -149.0f, -150.0f, 129.0f, -125.0f, 0.5f
    };

    private static int fb(float f) {
        return Float.floatToRawIntBits(f);
    }

    private void genPowf(long i, long[] in) {
        int x;
        int y;
        switch ((int) (i % 10)) {
            case 0:
                {
                    long r = nx();
                    x = (int) r;
                    y = (int) (r >>> 32);
                    break;
                }
            case 1:
                x = fExp(0x70, 0x8f) & 0x7fffffff;
                y = fExp(0x70, 0x8f);
                break;
            case 2:
                {
                    int sh = (int) nxMod(24);
                    int dd = (int) nxMod(1L << sh);
                    x = (nx() & 1) != 0 ? 0x3f800000 - dd : 0x3f800000 + dd;
                    y = fExp(0x70, 0x9f);
                    break;
                }
            case 3:
                {
                    int sh = 40 + (int) nxMod(24);
                    int yv = fb((float) (nx() >>> sh));
                    y = yv ^ ((int) (nx() & 1) << 31);
                    x = fExp(0x40, 0xbf);
                    break;
                }
            case 4:
                x = (int) nx() | 0x80000000;
                y = fb((float) ((int) nxMod(81) - 40));
                break;
            case 5:
                {
                    int e = 0x70 + (int) nxMod(32);
                    if (e == 0x7f) {
                        e = 0x80;
                    }
                    x = fExp(e, e) & 0x7fffffff;
                    float t = POWF_TARGETS[(int) nxMod(8)] / (float) (e - 0x7f);
                    y = fb(t) + (int) delta(1 + (int) nxMod(16));
                    if ((nx() & 1) != 0) {
                        x |= 0x80000000;
                    }
                    break;
                }
            case 6:
                x = specialF(i / 10);
                y = specialF(i / 10 / NSF);
                break;
            case 7:
                if ((nx() & 1) != 0) {
                    x = specialF(nx());
                    y = (int) nx();
                } else {
                    x = (int) nx();
                    y = specialF(nx());
                }
                break;
            case 8:
                x = fExp(0x00, 0x00);
                y = fExp(0x70, 0x84);
                break;
            default:
                x = fExp(0x00, 0xfe) & 0x7fffffff;
                y = fExp(0x60, 0x8f);
        }
        in[0] = x & 0xffffffffL;
        in[1] = y & 0xffffffffL;
    }

    private int genSqrtf(long i) {
        switch ((int) (i % 3)) {
            case 0:
                return (int) nx();
            case 1:
                return specialF(i / 3);
            default:
                return fExp(0x00, 0x00);
        }
    }

    /** Fills in[0..nin(f)) with the raw input bits of case i (floats zero-extended). */
    void inputs(int f, long i, long[] in) {
        seed(f, i);
        in[1] = 0;
        switch (f) {
            case F_SIN, F_COS, F_SINCOS -> in[0] = genTrig(i);
            case F_ASIN, F_ACOS -> in[0] = genAsin(i);
            case F_ATAN2 -> genAtan2(i, in);
            case F_POW -> genPow(i, in);
            case F_FMOD -> genFmod(i, in);
            case F_SQRT -> in[0] = genSqrt(i);
            case F_POWF -> genPowf(i, in);
            default -> in[0] = genSqrtf(i) & 0xffffffffL;
        }
    }
}
