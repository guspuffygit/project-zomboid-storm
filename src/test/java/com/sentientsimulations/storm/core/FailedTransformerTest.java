package com.sentientsimulations.storm.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.IntegrationTest;
import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.core.StormClassTransformers;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link StormClassTransformers#applyAll} does when a transformer throws: a client logs
 * it and keeps the bytes of the transformers around it, a server fails the class load, and neither
 * reports the class as transformed. {@link LinkageError} is covered because that is what a
 * transformer gets when it reaches back into its own target, and {@code catch (Exception)} misses
 * it.
 */
class FailedTransformerTest implements IntegrationTest {

    private static final byte[] RAW = {1};

    private abstract static class StubTransformer extends StormClassTransformer {
        StubTransformer(String className) {
            super(className);
        }

        @Override
        public DynamicType.Builder<Object> dynamicType(
                ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
            throw new UnsupportedOperationException();
        }
    }

    private static StormClassTransformer appending(String target, int marker) {
        return new StubTransformer(target) {
            @Override
            public byte[] transform(byte[] rawClass) {
                byte[] longer = Arrays.copyOf(rawClass, rawClass.length + 1);
                longer[rawClass.length] = (byte) marker;
                return longer;
            }
        };
    }

    private static StormClassTransformer throwing(String target, RuntimeException failure) {
        return new StubTransformer(target) {
            @Override
            public byte[] transform(byte[] rawClass) {
                throw failure;
            }
        };
    }

    private static StormClassTransformer throwing(String target, LinkageError failure) {
        return new StubTransformer(target) {
            @Override
            public byte[] transform(byte[] rawClass) {
                throw failure;
            }
        };
    }

    @Test
    void clientKeepsTheBytesAroundAnException() throws Exception {
        String target = "zombie.FailedTransformerTest$ClientException";
        byte[] result =
                applyAs(
                        false,
                        target,
                        appending(target, 2),
                        throwing(target, new IllegalStateException("moved")),
                        appending(target, 3));

        assertArrayEquals(new byte[] {1, 2, 3}, result);
        assertFalse(StormClassTransformers.getTransformedClasses().contains(target));
    }

    @Test
    void clientKeepsTheBytesAroundALinkageError() throws Exception {
        String target = "zombie.FailedTransformerTest$ClientLinkage";
        byte[] result =
                applyAs(
                        false,
                        target,
                        appending(target, 2),
                        throwing(target, new ClassCircularityError("target")));

        assertArrayEquals(new byte[] {1, 2}, result);
        assertFalse(StormClassTransformers.getTransformedClasses().contains(target));
    }

    @Test
    void clientStillReportsAFullyTransformedClass() throws Exception {
        String target = "zombie.FailedTransformerTest$ClientClean";
        byte[] result = applyAs(false, target, appending(target, 2));

        assertArrayEquals(new byte[] {1, 2}, result);
        assertTrue(StormClassTransformers.getTransformedClasses().contains(target));
    }

    @Test
    void serverFailsTheLoadOnAnException() {
        String target = "zombie.FailedTransformerTest$ServerException";
        IllegalStateException failure = new IllegalStateException("moved");

        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> applyAs(true, target, throwing(target, failure))));
        assertFalse(StormClassTransformers.getTransformedClasses().contains(target));
    }

    @Test
    void serverFailsTheLoadOnALinkageError() {
        String target = "zombie.FailedTransformerTest$ServerLinkage";
        ClassCircularityError failure = new ClassCircularityError("target");

        assertSame(
                failure,
                assertThrows(
                        ClassCircularityError.class,
                        () -> applyAs(true, target, throwing(target, failure))));
        assertFalse(StormClassTransformers.getTransformedClasses().contains(target));
    }

    private static byte[] applyAs(
            boolean server, String target, StormClassTransformer... transformers) throws Exception {
        Map<String, List<StormClassTransformer>> registered = registered();
        String saved = System.getProperty("storm.server");
        registered.put(target, List.of(transformers));
        System.setProperty("storm.server", Boolean.toString(server));
        try {
            return StormClassTransformers.applyAll(target, RAW);
        } finally {
            registered.remove(target);
            if (saved == null) {
                System.clearProperty("storm.server");
            } else {
                System.setProperty("storm.server", saved);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<StormClassTransformer>> registered() throws Exception {
        Field field = StormClassTransformers.class.getDeclaredField("TRANSFORMERS");
        field.setAccessible(true);
        return (Map<String, List<StormClassTransformer>>) field.get(null);
    }
}
