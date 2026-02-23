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
public class ZombieUtilsTransformer extends StormClassTransformer {

    public ZombieUtilsTransformer() {
        super("zombie.ZombieUtils");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                        // Match the method: setZombieProperties(int, boolean)
                        .method(
                                ElementMatchers.named("setZombieProperties")
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class, boolean.class)),
                                new ZombiePropertyVisitorWrapper()));
    }

    /** Custom VisitorWrapper to inject code before a specific field access. */
    private static class ZombiePropertyVisitorWrapper
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
                public void visitFieldInsn(
                        int opcode, String owner, String name, String descriptor) {
                    // Match the original target: PUTSTATIC zombie/ZombieUtils.zombiePropertyC : Z
                    if (opcode == Opcodes.PUTSTATIC
                            && "zombie/ZombieUtils".equals(owner)
                            && "zombiePropertyC".equals(name)
                            && "Z".equals(descriptor)) {
                        LOGGER.debug(
                                "Injecting zombiePropertyB initialization into setZombieProperties");

                        // 1. LDC "property"
                        super.visitLdcInsn("property");

                        // 2. PUTSTATIC zombie/ZombieUtils.zombiePropertyB : Ljava/lang/String;
                        super.visitFieldInsn(
                                Opcodes.PUTSTATIC,
                                "zombie/ZombieUtils",
                                "zombiePropertyB",
                                "Ljava/lang/String;");
                    }

                    // Write the original instruction (PUTSTATIC zombiePropertyC)
                    super.visitFieldInsn(opcode, owner, name, descriptor);
                }
            };
        }
    }
}
