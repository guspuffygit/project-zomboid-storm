package io.pzstorm.storm.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.pzstorm.storm.UnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TwoDecimalDoubleSerializerTest implements UnitTest {

    private record Wrapper(@JsonSerialize(using = TwoDecimalDoubleSerializer.class) Double tps) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundsRepeatingDecimalToTwoPlaces() throws Exception {
        Assertions.assertEquals(
                "{\"tps\":33.33}", MAPPER.writeValueAsString(new Wrapper(1000.0 / 30)));
    }

    @Test
    void roundsHalfUp() throws Exception {
        Assertions.assertEquals("{\"tps\":1.24}", MAPPER.writeValueAsString(new Wrapper(1.235)));
    }

    @Test
    void emitsTwoTrailingZerosForWholeNumber() throws Exception {
        Assertions.assertEquals("{\"tps\":50.00}", MAPPER.writeValueAsString(new Wrapper(50.0)));
    }

    @Test
    void writesAsJsonNumberNotString() throws Exception {
        String json = MAPPER.writeValueAsString(new Wrapper(33.333333333));
        Assertions.assertEquals("{\"tps\":33.33}", json);
        Assertions.assertFalse(
                json.contains(":\""), "tps value should be a number, not a string: " + json);
    }

    @Test
    void nullPassesThroughAsJsonNull() throws Exception {
        Assertions.assertEquals("{\"tps\":null}", MAPPER.writeValueAsString(new Wrapper(null)));
    }
}
