package io.pzstorm.storm.bullet.pz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link PZDebugLog#cprintf} against glibc printf. Expected strings were produced by printf in the
 * gcc:10.5 image (x86-64 glibc) for the listed bit patterns.
 */
class PZDebugLogFormatTest {

    private static final String[] GLIBC_F = {
        "407c800000000000|456.000000",
        "3f80000000000000|0.007812",
        "3f98000000000000|0.023438",
        "3ff000008637bd06|1.000001",
        "8000000000000000|-0.000000",
        "be9ad7f29abcaf48|-0.000000",
        "3fb999999999999a|0.100000",
        "3e90c6f7a0b5ed8d|0.000000",
        "4415af1d78b58c40|100000000000000000000.000000",
        "419d6f34547e6b61|123456789.123456",
        "bff8000000000000|-1.500000",
        "7ff8000000000000|nan",
        "fff8000000000000|-nan",
        "7ff0000000000000|inf",
        "fff0000000000000|-inf",
        "400921fb54442d11|3.141593",
        "3fdffffde7210be9|0.499999",
        "00000000000007e8|0.000000",
    };

    @Test
    void percentFMatchesGlibc() {
        for (String line : GLIBC_F) {
            String[] p = line.split("\\|");
            double v = Double.longBitsToDouble(Long.parseUnsignedLong(p[0], 16));
            assertEquals(p[1], PZDebugLog.cprintf("%f", v), p[0]);
        }
    }

    @Test
    void otherConversionsMatchGlibc() {
        assertEquals(
                "S|(null)|-7|42|%", PZDebugLog.cprintf("S|%s|%d|%i|%%", (Object) null, -7, 42));
    }

    @Test
    void selfTestLineMatchesTheNativeLibrary() {
        assertEquals(
                "Testing Tracef string:stringArg decimal:123 float:456.000000.",
                PZDebugLog.cprintf(
                        "Testing Tracef string:%s decimal:%d float:%f.", "stringArg", 123, 456.0));
    }
}
