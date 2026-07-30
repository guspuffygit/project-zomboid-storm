package io.pzstorm.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer. The launcher deliberately has no third-party dependencies, so this
 * covers the subset we need: objects, arrays, strings, numbers (Long/Double), booleans and null.
 * Maps preserve insertion order.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipWhitespace();
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw p.error("trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) {
            throw new JsonException("expected a JSON object at document root");
        }
        return (Map<String, Object>) value;
    }

    private Object parseValue() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        char c = src.charAt(pos);
        switch (c) {
            case '{':
                return parseObjectBody();
            case '[':
                return parseArrayBody();
            case '"':
                return parseString();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return parseNumber();
        }
    }

    private Map<String, Object> parseObjectBody() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // consume '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected object key");
            }
            String key = parseString();
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after object key");
            }
            pos++;
            skipWhitespace();
            map.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw error("expected ',' or '}' in object");
        }
    }

    private List<Object> parseArrayBody() {
        List<Object> list = new ArrayList<>();
        pos++; // consume '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw error("expected ',' or ']' in array");
        }
    }

    private String parseString() {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw error("unterminated escape");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':
                    sb.append('"');
                    break;
                case '\\':
                    sb.append('\\');
                    break;
                case '/':
                    sb.append('/');
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw error("truncated \\u escape");
                    }
                    sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw error("invalid escape '\\" + e + "'");
            }
        }
    }

    private Object parseNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        while (pos < src.length() && isNumberChar(src.charAt(pos))) {
            pos++;
        }
        String token = src.substring(start, pos);
        try {
            if (token.indexOf('.') < 0 && token.indexOf('e') < 0 && token.indexOf('E') < 0) {
                return Long.parseLong(token);
            }
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            throw error("invalid number '" + token + "'");
        }
    }

    private static boolean isNumberChar(char c) {
        return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
    }

    private void expect(String literal) {
        if (!src.startsWith(literal, pos)) {
            throw error("expected '" + literal + "'");
        }
        pos += literal.length();
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " at offset " + pos);
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0, true);
        return sb.toString();
    }

    public static String writeCompact(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0, false);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent, boolean pretty) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value, indent, pretty);
        } else if (value instanceof List) {
            writeList(sb, (List<?>) value, indent, pretty);
        } else {
            throw new JsonException("cannot serialize " + value.getClass().getName());
        }
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, int indent, boolean pretty) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, indent + 1, pretty);
            writeString(sb, String.valueOf(entry.getKey()));
            sb.append(pretty ? ": " : ":");
            writeValue(sb, entry.getValue(), indent + 1, pretty);
        }
        newline(sb, indent, pretty);
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> list, int indent, boolean pretty) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, indent + 1, pretty);
            writeValue(sb, item, indent + 1, pretty);
        }
        newline(sb, indent, pretty);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent, boolean pretty) {
        if (!pretty) {
            return;
        }
        sb.append('\n');
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String message) {
            super(message);
        }
    }
}
