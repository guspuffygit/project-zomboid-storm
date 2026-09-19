// Port of glibc x86-64 libm (2.39 and 2.35) as seen by libPZBulletNoOpenGL64.so on an FMA/AVX2 CPU:
// sin/cos/sincos (s_sin.c, s_sincos.c, branred.c), asin/acos (e_asin.c + w_asin_compat.c,
// w_acos_compat.c), atan2 (e_atan2.c), pow (e_pow.c), fmod (e_fmod.c + w_fmod_compat.c),
// sqrt/sqrtf (sqrtsd/sqrtss + w_sqrt_compat.c) and powf (flt-32/e_powf.c).
//
// The ifunc resolvers pick the "_fma" builds of sin, cos, sincos (2.39 only), asin, acos, atan2,
// pow and powf. Those builds are the same C compiled with -mfma -mavx2, where gcc contracts
// a*b+c into vfmadd. Every Math.fma below mirrors one contraction, taken from gcc's
// -fdump-tree-optimized output for the real glibc sources (gcc 13.3 for 2.39, gcc 11.4 for
// 2.35; both give the same contractions) and cross-checked against the FMA instruction counts in
// the distro libm.so.6 disassembly. Operations that are not contracted stay as separate rounded
// Java operations. NaN results are built explicitly (x86 default NaN is 0xfff8000000000000 and
// SSE propagates the first NaN operand), so results do not depend on the host CPU.
// See docs/re-bullet/libm.md.
package io.pzstorm.storm.bullet.libm;

import static io.pzstorm.storm.bullet.libm.GlibcMathTables.d;

/**
 * Bit-exact Java reimplementation of the glibc libm functions that Bullet imports. The glibc
 * version is taken from the system property {@code storm.bullet.glibc} ("auto", the default, "2.39"
 * or "2.35"). "auto" reproduces the glibc this JVM runs on, which is the libm the native library
 * would have used; anything it cannot reproduce falls back to 2.39 with a warning.
 */
public final class GlibcMath {

    private GlibcMath() {}

    /** The glibc version being reproduced: "2.39" or "2.35". */
    public static final String GLIBC_VERSION = selectVersion();

    static final boolean V235 = GLIBC_VERSION.equals("2.35");

    private static String selectVersion() {
        String v = System.getProperty("storm.bullet.glibc", "auto").trim();
        if (v.equals("2.35") || v.equals("2.39")) {
            return v;
        }
        System.Logger log = System.getLogger(GlibcMath.class.getName());
        if (!v.equals("auto")) {
            log.log(
                    System.Logger.Level.WARNING,
                    "storm.bullet.glibc=" + v + " is not 2.39, 2.35 or auto; using 2.39");
            return "2.39";
        }
        String host = hostGlibcVersion();
        if (host == null) {
            // Not glibc (Windows, macOS, musl): the native library there is a different build, so
            // there is no host libm to match. Reproduce the Linux server library.
            return "2.39";
        }
        String picked = host.equals("2.35") ? "2.35" : "2.39";
        if (!host.equals(picked)) {
            log.log(
                    System.Logger.Level.WARNING,
                    "Host glibc is "
                            + host
                            + "; the Java Bullet port reproduces 2.39 and 2.35 only, "
                            + "using 2.39. Physics may differ from the native library by an ulp.");
        }
        if (!hostHasFmaAvx2()) {
            log.log(
                    System.Logger.Level.WARNING,
                    "Host CPU lacks FMA/AVX2: native libm would use its SSE2 builds, which the Java "
                            + "Bullet port does not reproduce. Physics may differ by an ulp.");
        }
        return picked;
    }

