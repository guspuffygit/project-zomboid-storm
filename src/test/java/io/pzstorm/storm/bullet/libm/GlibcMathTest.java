package io.pzstorm.storm.bullet.libm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/**
 * Compares GlibcMath / GlibcRand bit-for-bit against reference output recorded from the real glibc
 * 2.39 (ubuntu:24.04) and glibc 2.35 (ubuntu:22.04) libm on FMA/AVX2 hardware by libm_refgen.c
 * (next to the reference files in the test resources).
 *
 * <p>Set the environment variable LIBM_REF_DUMP to a directory holding {@code <func>.bin} files
 * from {@code libm_refgen dump <func>} to get per-input mismatch reports instead of per-chunk
 * digests.
 */
class GlibcMathTest implements UnitTest {

    private static final String RES = "/io/pzstorm/storm/bullet/libm/";

    record Ref(
            String version,
            Map<String, long[][]> digests, // func -> [chunk] -> {inHash, outHash}
            Map<String, Integer> counts,
            Map<String, Integer> chunkSizes,
            List<String[]> raw,
            List<String[]> rand) {}

    private static Ref load(String version) throws IOException {
        Map<String, long[][]> digests = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> chunks = new HashMap<>();
        List<String[]> raw = new ArrayList<>();
        List<String[]> rand = new ArrayList<>();
        String v = null;
        try (InputStream is =
                        GlibcMathTest.class.getResourceAsStream(
                                RES + "libm-ref-" + version + ".txt.gz");
                BufferedReader r =
                        new BufferedReader(
                                new InputStreamReader(
                                        new GZIPInputStream(is), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(" ");
                switch (p[0]) {
                    case "V" -> {
                        v = p[1];
                        assertEquals("fma=1", p[2], "reference must come from an FMA machine");
                        assertEquals("avx2=1", p[3], "reference must come from an AVX2 machine");
                    }
                    case "N" -> {
                        int n = Integer.parseInt(p[2]);
                        int c = Integer.parseInt(p[3]);
                        counts.put(p[1], n);
                        chunks.put(p[1], c);
                        digests.put(p[1], new long[n / c][]);
                    }
                    case "D" ->
                            digests.get(p[1])[Integer.parseInt(p[2])] =
                                    new long[] {
                                        Long.parseUnsignedLong(p[3], 16),
                                        Long.parseUnsignedLong(p[4], 16)
                                    };
                    case "R" -> raw.add(p);
                    case "G" -> rand.add(p);
                    default -> throw new IllegalStateException("bad line " + line);
                }
            }
        }
        assertEquals(version, v);
        return new Ref(v, digests, counts, chunks, raw, rand);
    }

    /** Evaluates function f of the given glibc version on raw input bits. */
    static void eval(int f, boolean v235, long[] in, long[] out) {
        double x = Double.longBitsToDouble(in[0]);
        double y = Double.longBitsToDouble(in[1]);
        out[1] = 0;
        switch (f) {
            case LibmInputs.F_SIN -> out[0] = bits(GlibcMath.sin(x));
            case LibmInputs.F_COS -> out[0] = bits(GlibcMath.cos(x));
            case LibmInputs.F_SINCOS -> {
                double[] sc = new double[2];
                if (v235) {
                    GlibcMath.sincos235(x, sc);
                } else {
                    GlibcMath.sincos239(x, sc);
                }
                out[0] = bits(sc[0]);
                out[1] = bits(sc[1]);
            }
            case LibmInputs.F_ASIN -> out[0] = bits(GlibcMath.asin(x));
            case LibmInputs.F_ACOS -> out[0] = bits(GlibcMath.acos(x));
            case LibmInputs.F_ATAN2 -> out[0] = bits(GlibcMath.atan2(x, y));
            case LibmInputs.F_POW -> out[0] = bits(GlibcMath.pow(x, y));
            case LibmInputs.F_FMOD ->
                    out[0] = bits(v235 ? GlibcMath.fmod235(x, y) : GlibcMath.fmod239(x, y));
            case LibmInputs.F_SQRT -> out[0] = bits(GlibcMath.sqrt(x));
            case LibmInputs.F_POWF ->
                    out[0] =
                            Float.floatToRawIntBits(
                                            GlibcMath.powf(
                                                    Float.intBitsToFloat((int) in[0]),
                                                    Float.intBitsToFloat((int) in[1])))
                                    & 0xffffffffL;
            default ->
                    out[0] =
                            Float.floatToRawIntBits(
                                            GlibcMath.sqrtf(Float.intBitsToFloat((int) in[0])))
                                    & 0xffffffffL;
        }
    }

    private static long bits(double d) {
        return Double.doubleToRawLongBits(d);
    }

    private static String describe(int f, long[] in, long[] got, long[] want) {
        StringBuilder sb = new StringBuilder(LibmInputs.NAMES[f]).append('(');
        for (int k = 0; k < LibmInputs.nin(f); k++) {
            sb.append(k == 0 ? "" : ", ").append(Long.toHexString(in[k]));
        }
        sb.append(") got");
        for (int k = 0; k < LibmInputs.nout(f); k++) {
            sb.append(' ').append(Long.toHexString(got[k]));
        }
        sb.append(" want");
        for (int k = 0; k < LibmInputs.nout(f); k++) {
            sb.append(' ').append(Long.toHexString(want[k]));
        }
        return sb.toString();
    }

    private static void check(String version) throws IOException {
        boolean v235 = version.equals("2.35");
        Ref ref = load(version);
        String dumpDir = System.getenv("LIBM_REF_DUMP");
        List<String> failures = new ArrayList<>();
        long total = 0;

        // Raw special-value records.
        Map<String, Integer> fIndex = new HashMap<>();
        for (int f = 0; f < LibmInputs.NF; f++) {
            fIndex.put(LibmInputs.NAMES[f], f);
        }
        long[] in = new long[2];
        long[] out = new long[2];
        long[] want = new long[2];
        for (String[] p : ref.raw()) {
            int f = fIndex.get(p[1]);
            int ni = LibmInputs.nin(f);
            int no = LibmInputs.nout(f);
            in[1] = 0;
            for (int k = 0; k < ni; k++) {
                in[k] = Long.parseUnsignedLong(p[2 + k], 16);
            }
            want[1] = 0;
            for (int k = 0; k < no; k++) {
                want[k] = Long.parseUnsignedLong(p[2 + ni + k], 16);
            }
            eval(f, v235, in, out);
            total++;
            if (out[0] != want[0] || out[1] != want[1]) {
                failures.add("special " + describe(f, in, out, want));
            }
        }

        // Seeded bulk inputs, compared by per-chunk digests (or per input with a full dump).
        for (int f = 0; f < LibmInputs.NF; f++) {
            final int fn = f;
            String name = LibmInputs.NAMES[f];
            int n = ref.counts().get(name);
            int chunk = ref.chunkSizes().get(name);
            long[][] dig = ref.digests().get(name);
            total += n;
            long[][] full = null;
            if (dumpDir != null && Files.exists(Path.of(dumpDir, name + ".bin"))) {
                full = readDump(Path.of(dumpDir, name + ".bin"), n, LibmInputs.nout(f));
            }
            final long[][] fullRef = full;
            ConcurrentLinkedQueue<String> bad = new ConcurrentLinkedQueue<>();
            IntStream.range(0, n / chunk)
                    .parallel()
                    .forEach(
                            c -> {
                                LibmInputs gen = new LibmInputs();
                                long[] i2 = new long[2];
                                long[] o2 = new long[2];
                                long hi = 0;
                                long ho = 0;
                                int reported = 0;
                                for (long i = (long) c * chunk; i < (long) (c + 1) * chunk; i++) {
                                    gen.inputs(fn, i, i2);
                                    eval(fn, v235, i2, o2);
                                    for (int k = 0; k < LibmInputs.nin(fn); k++) {
                                        hi = LibmInputs.hash(hi, i2[k]);
                                    }
                                    for (int k = 0; k < LibmInputs.nout(fn); k++) {
                                        ho = LibmInputs.hash(ho, o2[k]);
                                    }
                                    if (fullRef != null) {
                                        long[] w = {fullRef[0][(int) i], fullRef[1][(int) i]};
                                        if ((w[0] != o2[0] || w[1] != o2[1]) && reported++ < 20) {
                                            bad.add(
                                                    "#"
                                                            + i
                                                            + " cat "
                                                            + (i % 12)
                                                            + " "
                                                            + describe(fn, i2, o2, w));
                                        }
                                    }
                                }
                                if (hi != dig[c][0]) {
                                    bad.add(
                                            name
                                                    + " chunk "
                                                    + c
                                                    + ": input generator out of sync with libm_refgen.c");
                                } else if (ho != dig[c][1]) {
                                    bad.add(name + " chunk " + c + ": output digest mismatch");
                                }
                            });
            failures.addAll(bad);
        }

        System.out.println(
                "glibc "
                        + version
                        + ": "
                        + total
                        + " libm cases checked, "
                        + failures.size()
                        + " failures");
        if (!failures.isEmpty()) {
            failures.stream().limit(200).forEach(System.out::println);
        }
        assertTrue(failures.isEmpty(), failures.size() + " mismatches vs glibc " + version);
    }

    private static long[][] readDump(Path p, int n, int nout) throws IOException {
        long[][] r = new long[2][n];
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < nout; k++) {
                r[k][i] = bb.getLong();
            }
        }
        return r;
    }

    @Test
    void matchesGlibc239() throws IOException {
        check("2.39");
    }

    @Test
    void matchesGlibc235() throws IOException {
        check("2.35");
    }

    @Test
    void randMatchesGlibc() throws IOException {
        for (String version : new String[] {"2.39", "2.35"}) {
            Ref ref = load(version);
            assertEquals(9, ref.rand().size());
            for (String[] p : ref.rand()) {
                int count = Integer.parseInt(p[2]);
                long want = Long.parseUnsignedLong(p[3], 16);
                GlibcRand g =
                        p[1].equals("default")
                                ? new GlibcRand(1)
                                : new GlibcRand(Integer.parseUnsignedInt(p[1], 16));
                long h = 0;
                for (int k = 0; k < count; k++) {
                    int v = g.next();
                    if (k < 8) {
                        assertEquals(
                                Integer.parseInt(p[4 + k], 16), v, "rand seed " + p[1] + " #" + k);
                    }
                    h = LibmInputs.hash(h, v & 0xffffffffL);
                }
                assertEquals(want, h, "rand seed " + p[1] + " digest, glibc " + version);
            }
        }
    }

    @Test
    void staticRandStartsFromDefaultState() {
        GlibcRand.srand(1);
        assertEquals(0x6b8b4567, GlibcRand.rand());
        assertEquals(0x327b23c6, GlibcRand.rand());
    }
}
