package io.pzstorm.storm.core;

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

/**
 * Test transformer that patches {@code zombie.MultiPatchTarget.getB()} to return {@code
 * "patched-b"} instead of {@code "original-b"}.
 */
public class MultiPatchTransformerB extends StormClassTransformer {

    public MultiPatchTransformerB() {
        super("zombie.MultiPatchTarget");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                        .method(
                                ElementMatchers.named("getB")
                                        .and(ElementMatchers.returns(String.class)),
                                new StringReplacer()));
    }

    private static class StringReplacer
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
                    if ("original-b".equals(value)) {
                        super.visitLdcInsn("patched-b");
                    } else {
                        super.visitLdcInsn(value);
                    }
                }
            };
        }
    }
}