    /** "2.39"-style version of the glibc mapped into this process, or null when there is none. */
    static String hostGlibcVersion() {
        try {
            String libc = null;
            for (String line :
                    java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/maps"))) {
                int slash = line.indexOf('/');
                if (slash < 0) {
                    continue;
                }
                String path = line.substring(slash);
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (name.equals("libc.so.6") || name.matches("libc-2\\.\\d+\\.so")) {
                    libc = path;
                    break;
                }
            }
            if (libc == null) {
                return null;
            }
            // Every glibc embeds "GNU C Library (...) stable release version 2.NN." in libc.so.6.
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(libc));
            String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("release version (2\\.\\d+)").matcher(text);
            return m.find() ? m.group(1) : null;
        } catch (Exception | Error e) {
            return null;
        }
    }

    /** True unless /proc/cpuinfo is readable and lacks the fma or avx2 flag. */
    static boolean hostHasFmaAvx2() {
        try {
            for (String line :
                    java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("flags")) {
                    String flags = " " + line.substring(line.indexOf(':') + 1).trim() + " ";
                    return flags.contains(" fma ") && flags.contains(" avx2 ");
                }
            }
        } catch (Exception | Error e) {
            // Not Linux, or unreadable: nothing to warn about.
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // NaN helpers. x86 SSE: an operation on two operands where only one is NaN returns that NaN
    // made quiet; if both are NaN it returns the first source operand made quiet; an invalid
    // operation on non-NaN operands returns the "default NaN" (sign bit set).
    // ---------------------------------------------------------------------------------------------

    static final long SIGN = 0x8000000000000000L;
    static final double DEFAULT_NAN = Double.longBitsToDouble(0xfff8000000000000L);
    static final float DEFAULT_NAN_F = Float.intBitsToFloat(0xffc00000);

    /** glibc's NAN macro (__builtin_nan("")): positive quiet NaN. */
    static final double POSITIVE_NAN = Double.longBitsToDouble(0x7ff8000000000000L);

    static double quiet(double x) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(x) | 0x0008000000000000L);
    }

    static float quiet(float x) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(x) | 0x00400000);
    }

    /** Result of an SSE binary op (first, second) when at least one operand is NaN. */
    static double nanOf(double first, double second) {
        return quiet(first != first ? first : second);
    }

    static float nanOf(float first, float second) {
        return quiet(first != first ? first : second);
    }

    private static double negate(double x) {
        return Double.longBitsToDouble(Double.doubleToRawLongBits(x) ^ SIGN);
    }

    private static float negate(float x) {
        return Float.intBitsToFloat(Float.floatToRawIntBits(x) ^ 0x80000000);
    }

    private static long bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    private static double asDouble(long b) {
        return Double.longBitsToDouble(b);
    }

    private static int hi(double x) {
        return (int) (Double.doubleToRawLongBits(x) >>> 32);
    }

    private static int lo(double x) {
        return (int) Double.doubleToRawLongBits(x);
    }

    // =============================================================================================
    // sin / cos / sincos (s_sin.c, s_sincos.c, usncs.h, sincostab.c, branred.c)
    // =============================================================================================

    private static final double[] SINCOSTAB = d(GlibcMathTables.SINCOSTAB);

    private static final double S1 = -0x1.5555555555555p-3;
    private static final double S2 = 0x1.1111111110ECEp-7;
    private static final double S3 = -0x1.A01A019DB08B8p-13;
    private static final double S4 = 0x1.71DE27B9A7ED9p-19;
    private static final double S5 = -0x1.ADDFFC2FCDF59p-26;
    private static final double BIG = 0x1.8000000000000p45;
    private static final double HP0 = 0x1.921FB54442D18p0;
    private static final double HP1 = 0x1.1A62633145C07p-54;
    private static final double MP1 = 0x1.921FB58000000p0;
    private static final double MP2 = -0x1.DDE973C000000p-27;
    private static final double PP3 = -0x1.CB3B398000000p-55;
    private static final double PP4 = -0x1.d747f23e32ed7p-83;
    private static final double HPINV = 0x1.45F306DC9C883p-1;
    private static final double TOINT = 0x1.8000000000000p52;
    private static final double SN3 = -1.66666666666664880952546298448555E-01;
    private static final double SN5 = 8.33333214285722277379541354343671E-03;
    private static final double CS2 = 4.99999999999999999999950396842453E-01;
    private static final double CS4 = -4.16666666666664434524222570944589E-02;
    private static final double CS6 = 1.38888874007937613028114285595617E-03;

    /** do_cos, FMA build. */
    private static double doCosFma(double x, double dx) {
        if (x < 0) {
            dx = -dx;
        }
        double ax = Math.abs(x);
        double u = BIG + ax;
        x = (ax - (u - BIG)) + dx;
        double xx = x * x;
        double s = Math.fma(x * xx, Math.fma(xx, SN5, SN3), x);
        double c = xx * Math.fma(xx, Math.fma(xx, CS6, CS4), CS2);
        int k = ((int) bits(u)) << 2;
        double sn = SINCOSTAB[k],
                ssn = SINCOSTAB[k + 1],
                cs = SINCOSTAB[k + 2],
                ccs = SINCOSTAB[k + 3];
        double cor = Math.fma(-s, sn, Math.fma(-c, cs, Math.fma(-s, ssn, ccs)));
        return cs + cor;
    }

    /** do_sin, FMA build. */
    private static double doSinFma(double x, double dx) {
        double xold = x;
        if (Math.abs(x) < 0.126) {
            double xx = x * x;
            double p = Math.fma(xx, Math.fma(xx, Math.fma(xx, Math.fma(xx, S5, S4), S3), S2), S1);
            double t = Math.fma(xx, Math.fma(p, x, -(dx * 0.5)), dx);
            return x + t;
        }
        if (x <= 0) {
            dx = -dx;
        }
        double ax = Math.abs(x);
        double u = BIG + ax;
        x = ax - (u - BIG);
        double xx = x * x;
        double s = x + Math.fma(x * xx, Math.fma(xx, SN5, SN3), dx);
        double c = Math.fma(x, dx, xx * Math.fma(xx, Math.fma(xx, CS6, CS4), CS2));
        int k = ((int) bits(u)) << 2;
        double sn = SINCOSTAB[k],
                ssn = SINCOSTAB[k + 1],
                cs = SINCOSTAB[k + 2],
                ccs = SINCOSTAB[k + 3];
        double cor = Math.fma(s, cs, Math.fma(-c, sn, Math.fma(s, ccs, ssn)));
        return Math.copySign(sn + cor, xold);
    }

    /** do_cos, plain SSE2 build (glibc 2.35 sincos). */
    private static double doCosPlain(double x, double dx) {
        if (x < 0) {
            dx = -dx;
        }
        double ax = Math.abs(x);
        double u = BIG + ax;
        x = (ax - (u - BIG)) + dx;
        double xx = x * x;
        double s = x + (x * xx) * (SN3 + xx * SN5);
        double c = xx * (CS2 + xx * (CS4 + xx * CS6));
        int k = ((int) bits(u)) << 2;
        double sn = SINCOSTAB[k],
                ssn = SINCOSTAB[k + 1],
                cs = SINCOSTAB[k + 2],
                ccs = SINCOSTAB[k + 3];
        double cor = ((ccs - s * ssn) - cs * c) - sn * s;
        return cs + cor;
    }

    /** do_sin, plain SSE2 build (glibc 2.35 sincos). */
    private static double doSinPlain(double x, double dx) {
        double xold = x;
        if (Math.abs(x) < 0.126) {
            double xx = x * x;
            double p = ((((S5 * xx + S4) * xx + S3) * xx + S2) * xx) + S1;
            double t = ((p * x - 0.5 * dx) * xx + dx);
            return x + t;
        }
        if (x <= 0) {
            dx = -dx;
        }
        double ax = Math.abs(x);
        double u = BIG + ax;
        x = ax - (u - BIG);
        double xx = x * x;
        double s = x + (dx + (x * xx) * (SN3 + xx * SN5));
        double c = x * dx + xx * (CS2 + xx * (CS4 + xx * CS6));
        int k = ((int) bits(u)) << 2;
        double sn = SINCOSTAB[k],
                ssn = SINCOSTAB[k + 1],
                cs = SINCOSTAB[k + 2],
                ccs = SINCOSTAB[k + 3];
        double cor = ((ssn + s * ccs) - sn * c) + cs * s;
        return Math.copySign(sn + cor, xold);
    }

    /** reduce_sincos, FMA build. Writes a/da into out[0], out[1] and returns n. */
    private static int reduceSincosFma(double x, double[] out) {
        double t = Math.fma(x, HPINV, TOINT);
        double xn = t - TOINT;
        double y = Math.fma(-xn, MP2, Math.fma(-xn, MP1, x));
        int n = ((int) bits(t)) & 3;
        double t2 = Math.fma(-xn, PP3, y);
        double db = Math.fma(-xn, PP3, y - t2);
        double b = Math.fma(-xn, PP4, t2);
        db += Math.fma(-xn, PP4, t2 - b);
        out[0] = b;
        out[1] = db;
        return n;
    }

    /** reduce_sincos, plain SSE2 build. */
    private static int reduceSincosPlain(double x, double[] out) {
        double t = x * HPINV + TOINT;
        double xn = t - TOINT;
        double y = (x - xn * MP1) - xn * MP2;
        int n = ((int) bits(t)) & 3;
        double t1 = xn * PP3;
        double t2 = y - t1;
        double db = (y - t2) - t1;
        t1 = xn * PP4;
        double b = t2 - t1;
        db += (t2 - b) - t1;
        out[0] = b;
        out[1] = db;
        return n;
    }

    private static double doSincosFma(double a, double da, int n) {
        double r = (n & 1) != 0 ? doCosFma(a, da) : doSinFma(a, da);
        return (n & 2) != 0 ? -r : r;
    }

    private static final double[] BR = d(GlibcMathTables.BRANRED_C);
    private static final double BR_T576 = BR[0];
    private static final double BR_TM600 = BR[1];
    private static final double BR_TM24 = BR[2];
    private static final double BR_BIG = BR[3];
    private static final double BR_BIG1 = BR[4];
    private static final double BR_HP0 = BR[5];
    private static final double BR_HP1 = BR[6];
    private static final double BR_MP1 = BR[7];
    private static final double BR_MP2 = BR[8];
    private static final double BR_SPLIT = BR[9];
    private static final double[] TOVERP = d(GlibcMathTables.TOVERP);

    /** One half of __branred: accumulates x1 (or x2) * 2/pi. Writes {sum, b, bb}. */
    private static void branredPart(double xp, double[] r, double[] res) {
        double sum = 0;
        int k = (hi(xp) >> 20) & 2047;
        k = (k - 450) / 24;
        if (k < 0) {
            k = 0;
        }
        long gb = bits(BR_T576);
        int ghi = (int) (gb >>> 32) - ((k * 24) << 20);
        double gor = asDouble(((long) ghi << 32) | (gb & 0xffffffffL));
        for (int i = 0; i < 6; i++) {
            r[i] = xp * TOVERP[k + i] * gor;
            gor *= BR_TM24;
        }
        for (int i = 0; i < 3; i++) {
            double s = (r[i] + BR_BIG) - BR_BIG;
            sum += s;
            r[i] -= s;
        }
        double t = 0;
        for (int i = 0; i < 6; i++) {
            t += r[5 - i];
        }
        double bb = (((((r[0] - t) + r[1]) + r[2]) + r[3]) + r[4]) + r[5];
        double s = (t + BR_BIG) - BR_BIG;
        sum += s;
        t -= s;
        double b = t + bb;
        bb += (t - b);
        s = (sum + BR_BIG1) - BR_BIG1;
        sum -= s;
        res[0] = sum;
        res[1] = b;
        res[2] = bb;
    }

    /** __branred (plain SSE2 in every libm build). Writes a/aa into out and returns n. */
    static int branred(double x, double[] out) {
        double[] r = new double[6];
        double[] p1 = new double[3];
        double[] p2 = new double[3];
        x *= BR_TM600;
        double t = x * BR_SPLIT;
        double x1 = t - (t - x);
        double x2 = x - x1;
        branredPart(x1, r, p1);
        branredPart(x2, r, p2);
        double sum1 = p1[0], b1 = p1[1], bb1 = p1[2];
        double sum2 = p2[0], b2 = p2[1], bb2 = p2[2];
        double sum = sum1 + sum2;
        double b = b1 + b2;
        double bb = (Math.abs(b1) > Math.abs(b2)) ? (b1 - b) + b2 : (b2 - b) + b1;
        if (b > 0.5) {
            b -= 1.0;
            sum += 1.0;
        } else if (b < -0.5) {
            b += 1.0;
            sum -= 1.0;
        }
        double s = b + (bb + bb1 + bb2);
        t = ((b - s) + bb) + (bb1 + bb2);
        b = s * BR_SPLIT;
        double t1 = b - (b - s);
        double t2 = s - t1;
        b = s * BR_HP0;
        bb =
                (((t1 * BR_MP1 - b) + t1 * BR_MP2) + t2 * BR_MP1)
                        + (t2 * BR_MP2 + s * BR_HP1 + t * BR_HP0);
        s = b + bb;
        t = (b - s) + bb;
        out[0] = s;
        out[1] = t;
        return ((int) sum) & 3;
    }

    /** x / x for |x| = inf or NaN. */
    private static double selfDivide(double x) {
        return x != x ? quiet(x) : DEFAULT_NAN;
    }

    /** glibc sin (__sin_fma). */
    public static double sin(double x) {
        int k = 0x7fffffff & hi(x);
        if (k < 0x3e500000) {
            return x;
        } else if (k < 0x3feb6000) {
            return doSinFma(x, 0);
        } else if (k < 0x400368fd) {
            double t = HP0 - Math.abs(x);
            return Math.copySign(doCosFma(t, HP1), x);
        } else if (k < 0x419921FB) {
            double[] a = new double[2];
            int n = reduceSincosFma(x, a);
            return doSincosFma(a[0], a[1], n);
        } else if (k < 0x7ff00000) {
            double[] a = new double[2];
            int n = branred(x, a);
            return doSincosFma(a[0], a[1], n);
        }
        return selfDivide(x);
    }

    /** glibc cos (__cos_fma). */
    public static double cos(double x) {
        int k = 0x7fffffff & hi(x);
        if (k < 0x3e400000) {
            return 1.0;
        } else if (k < 0x3feb6000) {
            return doCosFma(x, 0);
        } else if (k < 0x400368fd) {
            double y = HP0 - Math.abs(x);
            double a = y + HP1;
            double da = (y - a) + HP1;
            return doSinFma(a, da);
        } else if (k < 0x419921FB) {
            double[] a = new double[2];
            int n = reduceSincosFma(x, a);
            return doSincosFma(a[0], a[1], n + 1);
        } else if (k < 0x7ff00000) {
            double[] a = new double[2];
            int n = branred(x, a);
            return doSincosFma(a[0], a[1], n + 1);
        }
        return selfDivide(x);
    }

    /**
     * glibc sincos: {@code out[0] = sin(x)}, {@code out[1] = cos(x)}. glibc 2.39 uses __sincos_fma;
     * glibc 2.35 has no sincos ifunc and uses the plain SSE2 build.
     */
    public static void sincos(double x, double[] out) {
        if (V235) {
            sincos235(x, out);
        } else {
            sincos239(x, out);
        }
    }

    static void sincos239(double x, double[] out) {
        sincosImpl(x, out, true);
    }

    static void sincos235(double x, double[] out) {
        sincosImpl(x, out, false);
    }

    private static void sincosImpl(double x, double[] out, boolean fma) {
        int k = hi(x) & 0x7fffffff;
        if (k < 0x400368fd) {
            if (k < 0x3e400000) {
                out[0] = x;
                out[1] = 1.0;
                return;
            } else if (k < 0x3feb6000) {
                out[0] = fma ? doSinFma(x, 0) : doSinPlain(x, 0);
                out[1] = fma ? doCosFma(x, 0) : doCosPlain(x, 0);
                return;
            }
            double y = HP0 - Math.abs(x);
            double a = y + HP1;
            double da = (y - a) + HP1;
            out[0] = Math.copySign(fma ? doCosFma(a, da) : doCosPlain(a, da), x);
            out[1] = fma ? doSinFma(a, da) : doSinPlain(a, da);
            return;
        }
        if (k < 0x7ff00000) {
            double[] ad = new double[2];
            int n;
            if (k < 0x419921FB) {
                n = fma ? reduceSincosFma(x, ad) : reduceSincosPlain(x, ad);
            } else {
                n = branred(x, ad);
            }
            n = n & 3;
            double a = ad[0], da = ad[1];
            if (n == 1 || n == 2) {
                a = -a;
                da = -da;
            }
            double s = fma ? doSinFma(a, da) : doSinPlain(a, da);
            double xx = fma ? doCosFma(a, da) : doCosPlain(a, da);
            double c = (n & 2) != 0 ? -xx : xx;
            if ((n & 1) != 0) {
                out[1] = s;
                out[0] = c;
            } else {
                out[0] = s;
                out[1] = c;
            }
            return;
        }
        out[0] = out[1] = selfDivide(x);
    }

    // =============================================================================================
    // asin / acos (e_asin.c, asincos.tbl, root.tbl, powtwo.tbl, uasncs.h) + compat wrappers
    // =============================================================================================

    private static final double[] ASNCS = d(GlibcMathTables.ASNCS);
    private static final double[] INROOT = d(GlibcMathTables.INROOT);
    private static final double[] POWTWO = d(GlibcMathTables.POWTWO);
    private static final double F1 = 1.66666666666664110590506577996662E-01;
    private static final double F2 = 7.50000000026122686814431784722623E-02;
    private static final double F3 = 4.46428561421059750978517350006940E-02;
    private static final double F4 = 3.03821268582119319911193410625235E-02;
    private static final double F5 = 2.23551211026525610742786300334557E-02;
    private static final double F6 = 1.81382903404565056280372531963613E-02;
    private static final double T24 = 16777216.0;
    private static final double T27 = 134217728.0;
    private static final double RT0 = 9.99999999859990725855365213134618E-01;
    private static final double RT1 = 4.99999999495955425917856814202739E-01;
    private static final double RT2 = 3.75017500867345182581453026130850E-01;
    private static final double RT3 = 3.12523626554518656309172508769531E-01;

    /** (((((f6*z+f5)*z+f4)*z+f3)*z+f2)*z+f1), contracted. */
    private static double asinPoly(double z) {
        return Math.fma(
                Math.fma(Math.fma(Math.fma(Math.fma(z, F6, F5), z, F4), z, F3), z, F2), z, F1);
    }

    /**
     * Table-driven range shared by asin and acos: returns t = c1*xx + p where p = xx*xx*poly +
     * c_last, with the polynomial coefficients asncs[n+2 .. n+2+deg]. {@code deg} is the index of
     * the highest coefficient relative to n.
     */
    private static double asinTable(double xx, int n, int hiIdx) {
        double poly = Math.fma(ASNCS[n + hiIdx], xx, ASNCS[n + hiIdx - 1]);
        for (int j = hiIdx - 2; j >= 2; j--) {
            poly = Math.fma(poly, xx, ASNCS[n + j]);
        }
        double p = Math.fma(xx * xx, poly, ASNCS[n + hiIdx + 1]);
        return Math.fma(ASNCS[n + 1], xx, p);
    }

    /** Near |x| = 1: computes {y, cc, z} for asin (t24 split) or acos (t27 split). */
    private static void asinNearOne(double z, boolean acosSplit, double[] out) {
        int k = hi(z);
        double t = INROOT[(k & 0x001fffff) >> 14] * POWTWO[511 - (k >> 21)];
        double r = Math.fma(-(t * t), z, 1.0);
        t *= Math.fma(Math.fma(Math.fma(r, RT3, RT2), r, RT1), r, RT0);
        double c = z * t;
        double w = Math.fma(-(t * 0.5), c, 1.5);
        double y;
        if (acosSplit) {
            y = Math.fma(-c, T27, Math.fma(c, T27, c));
        } else {
            y = (c + T24) - T24;
        }
        double cc = Math.fma(-y, y, z) / Math.fma(w, c, y);
        out[0] = y;
        out[1] = cc;
    }

    /** __ieee754_asin_fma. */
    static double ieee754Asin(double x) {
        int m = hi(x);
        int k = 0x7fffffff & m;
        if (k < 0x3e500000) {
            return x;
        } else if (k < 0x3fc00000) {
            double x2 = x * x;
            return Math.fma(asinPoly(x2), x * x2, x);
        } else if (k < 0x3fef0000) {
            int n;
            int hiIdx;
            if (k < 0x3fe00000) {
                n =
                        k < 0x3fd00000
                                ? 11 * ((k & 0x000fffff) >> 15)
                                : 11 * ((k & 0x000fffff) >> 14) + 352;
                hiIdx = 6;
            } else if (k < 0x3fe80000) {
                n = 1056 + ((k & 0x000fe000) >> 11) * 3;
                hiIdx = 7;
            } else if (k < 0x3fed8000) {
                n = 992 + ((k & 0x000fe000) >> 13) * 13;
                hiIdx = 8;
            } else if (k < 0x3fee8000) {
                n = 884 + ((k & 0x000fe000) >> 13) * 14;
                hiIdx = 9;
            } else {
                n = 768 + ((k & 0x000fe000) >> 13) * 15;
                hiIdx = 10;
            }
            double xx = (m > 0) ? x - ASNCS[n] : -x - ASNCS[n];
            double t = asinTable(xx, n, hiIdx);
            double res = ASNCS[n + hiIdx + 2] + t;
            return (m > 0) ? res : -res;
        } else if (k < 0x3ff00000) {
            double z = 0.5 * ((m > 0) ? (1.0 - x) : (1.0 + x));
            double[] yc = new double[2];
            asinNearOne(z, false, yc);
            double y = yc[0], cc = yc[1];
            double p = asinPoly(z) * z;
            double cor = Math.fma(-((y + cc) * 2.0), p, Math.fma(-cc, 2.0, HP1));
            double res1 = Math.fma(-y, 2.0, HP0);
            double res = cor + res1;
            return (m > 0) ? res : -res;
        } else if (k == 0x3ff00000 && lo(x) == 0) {
            return (m > 0) ? HP0 : -HP0;
        }
        // (x - x) / (x - x) in 2.39, x + x (NaN) or inf/inf in 2.35. |x| > 1 never gets here
        // through
        // the asin wrapper, so only NaN arrives: both versions return x made quiet.
        return x != x ? quiet(x) : DEFAULT_NAN;
    }

    /** __ieee754_acos_fma. */
    static double ieee754Acos(double x) {
        int m = hi(x);
        int k = 0x7fffffff & m;
        if (k < 0x3c880000) {
            return HP0;
        } else if (k < 0x3fc00000) {
            double x2 = x * x;
            double r = HP0 - x;
            double cor = Math.fma(-asinPoly(x2), x * x2, ((HP0 - r) - x) + HP1);
            return r + cor;
        } else if (k < 0x3fef0000) {
            int n;
            int hiIdx;
            if (k < 0x3fe00000) {
                n =
                        k < 0x3fd00000
                                ? 11 * ((k & 0x000fffff) >> 15)
                                : 11 * ((k & 0x000fffff) >> 14) + 352;
                hiIdx = 6;
            } else if (k < 0x3fe80000) {
                n = 1056 + ((k & 0x000fe000) >> 11) * 3;
                hiIdx = 7;
            } else if (k < 0x3fed8000) {
                n = 992 + ((k & 0x000fe000) >> 13) * 13;
                hiIdx = 8;
            } else if (k < 0x3fee8000) {
                n = 884 + ((k & 0x000fe000) >> 13) * 14;
                hiIdx = 9;
            } else {
                n = 768 + ((k & 0x000fe000) >> 13) * 15;
                hiIdx = 10;
            }
            double xx = (m > 0) ? x - ASNCS[n] : -x - ASNCS[n];
            double t = asinTable(xx, n, hiIdx);
            double last = ASNCS[n + hiIdx + 2];
            double y = (m > 0) ? (HP0 - last) : (last + HP0);
            t = (m > 0) ? (HP1 - t) : (t + HP1);
            return t + y;
        } else if (k < 0x3ff00000) {
            double z = 0.5 * ((m > 0) ? (1.0 - x) : (1.0 + x));
            double[] yc = new double[2];
            asinNearOne(z, true, yc);
            double y = yc[0], cc = yc[1];
            double p = asinPoly(z) * z;
            double q = p * (y + cc);
            if (m < 0) {
                double cor = (HP1 - cc) - q;
                double res1 = HP0 - y;
                double res = cor + res1;
                return res * 2.0;
            } else {
                double cor = cc + q;
                double res = y + cor;
                return res * 2.0;
            }
        } else if (k == 0x3ff00000 && lo(x) == 0) {
            return (m > 0) ? 0 : 2.0 * HP0;
        }
        return x != x ? quiet(x) : DEFAULT_NAN;
    }

    /** glibc asin@GLIBC_2.2.5: |x| > 1 goes to __kernel_standard, which returns NAN. */
    public static double asin(double x) {
        if (Math.abs(x) > 1.0) {
            return POSITIVE_NAN;
        }
        return ieee754Asin(x);
    }

    /** glibc acos@GLIBC_2.2.5: |x| > 1 goes to __kernel_standard, which returns NAN. */
    public static double acos(double x) {
        if (Math.abs(x) > 1.0) {
            return POSITIVE_NAN;
        }
        return ieee754Acos(x);
    }

    // =============================================================================================
    // atan2 (e_atan2.c, atnat2.h, uatan.tbl)
    // =============================================================================================

    private static final double[] CIJ = d(GlibcMathTables.CIJ);
    private static final double[] ATD = d(GlibcMathTables.ATAN_D);
    private static final double D3 = ATD[0], D5 = ATD[1], D7 = ATD[2], D9 = ATD[3], D11 = ATD[4];
    private static final double D13 = ATD[5];
    private static final double[] ATC = d(GlibcMathTables.ATAN_C);
    private static final double INV16 = ATC[0];
    private static final double OPI = ATC[1];
    private static final double OPI1 = ATC[2];
    private static final double MOPI = ATC[3];
    private static final double HPI = ATC[4];
    private static final double HPI1 = ATC[5];
    private static final double MHPI = ATC[6];
    private static final double QPI = ATC[7];
    private static final double MQPI = ATC[8];
    private static final double TQPI = ATC[9];
    private static final double MTQPI = ATC[10];
    private static final double TWO500 = ATC[11];
    private static final double TWOM500 = ATC[12];
    private static final double TWO52 = 0x1.0p52;

    private static double atanPoly(double v) {
        return Math.fma(
                Math.fma(Math.fma(Math.fma(Math.fma(v, D13, D11), v, D9), v, D7), v, D5), v, D3);
    }

    /** cij[i][3..6] Horner in v, contracted. */
    private static double cijPoly3(int i, double v) {
        return Math.fma(
                Math.fma(Math.fma(CIJ[i + 6], v, CIJ[i + 5]), v, CIJ[i + 4]), v, CIJ[i + 3]);
    }

    /** glibc atan2 (__ieee754_atan2_fma; the atan2@GLIBC_2.2.5 wrapper returns it unchanged). */
    public static double atan2(double y, double x) {
        int ux = hi(x), dx = lo(x);
        if ((ux & 0x7ff00000) == 0x7ff00000 && ((ux & 0x000fffff) | dx) != 0) {
            return quiet(x); // x + y, x is the first source operand
        }
        int uy = hi(y), dy = lo(y);
        if ((uy & 0x7ff00000) == 0x7ff00000 && ((uy & 0x000fffff) | dy) != 0) {
            return quiet(y); // y + y
        }
        if (uy == 0x00000000) {
            if (dy == 0) {
                return (ux & 0x80000000) == 0 ? 0 : OPI;
            }
        } else if (uy == 0x80000000) {
            if (dy == 0) {
                return (ux & 0x80000000) == 0 ? -0.0 : MOPI;
            }
        }
        if (x == 0) {
            return (uy & 0x80000000) == 0 ? HPI : MHPI;
        }
        if (ux == 0x7ff00000) {
            if (dx == 0) {
                if (uy == 0x7ff00000) {
                    if (dy == 0) {
                        return QPI;
                    }
                } else if (uy == 0xfff00000) {
                    if (dy == 0) {
                        return MQPI;
                    }
                } else {
                    return (uy & 0x80000000) == 0 ? 0 : -0.0;
                }
            }
        } else if (ux == 0xfff00000) {
            if (dx == 0) {
                if (uy == 0x7ff00000) {
                    if (dy == 0) {
                        return TQPI;
                    }
                } else if (uy == 0xfff00000) {
                    if (dy == 0) {
                        return MTQPI;
                    }
                } else {
                    return (uy & 0x80000000) == 0 ? OPI : MOPI;
                }
            }
        }
        if (uy == 0x7ff00000) {
            if (dy == 0) {
                return HPI;
            }
        } else if (uy == 0xfff00000) {
            if (dy == 0) {
                return MHPI;
            }
        }

        double ax = (x < 0) ? -x : x;
        double ay = (y < 0) ? -y : y;
        int de = (uy & 0x7ff00000) - (ux & 0x7ff00000);
        if (de >= 59768832) {
            return (y > 0) ? HPI : MHPI;
        } else if (de <= -59768832) {
            if (x > 0) {
                return Math.copySign(ay / ax, y);
            }
            return (y > 0) ? OPI : MOPI;
        }
        if (ax < TWOM500 || ay < TWOM500) {
            ax *= TWO500;
            ay *= TWO500;
        }
        if (ax > TWO500 || ay > TWO500) {
            ax *= TWOM500;
            ay *= TWOM500;
        }

        double u, du, v, vv;
        if (ay < ax) {
            u = ay / ax;
            v = ax * u;
            vv = Math.fma(ax, u, -v);
            du = ((ay - v) - vv) / ax;
        } else {
            u = ax / ay;
            v = ay * u;
            vv = Math.fma(ay, u, -v);
            du = ((ax - v) - vv) / ay;
        }

        double zz, z, t1, t2, t3, cor;
        int i;
        if (x > 0) {
            // (i) x>0, |y|<|x|: atan(ay/ax)
            if (ay < ax) {
                if (u < INV16) {
                    v = u * u;
                    zz = Math.fma(u * v, atanPoly(v), du);
                    z = u + zz;
                    return Math.copySign(z, y);
                }
                i = ((int) (Math.fma(u, 256, TWO52) - TWO52) - 16) * 7;
                t3 = u - CIJ[i];
                v = t3 + du;
                double dv = (Math.abs(t3) > Math.abs(du)) ? (t3 - v) + du : (du - v) + t3;
                t1 = CIJ[i + 1];
                t2 = CIJ[i + 2];
                zz = Math.fma(v, t2, Math.fma(dv, t2, (v * v) * cijPoly3(i, v)));
                z = t1 + zz;
                return Math.copySign(z, y);
            }
            // (ii) x>0, |x|<=|y|: pi/2-atan(ax/ay)
            if (u < INV16) {
                v = u * u;
                zz = (u * v) * atanPoly(v);
                t2 = HPI - u;
                cor = (Math.abs(u) < HPI) ? (HPI - t2) - u : HPI - (u + t2);
                t3 = ((HPI1 + cor) - du) - zz;
                z = t2 + t3;
                return Math.copySign(z, y);
            }
            i = ((int) (Math.fma(u, 256, TWO52) - TWO52) - 16) * 7;
            v = (u - CIJ[i]) + du;
            zz = Math.fma(-Math.fma(cijPoly3(i, v), v, CIJ[i + 2]), v, HPI1);
            t1 = HPI - CIJ[i + 1];
            z = zz + t1;
            return Math.copySign(z, y);
        }
        // (iii) x<0, |x|<|y|: pi/2+atan(ax/ay)
        if (ax < ay) {
            if (u < INV16) {
                v = u * u;
                zz = (u * v) * atanPoly(v);
                t2 = u + HPI;
                cor = (Math.abs(u) < HPI) ? (HPI - t2) + u : (u - t2) + HPI;
                t3 = ((HPI1 + cor) + du) + zz;
                z = t2 + t3;
                return Math.copySign(z, y);
            }
            i = ((int) (Math.fma(u, 256, TWO52) - TWO52) - 16) * 7;
            v = (u - CIJ[i]) + du;
            zz = Math.fma(Math.fma(cijPoly3(i, v), v, CIJ[i + 2]), v, HPI1);
            t1 = CIJ[i + 1] + HPI;
            z = zz + t1;
            return Math.copySign(z, y);
        }
        // (iv) x<0, |y|<=|x|: pi-atan(ax/ay)
        if (u < INV16) {
            v = u * u;
            zz = (u * v) * atanPoly(v);
            t2 = OPI - u;
            cor = (Math.abs(u) < OPI) ? (OPI - t2) - u : OPI - (u + t2);
            t3 = ((OPI1 + cor) - du) - zz;
            z = t2 + t3;
            return Math.copySign(z, y);
        }
        i = ((int) (Math.fma(u, 256, TWO52) - TWO52) - 16) * 7;
        v = (u - CIJ[i]) + du;
        zz = Math.fma(-Math.fma(cijPoly3(i, v), v, CIJ[i + 2]), v, OPI1);
        t1 = OPI - CIJ[i + 1];
        z = zz + t1;
        return Math.copySign(z, y);
    }

    // =============================================================================================
    // pow (e_pow.c, e_pow_log_data.c, e_exp_data.c, math_err.c)
    // =============================================================================================

    private static final double[] POW_LOG_TAB = d(GlibcMathTables.POW_LOG_TAB);
    private static final double[] PA = d(GlibcMathTables.POW_LOG_POLY);
    private static final double LN2HI = asDouble(GlibcMathTables.POW_LN2[0]);
    private static final double LN2LO = asDouble(GlibcMathTables.POW_LN2[1]);
    private static final double INVLN2N = asDouble(GlibcMathTables.EXP_C[0]);
    private static final double EXP_SHIFT = asDouble(GlibcMathTables.EXP_C[1]);
    private static final double NEGLN2HIN = asDouble(GlibcMathTables.EXP_C[2]);
    private static final double NEGLN2LON = asDouble(GlibcMathTables.EXP_C[3]);
    private static final double[] EC = d(GlibcMathTables.EXP_POLY);
    private static final long[] EXP_TAB = GlibcMathTables.EXP_TAB;
    private static final long POW_OFF = 0x3fe6955500000000L;
    private static final long ONE_BITS = 0x3ff0000000000000L;
    private static final long INF_BITS = 0x7ff0000000000000L;
    private static final int POW_SIGN_BIAS = 0x800 << 7;

    private static double oflow(int sign) {
        return (sign != 0 ? -0x1p769 : 0x1p769) * 0x1p769;
    }

    private static double uflow(int sign) {
        return (sign != 0 ? -0x1p-767 : 0x1p-767) * 0x1p-767;
    }

    private static boolean zeroInfNan(long i) {
        return Long.compareUnsigned(2 * i - 1, 2 * INF_BITS - 1) >= 0;
    }

    private static int checkInt(long iy) {
        int e = (int) (iy >>> 52) & 0x7ff;
        if (e < 0x3ff) {
            return 0;
        }
        if (e > 0x3ff + 52) {
            return 2;
        }
        if ((iy & ((1L << (0x3ff + 52 - e)) - 1)) != 0) {
            return 0;
        }
        if ((iy & (1L << (0x3ff + 52 - e))) != 0) {
            return 1;
        }
        return 2;
    }

    private static boolean isSignaling(double x) {
        long ix = bits(x);
        // issignaling_inline: 2 * (ix ^ 0x0008000000000000) > 2 * 0x7ff8000000000000
        return Long.compareUnsigned(2 * (ix ^ 0x0008000000000000L), 2 * 0x7ff8000000000000L) > 0;
    }

    private static double powSpecialcase(double tmp, long sbits, long ki) {
        double scale, y;
        if ((ki & 0x80000000L) == 0) {
            sbits -= 1009L << 52;
            scale = asDouble(sbits);
            return 0x1p1009 * Math.fma(tmp, scale, scale);
        }
        sbits += 1022L << 52;
        scale = asDouble(sbits);
        double st = scale * tmp;
        y = scale + st;
        if (Math.abs(y) < 1.0) {
            double one = y < 0.0 ? -1.0 : 1.0;
            double lo = (scale - y) + st;
            double hi = one + y;
            lo += ((one - hi) + y);
            y = (hi + lo) - one;
            if (y == 0.0) {
                y = asDouble(sbits & SIGN);
            }
        }
        return 0x1p-1022 * y;
    }

    /** glibc pow@GLIBC_2.29 (__ieee754_pow_fma). */
    public static double pow(double x, double y) {
        int signBias = 0;
        long ix = bits(x);
        long iy = bits(y);
        int topx = (int) (ix >>> 52);
        int topy = (int) (iy >>> 52);
        if (Integer.compareUnsigned(topx - 0x001, 0x7ff - 0x001) >= 0
                || Integer.compareUnsigned((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0) {
            if (zeroInfNan(iy)) {
                if (2 * iy == 0) {
                    return isSignaling(x) ? nanOf(x, y) : 1.0;
                }
                if (ix == ONE_BITS) {
                    return isSignaling(y) ? nanOf(x, y) : 1.0;
                }
                if (Long.compareUnsigned(2 * ix, 2 * INF_BITS) > 0
                        || Long.compareUnsigned(2 * iy, 2 * INF_BITS) > 0) {
                    return nanOf(x, y);
                }
                if (2 * ix == 2 * ONE_BITS) {
                    return 1.0;
                }
                if ((Long.compareUnsigned(2 * ix, 2 * ONE_BITS) < 0) == ((iy >>> 63) == 0)) {
                    return 0.0;
                }
                return y * y;
            }
            if (zeroInfNan(ix)) {
                double x2 = x != x ? quiet(x) : x * x;
                if ((ix >>> 63) != 0 && checkInt(iy) == 1) {
                    x2 = negate(x2);
                    signBias = 1;
                }
                if (2 * ix == 0 && (iy >>> 63) != 0) {
                    return (signBias != 0 ? -1.0 : 1.0) / 0.0;
                }
                if ((iy >>> 63) != 0) {
                    return x2 != x2 ? x2 : 1 / x2;
                }
                return x2;
            }
            if ((ix >>> 63) != 0) {
                int yint = checkInt(iy);
                if (yint == 0) {
                    return DEFAULT_NAN;
                }
                if (yint == 1) {
                    signBias = POW_SIGN_BIAS;
                }
                ix &= 0x7fffffffffffffffL;
                topx &= 0x7ff;
            }
            if (Integer.compareUnsigned((topy & 0x7ff) - 0x3be, 0x43e - 0x3be) >= 0) {
                if (ix == ONE_BITS) {
                    return 1.0;
                }
                if ((topy & 0x7ff) < 0x3be) {
                    return ix > ONE_BITS ? 1.0 + y : 1.0 - y;
                }
                return (ix > ONE_BITS) == (topy < 0x800) ? oflow(0) : uflow(0);
            }
            if (topx == 0) {
                ix = bits(x * 0x1p52);
                ix &= 0x7fffffffffffffffL;
                ix -= 52L << 52;
            }
        }

        // log_inline(ix, &lo)
        long tmp = ix - POW_OFF;
        int i = (int) ((tmp >>> 45) & 127);
        int k = (int) (tmp >> 52);
        long iz = ix - (tmp & (0xfffL << 52));
        double z = asDouble(iz);
        double kd = k;
        double invc = POW_LOG_TAB[4 * i];
        double logc = POW_LOG_TAB[4 * i + 2];
        double logctail = POW_LOG_TAB[4 * i + 3];
        double r = Math.fma(z, invc, -1.0);
        double t1 = Math.fma(kd, LN2HI, logc);
        double t2 = t1 + r;
        double lo1 = Math.fma(kd, LN2LO, logctail);
        double lo2 = (t1 - t2) + r;
        double ar = PA[0] * r;
        double ar2 = r * ar;
        double ar3 = r * ar2;
        double hi = t2 + ar2;
        double lo3 = Math.fma(r, ar, -ar2);
        double lo4 = (t2 - hi) + ar2;
        double p =
                Math.fma(
                        ar2,
                        Math.fma(ar2, Math.fma(r, PA[6], PA[5]), Math.fma(r, PA[4], PA[3])),
                        Math.fma(r, PA[2], PA[1]));
        double lo = Math.fma(ar3, p, ((lo1 + lo2) + lo3) + lo4);
        double ly = hi + lo;
        double ltail = (hi - ly) + lo;

        double ehi = y * ly;
        double elo = Math.fma(y, ltail, Math.fma(y, ly, -ehi));

        // exp_inline(ehi, elo, signBias)
        int abstop = (int) (bits(ehi) >>> 52) & 0x7ff;
        if (Integer.compareUnsigned(abstop - 0x3c9, 0x408 - 0x3c9) >= 0) {
            if (Integer.compareUnsigned(abstop - 0x3c9, 0x80000000) >= 0) {
                double one = 1.0 + ehi;
                return signBias != 0 ? -one : one;
            }
            if (abstop >= 0x409) {
                if ((bits(ehi) >>> 63) != 0) {
                    return uflow(signBias);
                }
                return oflow(signBias);
            }
            abstop = 0;
        }
        double ekd = Math.fma(ehi, INVLN2N, EXP_SHIFT);
        long ki = bits(ekd);
        ekd -= EXP_SHIFT;
        double er = Math.fma(ekd, NEGLN2LON, Math.fma(ekd, NEGLN2HIN, ehi));
        er += elo;
        int idx = 2 * (int) (ki & 127);
        long top = (ki + (signBias & 0xffffffffL)) << 45;
        double tail = asDouble(EXP_TAB[idx]);
        long sbits = EXP_TAB[idx + 1] + top;
        double r2 = er * er;
        double etmp =
                Math.fma(
                        r2 * r2,
                        Math.fma(er, EC[3], EC[2]),
                        Math.fma(r2, Math.fma(er, EC[1], EC[0]), er + tail));
        if (abstop == 0) {
            return powSpecialcase(etmp, sbits, ki);
        }
        double scale = asDouble(sbits);
        return Math.fma(etmp, scale, scale);
    }

    // =============================================================================================
    // powf (flt-32/e_powf.c, e_powf_log2_data.c, e_exp2f_data.c, math_errf.c)
    // =============================================================================================

    private static final double[] POWF_LOG2_TAB = d(GlibcMathTables.POWF_LOG2_TAB);
    private static final double[] PFA = d(GlibcMathTables.POWF_LOG2_POLY);
    private static final long[] EXP2F_TAB = GlibcMathTables.EXP2F_TAB;
    private static final double EXP2F_SHIFT = asDouble(GlibcMathTables.EXP2F_SHIFT[0]);
    private static final double[] E2C = d(GlibcMathTables.EXP2F_POLY);
    private static final int POWF_SIGN_BIAS = 1 << (5 + 11);

    private static float xflowf(int sign, float y) {
        return (sign != 0 ? -y : y) * y;
    }

    private static boolean zeroInfNanF(int i) {
        return Integer.compareUnsigned(2 * i - 1, 2 * 0x7f800000 - 1) >= 0;
    }

    private static int checkIntF(int iy) {
        int e = (iy >>> 23) & 0xff;
        if (e < 0x7f) {
            return 0;
        }
        if (e > 0x7f + 23) {
            return 2;
        }
        if ((iy & ((1 << (0x7f + 23 - e)) - 1)) != 0) {
            return 0;
        }
        if ((iy & (1 << (0x7f + 23 - e))) != 0) {
            return 1;
        }
        return 2;
    }

    private static boolean isSignaling(float x) {
        int ix = Float.floatToRawIntBits(x);
        return Integer.compareUnsigned(2 * (ix ^ 0x00400000), 2 * 0x7fc00000) > 0;
    }

    /** glibc powf@GLIBC_2.27 (__powf_fma). */
    public static float powf(float x, float y) {
        int signBias = 0;
        int ix = Float.floatToRawIntBits(x);
        int iy = Float.floatToRawIntBits(y);
        if (Integer.compareUnsigned(ix - 0x00800000, 0x7f800000 - 0x00800000) >= 0
                || zeroInfNanF(iy)) {
            if (zeroInfNanF(iy)) {
                if (2 * iy == 0) {
                    return isSignaling(x) ? nanOf(x, y) : 1.0f;
                }
                if (ix == 0x3f800000) {
                    return isSignaling(y) ? nanOf(x, y) : 1.0f;
                }
                if (Integer.compareUnsigned(2 * ix, 2 * 0x7f800000) > 0
                        || Integer.compareUnsigned(2 * iy, 2 * 0x7f800000) > 0) {
                    return nanOf(x, y);
                }
                if (2 * ix == 2 * 0x3f800000) {
                    return 1.0f;
                }
                if ((Integer.compareUnsigned(2 * ix, 2 * 0x3f800000) < 0)
                        == ((iy & 0x80000000) == 0)) {
                    return 0.0f;
                }
                return y * y;
            }
            if (zeroInfNanF(ix)) {
                float x2 = x != x ? quiet(x) : x * x;
                if ((ix & 0x80000000) != 0 && checkIntF(iy) == 1) {
                    x2 = negate(x2);
                    signBias = 1;
                }
                if (2 * ix == 0 && (iy & 0x80000000) != 0) {
                    return (signBias != 0 ? -1.0f : 1.0f) / 0.0f;
                }
                if ((iy & 0x80000000) != 0) {
                    return x2 != x2 ? x2 : 1 / x2;
                }
                return x2;
            }
            if ((ix & 0x80000000) != 0) {
                int yint = checkIntF(iy);
                if (yint == 0) {
                    return DEFAULT_NAN_F;
                }
                if (yint == 1) {
                    signBias = POWF_SIGN_BIAS;
                }
                ix &= 0x7fffffff;
            }
            if (ix < 0x00800000) {
                ix = Float.floatToRawIntBits(x * 0x1p23f);
                ix &= 0x7fffffff;
                ix -= 23 << 23;
            }
        }

        // log2_inline(ix)
        int tmp = ix - 0x3f330000;
        int i = (tmp >>> 19) & 15;
        int top = tmp & 0xff800000;
        int iz = ix - top;
        int k = top >> 23;
        double invc = POWF_LOG2_TAB[2 * i];
        double logc = POWF_LOG2_TAB[2 * i + 1];
        double z = Float.intBitsToFloat(iz);
        double r = Math.fma(invc, z, -1.0);
        double y0 = logc + (double) k;
        double r2 = r * r;
        double ly = Math.fma(r, PFA[0], PFA[1]);
        double p = Math.fma(r, PFA[2], PFA[3]);
        double r4 = r2 * r2;
        double q = Math.fma(r, PFA[4], y0);
        q = Math.fma(r2, p, q);
        double logx = Math.fma(ly, r4, q);

        double ylogx = (double) y * logx;
        if (((bits(ylogx) >>> 47) & 0xffff) >= (bits(126.0) >>> 47)) {
            if (ylogx > 0x1.fffffffd1d571p+6) {
                return xflowf(signBias, 0x1p97f);
            }
            // ylogx > 0x1.fffffffa3aae2p+6 only overflows when rounding away from zero; the
            // rounding
            // mode is always to-nearest here.
            if (ylogx <= -150.0) {
                return xflowf(signBias, 0x1p-95f);
            }
            if (ylogx < -149.0) {
                return xflowf(signBias, 0x1.4p-75f);
            }
        }

        // exp2_inline(ylogx, signBias)
        double kd = ylogx + EXP2F_SHIFT;
        long ki = bits(kd);
        kd -= EXP2F_SHIFT;
        double er = ylogx - kd;
        long t = EXP2F_TAB[(int) (ki & 31)];
        long ski = ki + (signBias & 0xffffffffL);
        t += ski << 47;
        double s = asDouble(t);
        double ez = Math.fma(er, E2C[0], E2C[1]);
        double er2 = er * er;
        double ey = Math.fma(er, E2C[2], 1.0);
        ey = Math.fma(ez, er2, ey);
        ey *= s;
        return (float) ey;
    }

    // =============================================================================================
    // fmod (e_fmod.c: 2.35 bit-by-bit loop, 2.39 rewrite) + fmod@GLIBC_2.2.5 compat wrapper
    // =============================================================================================

    /** glibc fmod@GLIBC_2.2.5 for the selected version. */
    public static double fmod(double x, double y) {
        return V235 ? fmod235(x, y) : fmod239(x, y);
    }

    /** w_fmod_compat: fmod(+-Inf, y) and fmod(x, 0) go to __kernel_standard (zero/zero). */
    private static boolean fmodCompatDomain(double x, double y) {
        return (Double.isInfinite(x) || y == 0.0) && y == y && x == x;
    }

    static double fmod235(double x, double y) {
        if (fmodCompatDomain(x, y)) {
            return DEFAULT_NAN;
        }
        long hx = bits(x);
        long hy = bits(y);
        long sx = hx & SIGN;
        hx ^= sx;
        hy &= 0x7fffffffffffffffL;
        if (hy == 0 || hx >= 0x7ff0000000000000L || hy > 0x7ff0000000000000L) {
            // (x*y)/(x*y)
            return (x != x || y != y) ? nanOf(x, y) : DEFAULT_NAN;
        }
        if (hx <= hy) {
            if (hx < hy) {
                return x;
            }
            return asDouble(sx);
        }
        int ix;
        int iy;
        if (hx < 0x0010000000000000L) {
            ix = -1022;
            for (long i = hx << 11; i > 0; i <<= 1) {
                ix -= 1;
            }
        } else {
            ix = (int) (hx >> 52) - 1023;
        }
        if (hy < 0x0010000000000000L) {
            iy = -1022;
            for (long i = hy << 11; i > 0; i <<= 1) {
                iy -= 1;
            }
        } else {
            iy = (int) (hy >> 52) - 1023;
        }
        if (ix >= -1022) {
            hx = 0x0010000000000000L | (0x000fffffffffffffL & hx);
        } else {
            hx <<= -1022 - ix;
        }
        if (iy >= -1022) {
            hy = 0x0010000000000000L | (0x000fffffffffffffL & hy);
        } else {
            hy <<= -1022 - iy;
        }
        int n = ix - iy;
        long hz;
        while (n-- != 0) {
            hz = hx - hy;
            if (hz < 0) {
                hx += hx;
            } else {
                if (hz == 0) {
                    return asDouble(sx);
                }
                hx = hz + hz;
            }
        }
        hz = hx - hy;
        if (hz >= 0) {
            hx = hz;
        }
        if (hx == 0) {
            return asDouble(sx);
        }
        while (hx < 0x0010000000000000L) {
            hx += hx;
            iy -= 1;
        }
        if (iy >= -1022) {
            hx = (hx - 0x0010000000000000L) | ((long) (iy + 1023) << 52);
            return asDouble(hx | sx);
        }
        hx >>= -1022 - iy;
        return asDouble(hx | sx);
    }

    private static double makeDouble(long x, long ep, long s) {
        int lz = Long.numberOfLeadingZeros(x) - 11;
        x <<= lz;
        ep -= lz;
        if (ep < 0 || x == 0) {
            x >>>= -ep;
            ep = 0;
        }
        return asDouble(s + x + (ep << 52));
    }

    static double fmod239(double x, double y) {
        if (fmodCompatDomain(x, y)) {
            return DEFAULT_NAN;
        }
        long hx = bits(x);
        long hy = bits(y);
        long sx = hx & SIGN;
        hx ^= sx;
        hy &= ~SIGN;
        final long expMask = 0x7ff0000000000000L;
        if (hx < hy) {
            if (hy > expMask) {
                return nanOf(x, y); // x * y
            }
            return x;
        }
        int ex = (int) (hx >>> 52);
        int ey = (int) (hy >>> 52);
        int expDiff = ex - ey;
        if (ey < 0x7ff - 11 && ey > 52 && expDiff <= 11) {
            long mx = (hx << 11) | SIGN;
            long my = (hy << 11) | SIGN;
            mx = Long.remainderUnsigned(mx, my >>> expDiff);
            if (mx == 0) {
                return asDouble(sx);
            }
            int shift = Long.numberOfLeadingZeros(mx);
            ex -= shift + 1;
            mx <<= shift;
            mx = sx | (mx >>> 11);
            return asDouble(mx + ((long) ex << 52));
        }
        if (hy == 0 || hx >= expMask) {
            if (hx > expMask) {
                return nanOf(x, y); // x * y
            }
            return DEFAULT_NAN; // (x*y)/(x*y)
        }
        if (ex == 0) {
            return asDouble(sx | Long.remainderUnsigned(hx, hy));
        }
        long mx = (hx & 0x000fffffffffffffL) | 0x0010000000000000L;
        long my = (hy & 0x000fffffffffffffL) | 0x0010000000000000L;
        int leadZerosMy = 11;
        ey--;
        if (ey < 0) {
            my = hy;
            ey = 0;
            expDiff--;
            leadZerosMy = Long.numberOfLeadingZeros(my);
        }
        int tailZerosMy = Long.numberOfTrailingZeros(my);
        int sidesZeroes = leadZerosMy + tailZerosMy;
        int rightShift = Math.min(expDiff, tailZerosMy);
        my >>>= rightShift;
        expDiff -= rightShift;
        ey += rightShift;
        int leftShift = Math.min(expDiff, 11);
        mx <<= leftShift;
        expDiff -= leftShift;
        mx = Long.remainderUnsigned(mx, my);
        if (mx == 0) {
            return asDouble(sx);
        }
        if (expDiff == 0) {
            return makeDouble(mx, ey, sx);
        }
        long invHy = Long.divideUnsigned(-1L, my);
        while (expDiff > sidesZeroes) {
            expDiff -= sidesZeroes;
            long hd = (mx * invHy) >>> (64 - sidesZeroes);
            mx <<= sidesZeroes;
            mx -= hd * my;
            while (Long.compareUnsigned(mx, my) > 0) {
                mx -= my;
            }
        }
        long hd = (mx * invHy) >>> (64 - expDiff);
        mx <<= expDiff;
        mx -= hd * my;
        while (Long.compareUnsigned(mx, my) > 0) {
            mx -= my;
        }
        return makeDouble(mx, ey, sx);
    }

    // =============================================================================================
    // sqrt / sqrtf (sqrtsd / sqrtss; the compat wrapper's x<0 result is zero/zero, same bits)
    // =============================================================================================

    /** glibc sqrt@GLIBC_2.2.5. */
    public static double sqrt(double x) {
        if (x != x) {
            return quiet(x);
        }
        if (x < 0) {
            return DEFAULT_NAN;
        }
        return Math.sqrt(x);
    }

    /** glibc sqrtf@GLIBC_2.2.5. */
    public static float sqrtf(float x) {
        if (x != x) {
            return quiet(x);
        }
        if (x < 0) {
            return DEFAULT_NAN_F;
        }
        return (float) Math.sqrt(x);
    }
}
