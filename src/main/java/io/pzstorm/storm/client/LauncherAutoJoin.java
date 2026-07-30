package io.pzstorm.storm.client;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.event.core.SubscribeEvent;
import io.pzstorm.storm.event.lua.OnFETickEvent;
import io.pzstorm.storm.event.lua.OnMainMenuEnterEvent;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.jetbrains.annotations.Nullable;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;

/**
 * Zero-click server join for launcher-started clients. The Storm Launcher writes a one-shot
 * credential handoff ({@link java.util.Properties} format: {@code host}, {@code port}, {@code
 * username}, {@code password}, {@code serverPassword}) and passes its path to the game JVM as
 * {@code -Dstorm.autojoin.file=<path>}. At the first main menu this class reads and immediately
 * deletes the file, then fills and submits the vanilla {@code ServerConnectPopup} — the exact same
 * path a human clicking CONNECT takes, so version checks, workshop checks and all failure UI still
 * work.
 *
 * <p>Without the system property the class is never even registered, so a stale handoff file can
 * never fire on a manually started game; the property dies with the launcher-spawned process.
 *
 * <p>Registered by {@link io.pzstorm.storm.core.StormLauncher} on client JVMs only.
 */
public final class LauncherAutoJoin {

    /** Keep in sync with the Storm Launcher's {@code GameLaunch.AUTOJOIN_FILE_PROPERTY}. */
    public static final String AUTOJOIN_FILE_PROPERTY = "storm.autojoin.file";

    /** Frames to let the menu finish creating/painting before driving the popup. */
    private static final int ARM_DELAY_TICKS = 2;

    /** Give the menu UI ~10s at 60fps to appear before giving up (splash screens, slow loads). */
    private static final int MAX_DRIVE_TICKS = 600;

    private static final String NOT_READY = "not-ready";

    /**
     * Runs inside {@code LuaManager.env} with the credentials as chunk varargs — never interpolated
     * into the source. The port must be a string: {@code ServerConnectPopup} renders {@code self.ip
     * .. " : " .. self.port} and the java connect signature takes the port as a string.
     */
    static final String DRIVE_POPUP_LUA =
            """
            local host, port, username, password, serverPassword = ...
            local popup = ServerConnectPopup and ServerConnectPopup.instance
            local mainScreen = MainScreen and MainScreen.instance
            if not popup or not mainScreen then
                return "not-ready"
            end
            if mainScreen.bottomPanel then
                mainScreen.bottomPanel:setVisible(false)
            end
            popup:setServer(host, port, serverPassword)
            popup.usernameEntry:setText(username)
            popup.passwordEntry:setText(password)
            popup.serverPasswordEntry:setText(serverPassword)
            popup:setVisible(true)
            popup:onOptionMouseDown(popup.connectBtn)
            return "ok"
            """;

    private static boolean armed;
    private static boolean done;
    private static int ticksSinceArm;
    private static @Nullable Properties pending;
    private static @Nullable LuaClosure driveClosure;

    private LauncherAutoJoin() {}

    /**
     * Arms only at the first menu of the session: a later return to the menu (disconnect, ResetLua)
     * finds {@link #done} set — and the handoff file already deleted.
     */
    @SubscribeEvent
    public static void onMainMenuEnter(OnMainMenuEnterEvent event) {
        if (done || armed) {
            return;
        }
        Path handoff = handoffFile();
        if (handoff == null) {
            done = true;
            return;
        }
        try {
            pending = readAndDelete(handoff);
        } catch (IOException e) {
            LOGGER.error("Auto-join handoff {} unreadable: {}", handoff, e.toString());
            pending = null;
        }
        if (pending == null) {
            done = true;
            return;
        }
        armed = true;
        ticksSinceArm = 0;
        LOGGER.info(
                "Auto-join armed for {}@{}:{}",
                pending.getProperty("username"),
                pending.getProperty("host"),
                pending.getProperty("port"));
    }

    @SubscribeEvent
    public static void onFETick(OnFETickEvent event) {
        if (!armed || done || pending == null) {
            return;
        }
        ticksSinceArm++;
        if (ticksSinceArm < ARM_DELAY_TICKS) {
            return;
        }
        String result = driveConnectPopup(pending);
        if (NOT_READY.equals(result) && ticksSinceArm < MAX_DRIVE_TICKS) {
            return; // menu still building; retry next frame
        }
        if ("ok".equals(result)) {
            LOGGER.info(
                    "Auto-join submitted connect to {}:{}",
                    pending.getProperty("host"),
                    pending.getProperty("port"));
        } else {
            LOGGER.warn(
                    "Auto-join could not drive the connect popup ({}) — connect manually.", result);
        }
        finish();
    }

    private static void finish() {
        done = true;
        armed = false;
        pending = null; // credentials out of memory as soon as they have been used
        driveClosure = null;
    }

    private static String driveConnectPopup(Properties data) {
        try {
            if (driveClosure == null) {
                driveClosure =
                        LuaCompiler.loadstring(DRIVE_POPUP_LUA, "storm-autojoin", LuaManager.env);
            }
            Object[] results =
                    LuaManager.caller.pcall(
                            LuaManager.thread,
                            driveClosure,
                            data.getProperty("host", ""),
                            data.getProperty("port", ""),
                            data.getProperty("username", ""),
                            data.getProperty("password", ""),
                            data.getProperty("serverPassword", ""));
            if (results != null && results.length > 0 && Boolean.TRUE.equals(results[0])) {
                return results.length > 1 ? String.valueOf(results[1]) : "ok";
            }
            return "lua error: " + (results != null && results.length > 1 ? results[1] : "unknown");
        } catch (Exception e) {
            return "exception: " + e;
        }
    }

    static @Nullable Path handoffFile() {
        String path = System.getProperty(AUTOJOIN_FILE_PROPERTY);
        if (path == null || path.isEmpty()) {
            return null;
        }
        return Paths.get(path);
    }

    /**
     * Reads the handoff and deletes it before returning — credentials must not survive the first
     * read, even when they turn out to be unusable. Returns null for a missing or incomplete file.
     */
    static @Nullable Properties readAndDelete(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } finally {
            Files.deleteIfExists(file);
        }
        if (!isComplete(props)) {
            LOGGER.warn("Auto-join handoff {} is incomplete — ignoring it.", file);
            return null;
        }
        return props;
    }

    /** Username-less joins can't pass the popup's checkFields; host/port have no fallback. */
    static boolean isComplete(Properties props) {
        return !props.getProperty("host", "").isEmpty()
                && !props.getProperty("port", "").isEmpty()
                && !props.getProperty("username", "").isEmpty();
    }

    /** Test hook: the handlers are static because the dispatcher registers the class. */
    static void resetForTest() {
        armed = false;
        done = false;
        ticksSinceArm = 0;
        pending = null;
        driveClosure = null;
    }
}
