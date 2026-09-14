package io.pzstorm.storm.patch.client;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skip an unready preview part for this frame, returning its allocation to the pool. B42.20.4
 * dereferences getSkinTransforms() without checking its nullable result. This changes only the UI
 * preview, not world vehicle rendering or animation state.
 */
public class VehiclePreviewSkinTransformsPatch extends StormClassTransformer {
    private static final String RENDER_DATA = "zombie/vehicles/UI3DScene$VehicleModelRenderData";

    public VehiclePreviewSkinTransformsPatch() {
        super("zombie.vehicles.UI3DScene$VehicleRenderData");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                new AsmVisitorWrapper.ForDeclaredMethods()
                        .writerFlags(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
                        .method(
                                ElementMatchers.named("initPartModel")
                                        .and(ElementMatchers.takesArguments(4)),
                                (type, method, visitor, context, pool, writerFlags, readerFlags) ->
                                        new MethodVisitor(Opcodes.ASM9, visitor) {
                                            private boolean awaitingStore;
                                            private int allocationSlot = -1;
                                            private int guards;

                                            @Override
                                            public void visitTypeInsn(int opcode, String name) {
                                                super.visitTypeInsn(opcode, name);
                                                if (opcode == Opcodes.CHECKCAST
                                                        && RENDER_DATA.equals(name))
                                                    awaitingStore = true;
                                            }

                                            @Override
                                            public void visitVarInsn(int opcode, int slot) {
                                                super.visitVarInsn(opcode, slot);
                                                if (awaitingStore && opcode == Opcodes.ASTORE) {
                                                    allocationSlot = slot;
                                                    awaitingStore = false;
                                                }
                                            }

                                            @Override
                                            public void visitMethodInsn(
                                                    int opcode,
                                                    String owner,
                                                    String name,
                                                    String descriptor,
                                                    boolean itf) {
                                                super.visitMethodInsn(
                                                        opcode, owner, name, descriptor, itf);
                                                if (owner.equals(
                                                                "zombie/core/skinnedmodel/animation/AnimationPlayer")
                                                        && name.equals("getSkinTransforms")
                                                        && descriptor.equals(
                                                                "(Lzombie/core/skinnedmodel/model/SkinningData;)[Lorg/lwjgl/util/vector/Matrix4f;")) {
                                                    if (allocationSlot < 0)
                                                        throw new IllegalStateException(
                                                                "Preview allocation layout changed");
                                                    Label ready = new Label();
                                                    super.visitInsn(Opcodes.DUP);
                                                    super.visitJumpInsn(Opcodes.IFNONNULL, ready);
                                                    super.visitInsn(Opcodes.POP);
                                                    super.visitVarInsn(
                                                            Opcodes.ALOAD, allocationSlot);
                                                    super.visitMethodInsn(
                                                            Opcodes.INVOKEVIRTUAL,
                                                            RENDER_DATA,
                                                            "release",
                                                            "()V",
                                                            false);
                                                    super.visitInsn(Opcodes.RETURN);
                                                    super.visitLabel(ready);
                                                    guards++;
                                                }
                                            }

                                            @Override
                                            public void visitEnd() {
                                                if (guards != 1)
                                                    throw new IllegalStateException(
                                                            "Expected one preview skin-transform guard; got "
                                                                    + guards);
                                                super.visitEnd();
                                            }
                                        }));
    }
}
