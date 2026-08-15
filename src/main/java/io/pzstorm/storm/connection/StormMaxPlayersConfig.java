package io.pzstorm.storm.connection;

import io.pzstorm.storm.metrics.StormPerformanceSandboxMetrics;

/**
 * Runtime config for the {@code Storm.OverrideMaxPlayers} / {@code Storm.MaxPlayers} sandbox pair —
 * a live-adjustable replacement for the {@code .ini} {@code MaxPlayers} value.
 *
 * <p>Vanilla clamps the player ceiling twice: the {@code MaxPlayers} server option itself is
 * declared with max 100, and {@code ServerOptions.getMaxPlayers()} additionally returns {@code
 * Math.min(100, value)}. {@code ServerOptionsMaxPlayersPatch} rewrites that getter to route through
 * {@link #overrideOrVanilla(int)}: while the override is disabled (the default) every caller sees
 * the untouched vanilla value, so shipping this changes nothing on existing servers; while enabled,
 * every caller — the {@code LoginPacket} ServerFull gate, {@code LoginQueue}, {@code
 * ConnectCoopPacket}, {@code QueuePacket}, {@code ConnectionDetails}, the public-server phone-home
 * — reads the override at check time, so an admin sandbox push takes effect immediately with no
 * restart. Lowering below the current player count never kicks anyone; the server just stops
 * admitting new players until the count drops.
 *
 * <p>This class deliberately references no {@code zombie.*} types — unit tests exercise {@link
 * #setOverride(boolean, int)} in a bare JVM where {@code ServerOptions.&lt;clinit&gt;} would fail
 * (and stay poisoned for the rest of the test JVM). The Steam-browser re-push on an effective
 * change lives in {@code StormPerformanceSandboxApplier.applyMaxPlayersOverride()}, which only runs
 * in a real server context.
 *
 * <p>The RakNet connection pool is a separate, physical ceiling: 256 total slots (byte-wide wire
 * index — see {@link RakNetConnectionCapConfig#MAX_CAP}), sized once at boot as {@code MaxPlayers}
 * + headroom. {@code GameServerConnectionCapPatch} applies this override before sizing the pool, so
 * an override enabled in the save is accounted for from boot; raising it live above the boot-time
 * pool still raises the login gate, but growing the pool itself needs a restart.
 */
public final class StormMaxPlayersConfig {

    public static final int MIN_MAX_PLAYERS = 1;

    /**
     * {@link RakNetConnectionCapConfig#MAX_CAP} − 1: the byte-wide wire index gives 256 total
     * connection slots, and a joiner must hold a slot through the login pipeline before becoming a
     * player — 256 players would leave no slot for anyone to ever log in through.
     */
    public static final int MAX_MAX_PLAYERS = 255;

    public static final int DEFAULT_MAX_PLAYERS = 100;
    public static final boolean DEFAULT_OVERRIDE_ENABLED = false;

    private static volatile boolean OVERRIDE_ENABLED = DEFAULT_OVERRIDE_ENABLED;
    private static volatile int MAX_PLAYERS = DEFAULT_MAX_PLAYERS;

    private StormMaxPlayersConfig() {}

    /**
     * Hot-path reader inlined into {@code ServerOptions.getMaxPlayers()}: the override value while
     * enabled, the vanilla return value otherwise.
     */
    public static int overrideOrVanilla(int vanillaValue) {
        return OVERRIDE_ENABLED ? MAX_PLAYERS : vanillaValue;
    }

    public static boolean isOverrideEnabled() {
        return OVERRIDE_ENABLED;
    }

    /** Configured override value; only in effect while {@link #isOverrideEnabled()}. */
    public static int getConfiguredMaxPlayers() {
        return MAX_PLAYERS;
    }

    /**
     * Updates both halves of the override. Clamps the value to {@link #MIN_MAX_PLAYERS}..{@link
     * #MAX_MAX_PLAYERS}, stores, and pushes both Prometheus gauges. Returns the applied (clamped)
     * value. Callers that need to react to an effective-ceiling change (the sandbox applier's Steam
     * re-push) compare the getters before and after.
     */
    public static synchronized int setOverride(boolean enabled, int requestedMaxPlayers) {
        int clamped = clamp(requestedMaxPlayers);
        OVERRIDE_ENABLED = enabled;
        MAX_PLAYERS = clamped;
        StormPerformanceSandboxMetrics.setMaxPlayersOverrideEnabled(enabled);
        StormPerformanceSandboxMetrics.setMaxPlayersOverride(clamped);
        return clamped;
    }

    private static int clamp(int requested) {
        if (requested < MIN_MAX_PLAYERS) {
            return MIN_MAX_PLAYERS;
        }
        if (requested > MAX_MAX_PLAYERS) {
            return MAX_MAX_PLAYERS;
        }
        return requested;
    }
}
