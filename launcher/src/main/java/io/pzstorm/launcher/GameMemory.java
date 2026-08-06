package io.pzstorm.launcher;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Sizes the game JVM's max heap. Automatic mode allocates half the machine's RAM plus one GB,
 * capped at {@link #AUTO_MAX_GB}; manual mode is clamped to {@link #MANUAL_MIN_GB}–{@link
 * #MANUAL_MAX_GB}. RAM detection reads the platform OperatingSystemMXBean — one code path for
 * Windows, linux and mac — but through reflection, so a runtime without the {@code jdk.management}
 * module degrades to "unknown" (auto mode then keeps the game's own -Xmx) instead of failing to
 * link.
 */
public final class GameMemory {

    public static final int MANUAL_MIN_GB = 4;
    public static final int MANUAL_MAX_GB = 32;
    public static final int AUTO_MAX_GB = 16;

    private static final long GIB = 1L << 30;

    private GameMemory() {}

    /** Automatic allocation in GB for this machine, or 0 when total RAM cannot be determined. */
    public static int autoGb() {
        return autoGbFor(totalSystemBytes());
    }

    /** Half the total RAM plus one GB, capped at {@link #AUTO_MAX_GB}; 0 when total is unknown. */
    static int autoGbFor(long totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }
        long auto = Math.round((double) totalBytes / GIB / 2 + 1);
        return (int) Math.max(1, Math.min(AUTO_MAX_GB, auto));
    }

    public static int clampManualGb(long gb) {
        return (int) Math.max(MANUAL_MIN_GB, Math.min(MANUAL_MAX_GB, gb));
    }

    /** Total physical RAM in bytes, or 0 when the platform bean does not expose it. */
    public static long totalSystemBytes() {
        Object bean = ManagementFactory.getOperatingSystemMXBean();
        try {
            Class<?> extended = Class.forName("com.sun.management.OperatingSystemMXBean");
            if (!extended.isInstance(bean)) {
                return 0;
            }
            for (String getter :
                    new String[] {"getTotalMemorySize", "getTotalPhysicalMemorySize"}) {
                try {
                    Method method = extended.getMethod(getter);
                    long total = ((Number) method.invoke(bean)).longValue();
                    if (total > 0) {
                        return total;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // getter renamed across JDKs — try the older name
                }
            }
        } catch (ClassNotFoundException | RuntimeException ignored) {
            // jdk.management absent from the runtime — RAM size is simply unknown
        }
        return 0;
    }
}
