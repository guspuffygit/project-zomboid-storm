// Port of LinearMath/btQuickprof.h / .cpp, class CProfileManager (Bullet 2.82).
package io.pzstorm.storm.bullet.linearmath;

/**
 * Hierarchical profiler. The PZ binary is built with profiling on (BT_NO_PROFILE undefined:
 * Start_Profile / Stop_Profile are called from the dynamics code), but profiling never affects
 * simulation results, so per PORTING.md this port is a no-op unless {@link #enabled} is set. When
 * enabled, the tree bookkeeping is the verbatim 2.82 logic. {@code BT_PROFILE(name)} is {@code try
 * (CProfileSample s = new CProfileSample(name)) { ... }}.
 *
 * <p>{@code dumpRecursive}/{@code dumpAll} print with {@code printf} to stdout like C++ (the C
 * {@code %.3f} of a float promoted to double is reproduced with {@link String#format}).
 */
public final class CProfileManager {
    private CProfileManager() {}

    /** Port-only switch; false = every entry point returns immediately. */
    public static volatile boolean enabled = false;

    /** {@code static btClock gProfileClock} (file static in btQuickprof.cpp). */
    static final btClock gProfileClock = new btClock();

    private static final CProfileNode Root = new CProfileNode("Root", null);
    private static CProfileNode CurrentNode = Root;
    private static int FrameCounter = 0;
    private static long ResetTime = 0;

    /** {@code Profile_Get_Ticks}: microseconds. */
    static long Profile_Get_Ticks() {
        return gProfileClock.getTimeMicroseconds();
    }

    /** {@code Profile_Get_Tick_Rate}: 1000.f (ticks per ms). */
    static float Profile_Get_Tick_Rate() {
        return 1000.f;
    }

    @SuppressWarnings("StringEquality")
    public static void Start_Profile(String name) {
        if (!enabled) {
            return;
        }
        if (name != CurrentNode.Get_Name()) {
            CurrentNode = CurrentNode.Get_Sub_Node(name);
        }
        CurrentNode.Call();
    }

    public static void Stop_Profile() {
        if (!enabled) {
            return;
        }
        if (CurrentNode.Return()) {
            CurrentNode = CurrentNode.Get_Parent();
        }
    }

    public static void CleanupMemory() {
        Root.CleanupMemory();
    }

    public static void Reset() {
        if (!enabled) {
            return;
        }
        gProfileClock.reset();
        Root.Reset();
        Root.Call();
        FrameCounter = 0;
        ResetTime = Profile_Get_Ticks();
    }

    public static void Increment_Frame_Counter() {
        if (!enabled) {
            return;
        }
        FrameCounter++;
    }

    public static int Get_Frame_Count_Since_Reset() {
        return FrameCounter;
    }

    public static float Get_Time_Since_Reset() {
        long time = Profile_Get_Ticks();
        time -= ResetTime;
        return (float) time / Profile_Get_Tick_Rate();
    }

    public static CProfileIterator Get_Iterator() {
        return new CProfileIterator(Root);
    }

    public static void Release_Iterator(CProfileIterator iterator) {}

    public static void dumpRecursive(CProfileIterator profileIterator, int spacing) {
        profileIterator.First();
        if (profileIterator.Is_Done()) {
            return;
        }

        float accumulated_time = 0,
                parent_time =
                        profileIterator.Is_Root()
                                ? CProfileManager.Get_Time_Since_Reset()
                                : profileIterator.Get_Current_Parent_Total_Time();
        int i;
        int frames_since_reset = CProfileManager.Get_Frame_Count_Since_Reset();
        StringBuilder out = new StringBuilder();
        for (i = 0; i < spacing; i++) out.append('.');
        out.append("----------------------------------\n");
        for (i = 0; i < spacing; i++) out.append('.');
        out.append(
                String.format(
                        "Profiling: %s (total running time: %.3f ms) ---\n",
                        profileIterator.Get_Current_Parent_Name(), (double) parent_time));
        int numChildren = 0;

        for (i = 0; !profileIterator.Is_Done(); i++, profileIterator.Next()) {
            numChildren++;
            float current_total_time = profileIterator.Get_Current_Total_Time();
            accumulated_time += current_total_time;
            float fraction =
                    parent_time > btScalar.SIMD_EPSILON
                            ? (current_total_time / parent_time) * 100
                            : 0.f;
            for (int j = 0; j < spacing; j++) out.append('.');
            out.append(
                    String.format(
                            "%d -- %s (%.2f %%) :: %.3f ms / frame (%d calls)\n",
                            i,
                            profileIterator.Get_Current_Name(),
                            (double) fraction,
                            (current_total_time / (double) frames_since_reset),
                            profileIterator.Get_Current_Total_Calls()));
        }

        if (parent_time < accumulated_time) {
            out.append("what's wrong\n");
        }
        for (i = 0; i < spacing; i++) out.append('.');
        out.append(
                String.format(
                        "%s (%.3f %%) :: %.3f ms\n",
                        "Unaccounted:",
                        parent_time > btScalar.SIMD_EPSILON
                                ? (double) (((parent_time - accumulated_time) / parent_time) * 100)
                                : 0.0,
                        (double) (parent_time - accumulated_time)));
        System.out.print(out);

        for (i = 0; i < numChildren; i++) {
            profileIterator.Enter_Child(i);
            dumpRecursive(profileIterator, spacing + 3);
            profileIterator.Enter_Parent();
        }
    }

    public static void dumpAll() {
        CProfileIterator profileIterator = CProfileManager.Get_Iterator();
        dumpRecursive(profileIterator, 0);
        CProfileManager.Release_Iterator(profileIterator);
    }
}
