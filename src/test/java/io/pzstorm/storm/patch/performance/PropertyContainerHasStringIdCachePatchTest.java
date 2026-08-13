package io.pzstorm.storm.patch.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.InputStream;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code PropertyContainer.has(String)} advice actually weaves: the transformed method
 * must read the cached {@code doorTransId} field of the advice class (the inlined fast path) while
 * still containing the vanilla {@code getIDFromPropertyName} call (the fall-through body), and no
 * other method on the class may touch the advice state.
 *
 * <p>Uses ByteBuddy's bundled ASM (via {@code net.bytebuddy.jar.asm.*}) because the standalone
 * {@code org.ow2.asm:asm:9.1} test dependency is too old to read Java&nbsp;25 class files.
 */
class PropertyContainerHasStringIdCachePatchTest implements UnitTest {

    private static final String PROPERTY_CONTAINER = "zombie/core/properties/PropertyContainer";
    private static final String ADVICE_INTERNAL =
            "io/pzstorm/storm/advice/propertycontainer/PropertyContainerHasStringIdCacheAdvice";
    private static final String HAS_STRING_DESC = "(Ljava/lang/String;)Z";

    @Test
    void patchWeavesFastPathIntoHasStringOnly() throws Exception {
        String resourcePath = PROPERTY_CONTAINER + ".class";
        byte[] rawClass;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(is, "PropertyContainer.class must be on the test classpath");
            rawClass = is.readAllBytes();
        }

        byte[] transformed = new PropertyContainerHasStringIdCachePatch().transform(rawClass);
        assertNotNull(transformed);
        assertTrue(transformed.length > 0);

        Counts counts = countAccesses(transformed);

        assertTrue(
                counts.hasStringAdviceFieldReads >= 1,
                "has(String) should read the inlined doorTransId cache field; got "
                        + counts.hasStringAdviceFieldReads);
        assertTrue(
                counts.hasStringGetIdCalls >= 1,
                "has(String) must retain the vanilla getIDFromPropertyName fall-through; got "
                        + counts.hasStringGetIdCalls);
        assertEquals(
                0,
                counts.otherMethodAdviceFieldAccesses,
                "advice state must not leak outside has(String); got "
                        + counts.otherMethodAdviceFieldAccesses);
    }

    private static Counts countAccesses(byte[] classBytes) {
        Counts counts = new Counts();
        new ClassReader(classBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                final boolean isHasString =
                                        "has".equals(name) && HAS_STRING_DESC.equals(descriptor);
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fName, String fDesc) {
                                        if (ADVICE_INTERNAL.equals(owner)
                                                && "doorTransId".equals(fName)) {
                                            if (isHasString && opcode == Opcodes.GETSTATIC) {
                                                counts.hasStringAdviceFieldReads++;
                                            } else if (!isHasString) {
                                                counts.otherMethodAdviceFieldAccesses++;
                                            }
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mName,
                                            String mDesc,
                                            boolean isInterface) {
                                        if (isHasString && "getIDFromPropertyName".equals(mName)) {
                                            counts.hasStringGetIdCalls++;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return counts;
    }

    private static class Counts {
        int hasStringAdviceFieldReads;
        int hasStringGetIdCalls;
        int otherMethodAdviceFieldAccesses;
    }
}
