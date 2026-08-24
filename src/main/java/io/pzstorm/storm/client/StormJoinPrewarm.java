package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.util.StormEnv;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import zombie.ZomboidFileSystem;
import zombie.core.Core;
import zombie.modding.ActiveMods;

/**
 * Launcher-stage join prewarm: makes a launcher-started client boot with the <i>target server's</i>
 * mod set instead of the local "default" profile, so the expensive flag-independent loads (scripts,
 * models, animations, texture packs, translations) happen during boot — before the player ever
 * reaches the connect flow — instead of inside connect-time {@code Core.ResetLua}.
 *
 * <p>The launcher queries the server's ordered mod list before the game process exists (see {@code
 * StormQueryResponder} / launcher {@code ServerQuery}) and passes it as {@code
 * -Dstorm.workshop.mods} (the property Storm's java-mod gate already uses), plus {@value
 * #BOOT_MODS_PROPERTY}{@code =true} to opt this JVM into boot substitution. At the single boot-time
 * {@code ZomboidFileSystem.loadMods("default")} call, the substitution replicates exactly what
 * vanilla's connect-time client branch will later do — {@code loadTranslationMods() + serverMods} —
 * so the connect-time fast path can prove "the booted mod set is the server's mod set" by simple
 * list equality against {@code GameClient.instance.serverMods}.
 *
 * <p>Every failure is soft: any error leaves the vanilla "default" boot to run, which only costs
 * the fast path, never the join. Without the opt-in property the class does nothing, so a manually
 * started game is untouched.
 */
public final class StormJoinPrewarm {

    /**
     * Opt-in for boot-mods substitution. Set by the Storm Launcher alongside {@code
     * storm.workshop.mods} when it launches a join with a queried mod list. Keep in sync with the
     * launcher's {@code GameLaunch}.
     */
    public static final String BOOT_MODS_PROPERTY = "storm.join.bootmods";

    /**
     * The target server's three join-checksum totals ({@code lua;script;anim}), queried before
     * launch (query protocol v2). Consumed by the connect-time fast path; absent against a server
     * whose Storm predates protocol v2. Keep in sync with the launcher's {@code GameLaunch}.
     */
    public static final String CHECKSUMS_PROPERTY = "storm.join.checksums";

    /**
     * Launcher-computed fingerprint of the local content the checksums describe (game version,
     * ordered mod list, workshop install timestamps). The script-checksum cache is keyed on it, so
     * a workshop update or mod-list change invalidates the cache without any file walking here.
     * Keep in sync with the launcher's {@code GameLaunch}.
     */
    public static final String FINGERPRINT_PROPERTY = "storm.join.fingerprint";

    /** Same property the java-mod workshop gate reads: the server's ordered PZ mod ids. */
    private static final String WORKSHOP_MODS_PROPERTY = "storm.workshop.mods";

    private static boolean substitutionAttempted;
    private static volatile @Nullable List<String> bootModList;

    private StormJoinPrewarm() {}

    /**
     * Called from Storm's advice on {@code ZomboidFileSystem.loadMods(String)}. Returns {@code
     * true} when this call was the boot-time {@code "default"} load and the server's mod set was
     * loaded instead — the caller then skips the vanilla body. Never throws.
     */
    public static boolean substituteBootMods(Object fileSystem, String activeMods) {
        try {
            if (substitutionAttempted
                    || StormEnv.isStormServer()
                    || !Boolean.getBoolean(BOOT_MODS_PROPERTY)
                    || !"default".equalsIgnoreCase(activeMods)
                    || !Core.optionModsEnabled) {
                return false;
            }
            // one shot: only the first "default" load of the process is the boot load; a later
            // one (post-session ResetLua back to the menu, SP game start) stays vanilla
            substitutionAttempted = true;
            List<String> serverMods = parseServerMods();
            if (serverMods.isEmpty()) {
                return false;
            }
            return loadServerMods((ZomboidFileSystem) fileSystem, serverMods);
        } catch (Throwable t) {
            LOGGER.error("Boot-mods substitution failed — booting the default profile", t);
            return false;
        }
    }

    private static List<String> parseServerMods() {
        String raw = System.getProperty(WORKSHOP_MODS_PROPERTY, "");
        List<String> mods = new ArrayList<>();
        for (String mod : raw.split(";")) {
            if (!mod.isBlank()) {
                mods.add(mod.trim());
            }
        }
        return mods;
    }

    private static boolean loadServerMods(ZomboidFileSystem fs, List<String> serverMods)
            throws Exception {
        // refuse (and boot vanilla) when any server mod is not installed locally — the connect
        // flow's own workshop screen is the path that can fix that
        ArrayList<String> ordered = new ArrayList<>();
        String missing = fs.loadModsAux(new ArrayList<>(serverMods), ordered);
        if (missing != null) {
            LOGGER.warn(
                    "Boot-mods substitution skipped: server mod '{}' is not installed locally",
                    missing);
            return false;
        }

        // vanilla "default"-branch bookkeeping, so menu state is indistinguishable from a
        // vanilla boot (loadedMods is stale after a vanilla server connect too)
        ActiveMods defaults = (ActiveMods) invokePrivate(fs, "readDefaultModsTxt", new Class<?>[0]);
        defaults.checkMissingMods();
        defaults.checkMissingMaps();
        ActiveMods.setLoadedMods(defaults);

        // exactly the connect-time client branch of loadMods(String): translation mods
        // first, then the server's ordered list
        ArrayList<String> toLoad = new ArrayList<>();
        invokePrivate(fs, "loadTranslationMods", new Class<?>[] {ArrayList.class}, toLoad);
        toLoad.addAll(serverMods);
        fs.loadMods(toLoad);

        bootModList = Collections.unmodifiableList(new ArrayList<>(serverMods));
        LOGGER.info(
                "Boot-mods substitution: booted with the server's {} mod(s) instead of the"
                        + " default profile",
                serverMods.size());
        return true;
    }

    /**
     * The server mod list the boot actually loaded, or {@code null} when boot substitution did not
     * happen. The connect-time fast path compares this against {@code
     * GameClient.instance.serverMods}.
     */
    public static @Nullable List<String> bootModList() {
        return bootModList;
    }

    /** The server's script-checksum total from {@value #CHECKSUMS_PROPERTY}, or {@code null}. */
    public static @Nullable String serverScriptChecksum() {
        String[] parts = System.getProperty(CHECKSUMS_PROPERTY, "").split(";", -1);
        return parts.length >= 2 && !parts[1].isBlank() ? parts[1] : null;
    }

    /** The launcher's content fingerprint from {@value #FINGERPRINT_PROPERTY}, or {@code null}. */
    public static @Nullable String fingerprint() {
        String value = System.getProperty(FINGERPRINT_PROPERTY, "");
        return value.isBlank() ? null : value;
    }

    private static Object invokePrivate(
            Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    // test hook: property reads are cheap, but the one-shot flag must reset between tests
    static void resetForTests() {
        substitutionAttempted = false;
        bootModList = null;
    }
}
