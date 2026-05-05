package io.pzstorm.storm.commands;

import io.pzstorm.storm.lua.StormKahluaTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import se.krka.kahlua.luaj.compiler.LuaCompiler;
import se.krka.kahlua.vm.KahluaTable;
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
 * Server-side command that exercises {@link StormKahluaTable} against a real {@link KahluaTable}
 * built by the live game's Kahlua VM. Covers the proxying accessors, the typed {@code
 * getString}/{@code getDouble}/{@code getBoolean} helpers, the optional/required table lookups, the
 * {@code getFunction} happy and missing paths, and every {@code pcall} variant — including the
 * Lua-error path and the wrong-type ClassCastException path.
 *
 * <p>Lua functions used by the {@code pcall} cases are produced by compiling a small snippet that
 * returns a table of closures (then their values are copied onto the table under test).
 *
 * <p>Output: {@code RESULT ok=<bool> failures=<csv>} or {@code RESULT ERROR <kind>: <message>}.
 */
@CommandName(name = "stormteststormkahluatable")
@CommandHelp(
        helpText =
                "Exercises StormKahluaTable accessors and pcall variants against a real Kahlua"
                        + " table; emits a single RESULT line consumed by"
                        + " StormKahluaTableLiveTest.",
        shouldTranslated = false)
@RequiredCapability(requiredCapability = Capability.DebugConsole)
public class TestStormKahluaTableCommand extends CommandBase {

    public TestStormKahluaTableCommand(
            String username, Role userRole, String command, UdpConnection connection) {
        super(username, userRole, command, connection);
    }

    @Override
    public String Execute() {
        return Command();
    }

