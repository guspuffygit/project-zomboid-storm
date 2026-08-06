package io.pzstorm.storm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import se.krka.kahlua.j2se.J2SEPlatform;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.stdlib.BaseLib;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;

/**
 * Runs the production {@link LauncherAutoJoin#DRIVE_POPUP_LUA} chunk in a standalone Kahlua VM
 * against a stub {@code ServerConnectPopup}/{@code MainScreen}, asserting the exact calls the real
 * popup would receive — argument order, port-as-string, which password lands in which field. The
 * only thing this cannot prove is that the vanilla popup still has this API surface; the live
 * client E2E covers that.
 */
class LauncherAutoJoinChunkTest {

    private static final String STUB_UI_LUA =
            """
            local calls = {}
            local function entry(name)
                return {
                    setText = function(self, text) calls[name] = text end,
                }
            end
            ServerConnectPopup = {
                instance = {
                    usernameEntry = entry("username"),
                    passwordEntry = entry("password"),
                    serverPasswordEntry = entry("serverPassword"),
                    connectTypeEntry = { selected = { true } },
                    setServer = function(self, ip, port, pw)
                        calls.ip = ip
                        calls.portValue = port
                        calls.portType = type(port)
                        calls.setServerPassword = pw
                    end,
                    setVisible = function(self, visible) calls.popupVisible = visible end,
                },
            }
            MainScreen = {
                instance = {
                    bottomPanel = {
                        setVisible = function(self, visible) calls.bottomPanelVisible = visible end,
                    },
                },
            }
            ConnectToServer = {
                instance = {
                    connect = function(self, previousScreen, serverName, username, password,
                            ip, localIP, port, serverPassword, useSteamRelay, doHash, authType)
                        calls.connectPreviousScreen = previousScreen
                        calls.connectServerName = serverName
                        calls.connectUsername = username
                        calls.connectPassword = password
                        calls.connectIp = ip
                        calls.connectLocalIp = localIP
                        calls.connectPort = port
                        calls.connectPortType = type(port)
                        calls.connectServerPassword = serverPassword
                        calls.connectUseSteamRelay = useSteamRelay
                        calls.connectDoHash = doHash
                        calls.connectAuthType = authType
                    end,
                },
            }
            getSteamModeActive = function() return true end
            autoJoinTestCalls = calls
            """;

    private J2SEPlatform platform;
    private KahluaTable env;
    private KahluaThread thread;

    @BeforeEach
    void setUp() {
        // not newEnvironment(): PZ's fork insists on stdlib.lua/serialize.lua files in the
        // working directory; the chunks under test only need BaseLib (for `type`)
        platform = new J2SEPlatform();
        env = platform.newTable();
        env.rawset("_G", env);
        BaseLib.register(env);
        thread = new KahluaThread(platform, env);
        thread.debugOwnerThread = Thread.currentThread();
    }

    private Object[] run(String source, Object... args) throws Exception {
        LuaClosure closure = LuaCompiler.loadstring(source, "chunk-test", env);
        Object[] results = thread.pcall(closure, args);
        assertEquals(Boolean.TRUE, results[0], Arrays.toString(results));
        return results;
    }

    @Test
    void fillsThePopupAndConnectsWithoutRehashingTheStoredPassword() throws Exception {
        run(STUB_UI_LUA);
        Object[] results =
                run(
                        LauncherAutoJoin.DRIVE_POPUP_LUA,
                        "play.example.org",
                        "16261",
                        "gus",
                        "stored-form-pw",
                        "server-pw");
        assertEquals("ok", results[1]);

        KahluaTable calls = (KahluaTable) env.rawget("autoJoinTestCalls");
        assertEquals("play.example.org", calls.rawget("ip"));
        assertEquals("16261", calls.rawget("portValue"));
        assertEquals("string", calls.rawget("portType"), "the popup needs the port as a STRING");
        assertEquals("server-pw", calls.rawget("setServerPassword"));
        assertEquals("gus", calls.rawget("username"));
        assertEquals("stored-form-pw", calls.rawget("password"));
        assertEquals("server-pw", calls.rawget("serverPassword"));
        assertEquals(Boolean.TRUE, calls.rawget("popupVisible"));
        assertEquals(Boolean.FALSE, calls.rawget("bottomPanelVisible"), "menu buttons must hide");

        // the popup CONNECT branch, argument for argument — except doHash
        assertEquals("", calls.rawget("connectServerName"));
        assertEquals("gus", calls.rawget("connectUsername"));
        assertEquals("stored-form-pw", calls.rawget("connectPassword"));
        assertEquals("play.example.org", calls.rawget("connectIp"));
        assertEquals("", calls.rawget("connectLocalIp"));
        assertEquals("16261", calls.rawget("connectPort"));
        assertEquals("string", calls.rawget("connectPortType"));
        assertEquals("server-pw", calls.rawget("connectServerPassword"));
        assertEquals(Boolean.TRUE, calls.rawget("connectUseSteamRelay"));
        assertEquals(
                Boolean.FALSE,
                calls.rawget("connectDoHash"),
                "the password is already the stored form the server compares — hashing it"
                        + " again would guarantee InvalidUsernamePassword");
        assertEquals(1.0, calls.rawget("connectAuthType"));
    }

    @Test
    void reportsNotReadyInsteadOfErroringWhileTheMenuIsStillBuilding() throws Exception {
        // no stubs at all: even the class globals are absent, as during early boot frames
        Object[] bare = run(LauncherAutoJoin.DRIVE_POPUP_LUA, "h", "1", "u", "", "");
        assertEquals("not-ready", bare[1]);

        // class tables exist but no instances yet: the common first-frames state
        run("ServerConnectPopup = {}; MainScreen = {}; ConnectToServer = {}");
        Object[] noInstance = run(LauncherAutoJoin.DRIVE_POPUP_LUA, "h", "1", "u", "", "");
        assertEquals("not-ready", noInstance[1]);

        // popup and menu are up but ConnectToServer has not instantiated yet
        run(STUB_UI_LUA);
        run("ConnectToServer.instance = nil");
        Object[] noConnect = run(LauncherAutoJoin.DRIVE_POPUP_LUA, "h", "1", "u", "", "");
        assertEquals("not-ready", noConnect[1]);
    }
}
