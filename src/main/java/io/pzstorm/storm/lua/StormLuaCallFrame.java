package io.pzstorm.storm.lua;

import java.util.Optional;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;

/**
 * Wrapper around {@link LuaCallFrame} that exposes typed, null-safe accessors for arguments passed
 * from Lua to a {@link se.krka.kahlua.vm.JavaFunction}. Mirrors {@link StormKahluaTable}'s pattern
 * of converting raw Kahlua values into the Java types callers actually want.
 *
 * <p>Kahlua passes every Lua number as a {@link Double}, so {@code getLong}/{@code getInt} narrow
 * via {@link Number} and also accept numeric strings. Missing or wrong-typed slots return an empty
 * {@link Optional} (or {@code null} for the non-optional variants) rather than throwing.
 */
public class StormLuaCallFrame {

    private final LuaCallFrame frame;

    public StormLuaCallFrame(LuaCallFrame frame) {
        this.frame = frame;
    }

    public LuaCallFrame raw() {
        return frame;
    }

    public Optional<Long> getOptionalLong(int index) {
        Object value = frame.get(index);
        if (value instanceof Number n) {
            return Optional.of(n.longValue());
        }
        if (value instanceof String s) {
            try {
                return Optional.of(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> getOptionalInt(int index) {
        Object value = frame.get(index);
        if (value instanceof Number n) {
            return Optional.of(n.intValue());
        }
        if (value instanceof String s) {
            try {
                return Optional.of(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<Double> getOptionalDouble(int index) {
        Object value = frame.get(index);
        if (value instanceof Number n) {
            return Optional.of(n.doubleValue());
        }
        if (value instanceof String s) {
            try {
                return Optional.of(Double.parseDouble(s));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<String> getOptionalString(int index) {
        Object value = frame.get(index);
        return value instanceof String s ? Optional.of(s) : Optional.empty();
    }

    public Optional<StormKahluaTable> getOptionalTable(int index) {
        Object value = frame.get(index);
        if (value instanceof KahluaTable t) {
            return Optional.of(new StormKahluaTable(t));
        }
        return Optional.empty();
    }

    public Long getLong(int index) {
        return getOptionalLong(index).orElse(null);
    }

    public Integer getInt(int index) {
        return getOptionalInt(index).orElse(null);
    }

    public Double getDouble(int index) {
        return getOptionalDouble(index).orElse(null);
    }

    public String getString(int index) {
        return getOptionalString(index).orElse(null);
    }

    public StormKahluaTable getTable(int index) {
        return getOptionalTable(index).orElse(null);
    }

    /**
     * Coerces the slot to a boolean using Lua-ish semantics: a {@link Boolean} returns its value, a
     * {@link Number} returns {@code true} when non-zero, any other non-null value returns {@code
     * true}, and {@code null} returns {@code false}.
     */
    public boolean getBool(int index) {
        Object value = frame.get(index);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0.0;
        }
        return value != null;
    }
}
