package io.pzstorm.storm.event;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaTableIterator;
import se.krka.kahlua.vm.LuaCallFrame;

/** Minimal HashMap-backed KahluaTable for unit tests. */
public class StubKahluaTable implements KahluaTable {

    private final Map<Object, Object> map = new HashMap<>();

    public StubKahluaTable() {}

    public StubKahluaTable(Map<String, Object> values) {
        map.putAll(values);
    }

    @Override
    public void setMetatable(KahluaTable kahluaTable) {}

    @Override
    public KahluaTable getMetatable() {
        return null;
    }

    @Override
    public void rawset(Object key, Object value) {
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    @Override
    public Object rawget(Object key) {
        return map.get(key);
    }

    @Override
    public void rawset(int index, Object value) {
        rawset((Object) index, value);
    }

    @Override
    public Object rawget(int index) {
        return rawget((Object) index);
    }

    @Override
    public int len() {
        return map.size();
    }

    @Override
    public KahluaTableIterator iterator() {
        Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
        return new KahluaTableIterator() {
            private Map.Entry<Object, Object> current;

            @Override
            public boolean advance() {
                if (it.hasNext()) {
                    current = it.next();
                    return true;
                }
                return false;
            }

            @Override
            public Object getKey() {
                return current.getKey();
            }

            @Override
            public Object getValue() {
                return current.getValue();
            }

            @Override
            public int call(LuaCallFrame frame, int nargs) {
                return 0;
            }
        };
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public void wipe() {
        map.clear();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public void save(ByteBuffer byteBuffer) throws IOException {}

    @Override
    public void load(ByteBuffer byteBuffer, int version) throws IOException {}

    @Override
    public void save(DataOutputStream dataOutputStream) throws IOException {}

    @Override
    public void load(DataInputStream dataInputStream, int version) throws IOException {}

    @Override
    public String getString(String name) {
        Object val = map.get(name);
        return val instanceof String ? (String) val : null;
    }
}
