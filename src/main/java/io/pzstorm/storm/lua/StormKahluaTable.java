package io.pzstorm.storm.lua;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import zombie.Lua.LuaManager;

public class StormKahluaTable implements KahluaTable {

    private final KahluaTable table;

    public StormKahluaTable(KahluaTable table) {
        this.table = table;
    }

    @Override
    public void setMetatable(KahluaTable kahluaTable) {}

    @Override
    public KahluaTable getMetatable() {
        return table.getMetatable();
    }

    @Override
    public void rawset(Object o, Object o1) {
        table.rawset(o, o1);
    }

    @Override
    public Object rawget(Object o) {
        return table.rawget(o);
    }

    @Override
    public void rawset(int i, Object o) {
        table.rawset(i, o);
    }

    @Override
    public Object rawget(int i) {
        return table.rawget(i);
    }

    @Override
    public int len() {
        return table.len();
    }

    @Override
    public KahluaTableIterator iterator() {
        return table.iterator();
    }

    @Override
    public boolean isEmpty() {
        return table.isEmpty();
    }

    @Override
    public void wipe() {
        table.wipe();
    }

    @Override
    public int size() {
        return table.size();
    }

    @Override
    public void save(ByteBuffer byteBuffer) throws IOException {
        table.save(byteBuffer);
    }

    @Override
    public void load(ByteBuffer byteBuffer, int i) throws IOException {
        table.load(byteBuffer, i);
    }

    @Override
    public void save(DataOutputStream dataOutputStream) throws IOException {
        table.save(dataOutputStream);
    }

    @Override
    public void load(DataInputStream dataInputStream, int i) throws IOException {
        table.load(dataInputStream, i);
    }

    @Override
    public String getString(String name) {
        return (String) table.rawget(name);
    }

    public Double getDouble(String name) {
        return (Double) table.rawget(name);
    }

    public Boolean getBoolean(String name) {
        return (Boolean) table.rawget(name);
    }

    public Optional<StormKahluaTable> getOptionalTable(String name) {
        Object tableObj = table.rawget(name);
        if (tableObj instanceof KahluaTable) {
            return Optional.of(new StormKahluaTable((KahluaTable) tableObj));
        }

        return Optional.empty();
    }

    public Optional<StormKahluaTable> getOptionalTable(int index) {
        Object tableObj = table.rawget(index);
        if (tableObj instanceof KahluaTable) {
            return Optional.of(new StormKahluaTable((KahluaTable) tableObj));
        }

        return Optional.empty();
    }

    public StormKahluaTable getTable(String name) {
        return getOptionalTable(name)
                .orElseThrow(() -> new RuntimeException("Unable to get Kahlua table " + name));
    }

    public StormKahluaTable getTable(int index) {
        return getOptionalTable(index)
                .orElseThrow(() -> new RuntimeException("Unable to get Kahlua table " + index));
    }

    public Object getFunction(String name) {
        Object luaFunction = table.rawget(name);

        if (luaFunction == null) {
            String message = String.format("Function %s not found in table.", name);
            LOGGER.error(message);
            throw new RuntimeException(message);
        }

        return luaFunction;
    }

    /**
     * Throws an exception if the call was unsuccessful
     *
     * @param name of the Lua function on the table to call
     * @param args any arguments to pass to the lua function
     * @return Optional Object if the function returned a response
     */
    public Optional<Object> pcall(String name, Object[] args) {
        Object luaFunction = getFunction(name);
        Object[] results = LuaManager.caller.pcall(LuaManager.thread, luaFunction, args);
        if (results == null || results.length < 1) {
            LOGGER.error("Results were null calling luaFunction {}", name);
            throw new RuntimeException("Results were null calling luaFunction " + name);
        }

        Boolean succeeded = (Boolean) results[0];

        if (!succeeded) {
            LOGGER.error("luaFunction result was unsuccessful {}", name);
            throw new RuntimeException("luaFunction result was unsuccessful " + name);
        }

        if (results.length > 1) {
            return Optional.of(results[1]);
        }

        return Optional.empty();
    }

    public <T> T pcall(String name, Class<T> type, Object[] args) {
        Object result =
                pcall(name, args)
                        .orElseThrow(
                                () ->
                                        new RuntimeException(
                                                "Lua function %s did not return a result"
                                                        .formatted(name)));

        if (type.isInstance(result)) {
            return type.cast(result);
        } else if (type == StormKahluaTable.class && result instanceof KahluaTable) {
            StormKahluaTable wrapped = new StormKahluaTable((KahluaTable) result);
            return type.cast(wrapped);
        }

        throw new ClassCastException(
                "Expected result of type "
                        + type.getSimpleName()
                        + " but received "
                        + (result == null ? "null" : result.getClass().getSimpleName()));
    }

    public <T> T pcall(String name, Class<T> type) {
        return pcall(name, type, new Object[] {});
    }
}
