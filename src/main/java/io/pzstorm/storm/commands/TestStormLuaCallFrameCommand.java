package io.pzstorm.storm.commands;

import io.pzstorm.storm.lua.StormKahluaTable;
import io.pzstorm.storm.lua.StormLuaCallFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaClosure;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.Role;
import zombie.commands.CommandBase;
import zombie.commands.CommandHelp;
import zombie.commands.CommandName;
import zombie.commands.RequiredCapability;
import zombie.core.raknet.UdpConnection;

/**
 * Server-side command that exercises {@link StormLuaCallFrame} against a real {@link
 * se.krka.kahlua.vm.LuaCallFrame} produced by the live game's Kahlua VM.
 *
 * <p>Registers a temporary {@link JavaFunction} probe in {@link LuaManager#env}, then compiles a
 * Lua snippet that invokes the probe with a mix of value types (number, numeric string, plain
 * string, table, true, nil, false, zero). The probe wraps the real call frame in {@link
 * StormLuaCallFrame} and asserts every accessor for each slot, recording any mismatches into a
 * list. The probe is removed from {@code env} on the way out so re-runs start clean.
 *
 * <p>Output: {@code RESULT ok=<bool> failures=<csv>} or {@code RESULT ERROR <kind>: <message>}.
 */
@CommandName(name = "stormteststormluacallframe")
@CommandHelp(
        helpText =
                "Exercises StormLuaCallFrame accessors against a real Kahlua LuaCallFrame; emits a"
                        + " single RESULT line consumed by StormLuaCallFrameLiveTest.",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestStormLuaCallFrameCommand extends CommandBase {

    private static volatile List<String> capturedFailures;
    private static volatile boolean probeInvoked;

    public TestStormLuaCallFrameCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() {
        return Command();
    }

    @Override
    protected String Command() {
        String fnName = "_storm_test_call_frame_" + System.nanoTime();
        try {
            capturedFailures = null;
            probeInvoked = false;

            JavaFunction probe =
                    (frame, nargs) -> {
                        List<String> failures = new ArrayList<>();
                        StormLuaCallFrame slf = new StormLuaCallFrame(frame);

                        // Slot 0: Lua number 42 (arrives as Double)
                        check(failures, "s0_long", 42L, slf.getLong(0));
                        check(failures, "s0_optLong", Optional.of(42L), slf.getOptionalLong(0));
                        check(failures, "s0_int", 42, slf.getInt(0));
                        check(failures, "s0_optInt", Optional.of(42), slf.getOptionalInt(0));
                        check(failures, "s0_double", 42.0, slf.getDouble(0));
                        check(
                                failures,
                                "s0_optDouble",
                                Optional.of(42.0),
                                slf.getOptionalDouble(0));
                        check(failures, "s0_string", null, slf.getString(0));
                        check(failures, "s0_optString", Optional.empty(), slf.getOptionalString(0));
                        check(failures, "s0_table", null, slf.getTable(0));
                        check(failures, "s0_bool", true, slf.getBool(0));

                        // Slot 1: numeric string "100" — strings should parse via the String branch
                        check(failures, "s1_long", 100L, slf.getLong(1));
                        check(failures, "s1_optLong", Optional.of(100L), slf.getOptionalLong(1));
                        check(failures, "s1_int", 100, slf.getInt(1));
                        check(failures, "s1_double", 100.0, slf.getDouble(1));
                        check(failures, "s1_string", "100", slf.getString(1));
                        check(
                                failures,
                                "s1_optString",
                                Optional.of("100"),
                                slf.getOptionalString(1));
                        check(failures, "s1_bool", true, slf.getBool(1));

                        // Slot 2: non-numeric string "hello" — number accessors return empty/null
                        check(failures, "s2_long", null, slf.getLong(2));
                        check(failures, "s2_optLong", Optional.empty(), slf.getOptionalLong(2));
                        check(failures, "s2_int", null, slf.getInt(2));
                        check(failures, "s2_optInt", Optional.empty(), slf.getOptionalInt(2));
                        check(failures, "s2_double", null, slf.getDouble(2));
                        check(failures, "s2_optDouble", Optional.empty(), slf.getOptionalDouble(2));
                        check(failures, "s2_string", "hello", slf.getString(2));
                        check(failures, "s2_table", null, slf.getTable(2));
                        check(failures, "s2_bool", true, slf.getBool(2));

                        // Slot 3: table {x=7}
                        StormKahluaTable t = slf.getTable(3);
                        if (t == null) {
                            failures.add("s3_table_null");
                        } else {
                            Double x = t.getDouble("x");
                            if (!Double.valueOf(7.0).equals(x)) {
                                failures.add("s3_table_x:" + x);
                            }
                        }
                        Optional<StormKahluaTable> optT = slf.getOptionalTable(3);
                        if (optT.isEmpty()) {
                            failures.add("s3_optTable_empty");
                        }
                        check(failures, "s3_long", null, slf.getLong(3));
                        check(failures, "s3_string", null, slf.getString(3));

                        // Slot 4: boolean true
                        check(failures, "s4_bool", true, slf.getBool(4));
                        check(failures, "s4_long", null, slf.getLong(4));
                        check(failures, "s4_string", null, slf.getString(4));
                        check(failures, "s4_table", null, slf.getTable(4));

                        // Slot 5: nil
                        check(failures, "s5_bool", false, slf.getBool(5));
                        check(failures, "s5_long", null, slf.getLong(5));
                        check(failures, "s5_optLong", Optional.empty(), slf.getOptionalLong(5));
                        check(failures, "s5_string", null, slf.getString(5));
                        check(failures, "s5_optString", Optional.empty(), slf.getOptionalString(5));
                        check(failures, "s5_table", null, slf.getTable(5));
                        check(
                                failures,
                                "s5_optTable_empty",
                                true,
                                slf.getOptionalTable(5).isEmpty());

                        // Slot 6: boolean false (distinct from nil)
                        check(failures, "s6_bool", false, slf.getBool(6));
                        check(failures, "s6_long", null, slf.getLong(6));

                        // Slot 7: number 0 — getBool must return false for the zero number path
                        check(failures, "s7_bool", false, slf.getBool(7));
                        check(failures, "s7_long", 0L, slf.getLong(7));
                        check(failures, "s7_double", 0.0, slf.getDouble(7));

                        // raw() must hand back the underlying frame untouched
                        if (slf.raw() != frame) {
                            failures.add("raw_identity");
                        }

                        probeInvoked = true;
                        capturedFailures = failures;
                        return 0;
                    };

            LuaManager.env.rawset(fnName, probe);

            String code = fnName + "(42, \"100\", \"hello\", {x=7}, true, nil, false, 0)";
            LuaClosure closure =
                    LuaCompiler.loadstring(code, "stormtest_callframe", LuaManager.env);
            Object[] callResults = LuaManager.caller.pcall(LuaManager.thread, closure);
            if (callResults == null || callResults.length < 1) {
                return "RESULT ERROR pcall_no_results";
            }
            if (!Boolean.TRUE.equals(callResults[0])) {
                String err = callResults.length > 1 ? String.valueOf(callResults[1]) : "(none)";
                return "RESULT ERROR pcall_failed: " + err;
            }
            if (!probeInvoked) {
                return "RESULT ERROR probe_not_invoked";
            }

            List<String> failures = capturedFailures;
            if (failures == null || failures.isEmpty()) {
                return "RESULT ok=true failures=";
            }
            return "RESULT ok=false failures=" + String.join(",", failures);
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try {
                LuaManager.env.rawset(fnName, null);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    private static void check(List<String> failures, String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            failures.add(label + "(want=" + expected + ",got=" + actual + ")");
        }
    }
}
