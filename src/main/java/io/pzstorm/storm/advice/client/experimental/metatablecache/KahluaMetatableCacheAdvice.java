package io.pzstorm.storm.advice.client.experimental.metatablecache;

import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import se.krka.kahlua.j2se.KahluaTableImpl;
import se.krka.kahlua.vm.KahluaTable;

public class KahluaMetatableCacheAdvice {

    /**
     * Stand-in placed into {@code KahluaThread.cachedMetatables} for classes whose metatable lookup
     * returned {@code null}. Compared by reference only; never read, written, or exposed to game
     * code.
     */
    public static final KahluaTable NULL_SENTINEL = new KahluaTableImpl(new HashMap<>());

    @Advice.OnMethodExit
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void onExit(
            @Advice.Argument(0) Class<?> c,
            @Advice.Return(readOnly = false) KahluaTable result,
            @Advice.FieldValue("cachedMetatables") Map cache) {
        if (result == null) {
            cache.putIfAbsent(c, NULL_SENTINEL);
        } else if (result == NULL_SENTINEL) {
            result = null;
        }
    }
}
