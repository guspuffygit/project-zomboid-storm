import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Standalone reader for Project Zomboid's global_mod_data.bin files.
 * Also generates a .lua restore script that can reload the data into a new save.
 *
 * Usage: java GlobalModDataReader <path-to-global_mod_data.bin> [output.lua]
 */
public class GlobalModDataReader {

    // Type constants matching KahluaTableImpl
    private static final byte TYPE_STRING  = 0;
    private static final byte TYPE_DOUBLE  = 1;
    private static final byte TYPE_TABLE   = 2;
    private static final byte TYPE_BOOLEAN = 3;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java GlobalModDataReader <path-to-global_mod_data.bin> [output.lua]");
            System.exit(1);
        }

        File file = new File(args[0]);
        byte[] data = readAllBytes(file);
        ByteBuffer bb = ByteBuffer.wrap(data);

        int worldVersion = bb.getInt();
        int tableCount = bb.getInt();
        System.out.println("World Version: " + worldVersion);
        System.out.println("Table Count:   " + tableCount);
        System.out.println();

        // Parse all tables
        List<String> tableNames = new ArrayList<>();
        List<Map<Object, Object>> tables = new ArrayList<>();

        for (int i = 0; i < tableCount; i++) {
            int dataBlockSize = bb.getInt();
            String tableName = readString(bb);
            Map<Object, Object> table = readLuaTable(bb, worldVersion);
            tableNames.add(tableName);
            tables.add(table);

            System.out.println("=== Table: " + tableName + " (block size: " + dataBlockSize + " bytes) ===");
            printTable(table, 1);
            System.out.println();
        }

        // Generate lua file
        String luaPath;
        if (args.length >= 2) {
            luaPath = args[1];
        } else {
            luaPath = file.getParent() + File.separator + "RestoreGlobalModData.lua";
        }

        writeLuaFile(luaPath, tableNames, tables);
        System.out.println("Lua restore script written to: " + luaPath);
    }

    // ── Binary reader ──

    private static String readString(ByteBuffer bb) {
        short numBytes = bb.getShort();
        if (numBytes <= 0) return "";
        byte[] bytes = new byte[numBytes];
        bb.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static Map<Object, Object> readLuaTable(ByteBuffer bb, int worldVersion) {
        int count = bb.getInt();
        Map<Object, Object> table = new LinkedHashMap<>();

        if (worldVersion >= 25) {
            for (int i = 0; i < count; i++) {
                byte keyType = bb.get();
                Object key = readValue(bb, worldVersion, keyType);
                byte valueType = bb.get();
                Object value = readValue(bb, worldVersion, valueType);
                table.put(key, value);
            }
        } else {
            for (int i = 0; i < count; i++) {
                byte valueType = bb.get();
                String key = readString(bb);
                Object value = readValue(bb, worldVersion, valueType);
                table.put(key, value);
            }
        }

        return table;
    }

    private static Object readValue(ByteBuffer bb, int worldVersion, byte type) {
        return switch (type) {
            case TYPE_STRING  -> readString(bb);
            case TYPE_DOUBLE  -> bb.getDouble();
            case TYPE_TABLE   -> readLuaTable(bb, worldVersion);
            case TYPE_BOOLEAN -> bb.get() != 0;
            default -> throw new RuntimeException("Unknown lua type: " + type);
        };
    }

    // ── Console printer ──

    private static void printTable(Map<Object, Object> table, int indent) {
        String pad = "  ".repeat(indent);
        for (Map.Entry<Object, Object> entry : table.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            String keyStr = formatKey(key);

            if (value instanceof Map) {
                System.out.println(pad + keyStr + " = {");
                @SuppressWarnings("unchecked")
                Map<Object, Object> nested = (Map<Object, Object>) value;
                printTable(nested, indent + 1);
                System.out.println(pad + "}");
            } else {
                System.out.println(pad + keyStr + " = " + formatValue(value));
            }
        }
    }

    private static String formatKey(Object key) {
        if (key instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return "[" + (long) d.doubleValue() + "]";
            }
            return "[" + d + "]";
        }
        return String.valueOf(key);
    }

    private static String formatValue(Object value) {
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d.doubleValue());
            }
            return String.valueOf(d);
        }
        if (value instanceof String) {
            return "\"" + value + "\"";
        }
        return String.valueOf(value);
    }

    // ── Lua file writer ──

    private static void writeLuaFile(String path, List<String> tableNames,
                                     List<Map<Object, Object>> tables) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            w.println("-- RestoreGlobalModData.lua");
            w.println("-- Auto-generated from global_mod_data.bin");
            w.println("-- Place in media/lua/server/ of a mod, or run from the server console.");
            w.println("--");
            w.println("-- This script restores all GlobalModData tables from a previous save.");
            w.println("-- It uses ModData.getOrCreate() so it won't clobber tables that already exist;");
            w.println("-- instead it merges into them. To do a clean restore, call ModData.remove() first.");
            w.println();
            w.println("local function restoreGlobalModData()");

            for (int i = 0; i < tableNames.size(); i++) {
                String name = tableNames.get(i);
                Map<Object, Object> table = tables.get(i);

                w.println();
                w.println("    -- Table: " + name);
                w.println("    do");
                w.println("        local t = ModData.getOrCreate(\"" + escapeLua(name) + "\")");
                writeLuaTableAssignments(w, "t", table, 2);
                w.println("    end");
            }

            w.println("end");
            w.println();
            w.println("Events.OnInitGlobalModData.Add(restoreGlobalModData)");
        }
    }

    private static void writeLuaTableAssignments(PrintWriter w, String varPath,
                                                  Map<Object, Object> table, int indent) {
        String pad = "    ".repeat(indent);

        for (Map.Entry<Object, Object> entry : table.entrySet()) {
            String luaKey = toLuaKey(entry.getKey());
            String accessor = varPath + "[" + luaKey + "]";
            Object value = entry.getValue();

            if (value instanceof Map) {
                w.println(pad + accessor + " = {}");
                @SuppressWarnings("unchecked")
                Map<Object, Object> nested = (Map<Object, Object>) value;
                writeLuaTableAssignments(w, accessor, nested, indent);
            } else {
                w.println(pad + accessor + " = " + toLuaValue(value));
            }
        }
    }

    private static String toLuaKey(Object key) {
        if (key instanceof String s) {
            return "\"" + escapeLua(s) + "\"";
        }
        if (key instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d.doubleValue());
            }
            return String.valueOf(d);
        }
        return "\"" + escapeLua(key.toString()) + "\"";
    }

    private static String toLuaValue(Object value) {
        if (value instanceof String s) {
            return "\"" + escapeLua(s) + "\"";
        }
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d.doubleValue());
            }
            return String.valueOf(d);
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return "nil";
    }

    private static String escapeLua(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\0", "\\0");
    }

    // ── Util ──

    private static byte[] readAllBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.readAllBytes();
        }
    }
}
