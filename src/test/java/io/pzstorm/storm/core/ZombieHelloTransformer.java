package io.pzstorm.storm.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

@SuppressWarnings("unused")
public class ZombieHelloTransformer extends StormClassTransformer {

    public ZombieHelloTransformer() {
        super("zombie.ZombieHello");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                        // Match method: getHello() returning String
                        .method(
                                ElementMatchers.named("getHello")
                                        .and(ElementMatchers.returns(String.class)),
                                new StringReplaceVisitor()));
    }

    /** Visitor that intercepts string constants (LDC) and replaces them. */
    private static class StringReplaceVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        @Override
        public MethodVisitor wrap(
                TypeDescription instrumentedType,
                MethodDescription instrumentedMethod,
                MethodVisitor methodVisitor,
                Implementation.Context implementationContext,
                TypePool typePool,
                int writerFlags,
                int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                @Override
                public void visitLdcInsn(Object value) {
                    // Check if the constant is a String (to avoid breaking int/float constants)
                    if (value instanceof String) {
                        LOGGER.debug("Replacing string constant in ZombieHello: {}", value);

                        // Replace ANY string found in this method with your message,
                        // replicating the behavior of the original loop.
                        super.visitLdcInsn("Zombie says: you die today!");
                    } else {
                        // If it's not a string (e.g., a number), keep it as is
                        super.visitLdcInsn(value);
                    }
                }
            };
        }
    }
}
