package io.pzstorm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedDocument() {
        Map<String, Object> root =
                Json.parseObject(
                        "{\"a\": 1, \"b\": [true, null, \"x\"], \"c\": {\"d\": -2.5}, \"e\": \"\"}");
        assertEquals(1L, root.get("a"));
        assertEquals(Arrays.asList(true, null, "x"), root.get("b"));
        assertEquals(-2.5, ((Map<?, ?>) root.get("c")).get("d"));
        assertEquals("", root.get("e"));
    }

    @Test
    void parsesEscapes() {
        Object value = Json.parse("\"a\\n\\t\\\\\\\"\\u0041\"");
        assertEquals("a\n\t\\\"A", value);
    }

    @Test
    void parsesTopLevelScalars() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\": 1} extra"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{'a': 1}"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.JsonException.class, () -> Json.parseObject("[1,2]"));
    }

    @Test
    void writeReadRoundtrip() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "storm \"quoted\" \n line");
        map.put("port", 16261L);
        map.put("flag", true);
        map.put("list", List.of("a", "b"));
        map.put("nothing", null);
        String pretty = Json.write(map);
        assertTrue(pretty.contains("\n"));
        assertEquals(map, Json.parseObject(pretty));
        assertEquals(map, Json.parseObject(Json.writeCompact(map)));
    }
}
