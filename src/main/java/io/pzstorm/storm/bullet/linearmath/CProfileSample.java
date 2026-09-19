// Port of LinearMath/btQuickprof.h, class CProfileSample / BT_PROFILE (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Scope profiler: {@code BT_PROFILE("x");} is {@code try (CProfileSample p = new
 * CProfileSample("x")) {...}}.
 */
public class CProfileSample implements AutoCloseable {
    public CProfileSample(String name) {
        CProfileManager.Start_Profile(name);
    }

    /** ~CProfileSample() */
    @Override
    public void close() {
        CProfileManager.Stop_Profile();
    }
}
