// Port of PZ glue PZDebugLog (PZDebugLog.cpp): instance @00186240, logInternal @00186250,
// GetFileNameOnly @00186390, InitCharBuffer @001863f0, TryFormatTextV @00186480,
// AllocateBufferForFormatTextV @00186530, FormatText @001865d0, log @001868d0, logf @00186a60,
// init @00186d30.
package io.pzstorm.storm.bullet.pz;

import io.pzstorm.storm.bullet.BulletUpcalls;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Native logger forwarding to {@code zombie.debug.DebugLog.nativeLog(tag, type, text)}. The pthread
 * mutex at +0x30 is recursive-in-effect (every entry point locks it); here methods are {@code
 * synchronized}. The char buffer (+0x20/+0x28) and vsnprintf are replaced by {@link #cprintf},
 * which reproduces glibc for the conversions the library uses ({@code %s %d %f %%}). {@link
 * String#format} does not: it prints NaN/Infinity, "null", and rounds {@code %f} from the shortest
 * decimal half-up where glibc rounds the exact binary value half-to-even.
 */
public final class PZDebugLog {

    private static final PZDebugLog s_instance = new PZDebugLog();

    /** +0 */
    public boolean m_initCalled;

    /** +1 */
    public boolean m_initSuccess;

    /** +8 jclass DebugLog */
    public Object m_class;

    /** +0x10 jmethodID nativeLog */
    public Object m_nativeLog;

    private PZDebugLog() {}

    public static PZDebugLog instance() {
        return s_instance;
    }

    public synchronized boolean init() {
        if (m_initCalled) {
            return m_initSuccess;
        }
        m_initCalled = true;
        m_class = PZBullet.Instance().initClass("zombie/debug/DebugLog");
        if (m_class == null) {
            System.out.print("Could not find class: \"zombie.debug.DebugLog\"");
            return false;
        }
        m_nativeLog =
                PZBullet.Instance()
                        .initStaticMethod(
                                m_class,
                                "nativeLog",
                                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (m_nativeLog == null) {
            System.out.print(
                    "Could not find method: nativeLog(String, String, String), in class:"
                            + " \"zombie.debug.DebugLog\"");
            return false;
        }
        final String loc = "/usr/src/pz/pzbullet/PZDebugLog.cpp:";
        instance().log("PZBullet", PZDebugType.Debug, loc + "43", "PZDebugLog init success.");
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Debug,
                        loc + "44",
                        "PZBullet Version: %s",
                        "1.0.0.28");
        instance().log("PZBullet", PZDebugType.Trace, loc + "48", "Testing Trace.");
        instance().log("PZBullet", PZDebugType.Noise, loc + "49", "Testing Noise.");
        instance().log("PZBullet", PZDebugType.Debug, loc + "50", "Testing Debug.");
        instance().log("PZBullet", PZDebugType.General, null, "Testing General.");
        instance().log("PZBullet", PZDebugType.Warning, loc + "52", "Testing Warning.");
        instance().log("PZBullet", PZDebugType.Error, loc + "53", "Testing Error.");
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Trace,
                        loc + "55",
                        "Testing Tracef string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Noise,
                        loc + "56",
                        "Testing Noisef string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Debug,
                        loc + "57",
                        "Testing Debugf string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.General,
                        null,
                        "Testing Generalf string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Warning,
                        loc + "59",
                        "Testing Warningf string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance()
                .logf(
                        "PZBullet",
                        PZDebugType.Error,
                        loc + "60",
                        "Testing Errorf string:%s decimal:%d float:%f.",
                        "stringArg",
                        123,
                        456.0);
        instance().log("PZBullet", PZDebugType.Debug, loc + "62", "PZDebugLog testing success.");
        m_initSuccess = true;
        return true;
    }

    /** strrchr of '\\' and '/': the part after whichever separator comes last; null for null. */
    public static String GetFileNameOnly(String path) {
        if (path == null) {
            return null;
        }
        int bs = path.lastIndexOf('\\');
        int fs = path.lastIndexOf('/');
        if (fs < 0 && bs < 0) {
            return path;
        }
        return path.substring(Math.max(fs, bs) + 1);
    }

    public synchronized void log(String tag, String type, String location, String msg) {
        String file = GetFileNameOnly(location);
        String text;
        if (file != null) {
            text = cformat("(%s) > %s", file, msg);
        } else {
            if (msg == null) {
                // std::string(nullptr): std::logic_error
                throw new IllegalStateException("basic_string::_M_construct null not valid");
            }
            text = msg;
        }
        logInternal(tag, type, text);
    }

    public synchronized void logf(
            String tag, String type, String location, String fmt, Object... args) {
        String file = GetFileNameOnly(location);
        String format;
        if (file != null) {
            format = cformat("(%s) > %s", file, fmt);
        } else {
            if (fmt == null) {
                throw new IllegalStateException("basic_string::_M_construct null not valid");
            }
            format = fmt;
        }
        String text;
        try {
            text = cprintf(format, args);
        } catch (RuntimeException e) {
            // TryFormatTextV and AllocateBufferForFormatTextV both failed: the raw fmt is logged.
            text = fmt;
        }
        logInternal(tag, type, text);
    }

    public synchronized void logInternal(String tag, String type, String text) {
        BulletUpcalls.callNativeLog(tag, type, text);
    }

    /** snprintf with "%s" arguments ("(null)" for null, like glibc). */
    private static String cformat(String fmt, String a, String b) {
        return cprintf(fmt, a, b);
    }

    /**
     * glibc vsnprintf for plain {@code %s %d %i %f %%} (no flags, width or precision, which the
     * library never uses). Any other conversion is delegated to {@link String#format} for that one
     * specifier. Too few arguments throws, which {@link #logf} treats like a failed format.
     */
    static String cprintf(String fmt, Object... args) {
        StringBuilder out = new StringBuilder(fmt.length() + 32);
        int arg = 0;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '%' || i + 1 >= fmt.length()) {
                out.append(c);
                continue;
            }
            char conv = fmt.charAt(++i);
            switch (conv) {
                case '%' -> out.append('%');
                case 's' -> {
                    Object a = args[arg++];
                    out.append(a == null ? "(null)" : a.toString());
                }
                case 'd', 'i' -> out.append(((Number) args[arg++]).intValue());
                case 'f' -> out.append(cFixed6(((Number) args[arg++]).doubleValue()));
                default -> out.append(String.format(Locale.ROOT, "%" + conv, args[arg++]));
            }
        }
        return out.toString();
    }

    /** glibc {@code %f}: the exact binary value rounded half-to-even to 6 places. */
    static String cFixed6(double v) {
        boolean negative = (Double.doubleToRawLongBits(v) < 0);
        if (v != v) {
            return negative ? "-nan" : "nan";
        }
        if (Double.isInfinite(v)) {
            return negative ? "-inf" : "inf";
        }
        String digits =
                new BigDecimal(Math.abs(v)).setScale(6, RoundingMode.HALF_EVEN).toPlainString();
        return negative ? "-" + digits : digits;
    }
}