    @Override
    protected String Command() {
        try {
            List<String> failures = new ArrayList<>();

            KahluaTable raw = LuaManager.platform.newTable();
            raw.rawset("name", "alice");
            raw.rawset("score", 17.5);
            raw.rawset("active", Boolean.TRUE);
            raw.rawset("flagged", Boolean.FALSE);
            raw.rawset(1, "first");
            raw.rawset(2, "second");

            KahluaTable nestedRaw = LuaManager.platform.newTable();
            nestedRaw.rawset("count", 3.0);
            raw.rawset("nested", nestedRaw);

            KahluaTable intInner = LuaManager.platform.newTable();
            intInner.rawset("k", "v");
            raw.rawset(99, intInner);

            String code =
                    "return { "
                            + "doubleIt = function(n) return n * 2 end, "
                            + "greet = function(name) return 'hi ' .. name end, "
                            + "noReturn = function() end, "
                            + "errorOut = function() error('intentional') end, "
                            + "makeTable = function() return { kind = 'made' } end, "
                            + "noArgs = function() return 7 end "
                            + "}";
            LuaClosure closure =
                    LuaCompiler.loadstring(code, "stormtest_kahluatable", LuaManager.env);
            Object[] compileResults = LuaManager.caller.pcall(LuaManager.thread, closure);
            if (compileResults == null
                    || compileResults.length < 2
                    || !Boolean.TRUE.equals(compileResults[0])) {
                String err =
                        compileResults != null && compileResults.length > 1
                                ? String.valueOf(compileResults[1])
                                : "(none)";
                return "RESULT ERROR fn_table_pcall_failed: " + err;
            }
            KahluaTable fnTable = (KahluaTable) compileResults[1];
            for (String fnName :
                    new String[] {
                        "doubleIt", "greet", "noReturn", "errorOut", "makeTable", "noArgs"
                    }) {
                Object fn = fnTable.rawget(fnName);
                if (fn == null) {
                    return "RESULT ERROR missing_fn:" + fnName;
                }
                raw.rawset(fnName, fn);
            }

            StormKahluaTable t = new StormKahluaTable(raw);

            // ---- Typed scalar accessors
            check(failures, "getString_name", "alice", t.getString("name"));
            check(failures, "getString_missing", null, t.getString("missing"));
            check(failures, "getDouble_score", 17.5, t.getDouble("score"));
            check(failures, "getDouble_missing", null, t.getDouble("missing"));
            check(failures, "getBoolean_active", Boolean.TRUE, t.getBoolean("active"));
            check(failures, "getBoolean_flagged", Boolean.FALSE, t.getBoolean("flagged"));
            check(failures, "getBoolean_missing", null, t.getBoolean("missing"));

            // ---- KahluaTable interface proxies
            check(failures, "rawget_str", "alice", t.rawget("name"));
            check(failures, "rawget_int1", "first", t.rawget(1));
            check(failures, "rawget_int2", "second", t.rawget(2));
            check(failures, "isEmpty_false", false, t.isEmpty());
            check(failures, "size_positive", true, t.size() > 0);
            check(failures, "len_positive", true, t.len() >= 0);

            t.rawset("dynamic", "value");
            check(failures, "rawset_str_passthrough", "value", raw.rawget("dynamic"));
            t.rawset(50, "fifty");
            check(failures, "rawset_int_passthrough", "fifty", raw.rawget(50));
            // setMetatable is a no-op by design — exercising it must not throw
            try {
                t.setMetatable(LuaManager.platform.newTable());
            } catch (Exception e) {
                failures.add("setMetatable_threw:" + e.getMessage());
            }

            // ---- getOptionalTable(String)
            Optional<StormKahluaTable> nested = t.getOptionalTable("nested");
            check(failures, "optTable_present", true, nested.isPresent());
            if (nested.isPresent()) {
                check(failures, "nested_count", 3.0, nested.get().getDouble("count"));
            }
            check(failures, "optTable_missing", true, t.getOptionalTable("notThere").isEmpty());
            check(failures, "optTable_wrongType", true, t.getOptionalTable("name").isEmpty());

            // ---- getOptionalTable(int)
            check(failures, "optTable_int_present", true, t.getOptionalTable(99).isPresent());
            check(failures, "optTable_int_missing", true, t.getOptionalTable(100).isEmpty());
            check(failures, "optTable_int_wrongType", true, t.getOptionalTable(1).isEmpty());

            // ---- getTable(String) happy + missing
            try {
                StormKahluaTable n = t.getTable("nested");
                check(failures, "getTable_count", 3.0, n.getDouble("count"));
            } catch (RuntimeException e) {
                failures.add("getTable_threw:" + e.getMessage());
            }
            try {
                t.getTable("notThere");
                failures.add("getTable_missing_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }

            // ---- getTable(int) happy + missing
            try {
                StormKahluaTable n = t.getTable(99);
                check(failures, "getTable_int_k", "v", n.getString("k"));
            } catch (RuntimeException e) {
                failures.add("getTable_int_threw:" + e.getMessage());
            }
            try {
                t.getTable(100);
                failures.add("getTable_int_missing_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }

            // ---- getFunction happy + missing
            try {
                Object fn = t.getFunction("doubleIt");
                check(failures, "getFunction_nonNull", true, fn != null);
            } catch (RuntimeException e) {
                failures.add("getFunction_threw:" + e.getMessage());
            }
            try {
                t.getFunction("noSuchFn");
                failures.add("getFunction_missing_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }

            // ---- pcall(name, args) — Optional return
            Optional<Object> doubled = t.pcall("doubleIt", new Object[] {21.0});
            check(failures, "pcall_doubleIt_present", true, doubled.isPresent());
            check(failures, "pcall_doubleIt_value", 42.0, doubled.orElse(null));

            Optional<Object> greeted = t.pcall("greet", new Object[] {"world"});
            check(failures, "pcall_greet_value", "hi world", greeted.orElse(null));

            Optional<Object> noret = t.pcall("noReturn", new Object[] {});
            check(failures, "pcall_noReturn_empty", true, noret.isEmpty());

            // ---- pcall(name, type, args) — typed return
            Double doubledTyped = t.pcall("doubleIt", Double.class, new Object[] {11.0});
            check(failures, "pcall_typed_double", 22.0, doubledTyped);

            String greetedTyped = t.pcall("greet", String.class, new Object[] {"lua"});
            check(failures, "pcall_typed_string", "hi lua", greetedTyped);

            // StormKahluaTable special-case: lua returns KahluaTable, asks for StormKahluaTable
            StormKahluaTable made = t.pcall("makeTable", StormKahluaTable.class, new Object[] {});
            check(failures, "pcall_typed_table_nonnull", true, made != null);
            if (made != null) {
                check(failures, "pcall_typed_table_kind", "made", made.getString("kind"));
            }

            // ---- pcall(name, type) — no-args overload
            Double seven = t.pcall("noArgs", Double.class);
            check(failures, "pcall_noargs_value", 7.0, seven);

            // ---- pcall failure paths
            try {
                t.pcall("noSuchFn", new Object[] {});
                failures.add("pcall_missing_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }
            try {
                t.pcall("errorOut", new Object[] {});
                failures.add("pcall_luaError_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }
            try {
                t.pcall("greet", Integer.class, new Object[] {"x"});
                failures.add("pcall_wrongType_no_throw");
            } catch (ClassCastException expected) {
                // ok
            }
            try {
                t.pcall("noReturn", Double.class, new Object[] {});
                failures.add("pcall_typed_noResult_no_throw");
            } catch (RuntimeException expected) {
                // ok
            }

            // ---- wipe() on a fresh wrapped table
            StormKahluaTable wipeable = new StormKahluaTable(LuaManager.platform.newTable());
            wipeable.rawset("a", "b");
            check(failures, "before_wipe_nonempty", false, wipeable.isEmpty());
            wipeable.wipe();
            check(failures, "after_wipe_empty", true, wipeable.isEmpty());

            // ---- iterator() proxies through and yields entries we set
            int seen = 0;
            var it = t.iterator();
            while (it.advance()) {
                seen++;
            }
            check(failures, "iterator_yielded_entries", true, seen > 0);

            if (failures.isEmpty()) {
                return "RESULT ok=true failures=";
            }
            return "RESULT ok=false failures=" + String.join(",", failures);
        } catch (Exception e) {
            return "RESULT ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void check(List<String> failures, String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            failures.add(label + "(want=" + expected + ",got=" + actual + ")");
        }
    }
}
