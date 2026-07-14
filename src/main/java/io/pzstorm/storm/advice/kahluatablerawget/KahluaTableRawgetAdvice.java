package io.pzstorm.storm.advice.kahluatablerawget;

import java.util.Map;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.vm.KahluaTable;
import zombie.core.Core;

/**
 * Advice for {@code KahluaTableImpl.rawget(Object)}.
 *
 * <p>Vanilla body:
 *
 * <pre>{@code
 * return !this.delegate.containsKey(key) && this.metatable != null
 *     ? this.metatable.rawget(key)
 *     : this.delegate.get(key);
 * }</pre>
 *
 * <p>Runs {@code containsKey} then {@code get}: two full {@code HashMap} hash + lookup passes per
 * call. Kahlua's {@code rawset} normalises {@code value == null} to {@code delegate.remove(key)}
 * (line 63), so the delegate never holds a null value. That makes {@code containsKey(key)} exactly
 * equivalent to {@code get(key) != null}, and the {@code containsKey} pass is redundant on every
 * hit.
 *
 * <p>Fast path collapses to a single {@code get}: return it if non-null; else consult the metatable
 * (or return null if there is none). Semantically identical for real Lua tables. Falls through to
 * vanilla when {@code Core.debug} is on so the Lua debugger's read-breakpoint side effect keeps
 * running, and when {@code reloadReplace} is populated so the reload chain is untouched.
 *
 * <p>rawget is called from every Lua-to-Java bridge dispatch and every table field read; the
 * MainThread driving profile shows it in ~6.5% of top-6 stack frames, with {@link
 * java.util.HashMap#hash} at ~6.9% self.
 */
public class KahluaTableRawgetAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.Argument(0) Object key,
            @Advice.FieldValue("reloadReplace") KahluaTable reloadReplace,
            @Advice.FieldValue("delegate") Map<Object, Object> delegate,
            @Advice.FieldValue("metatable") KahluaTable metatable,
            @Advice.Local("cached") Object cached) {
        if (reloadReplace != null || key == null || Core.debug) {
            return 0;
        }
        Object v = delegate.get(key);
        if (v != null) {
            cached = v;
            return 1;
        }
        if (metatable != null) {
            cached = metatable.rawget(key);
            return 1;
        }
        cached = null;
        return 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int code,
            @Advice.Local("cached") Object cached,
            @Advice.Return(readOnly = false) Object ret) {
        if (code == 1) {
            ret = cached;
        }
    }
}
