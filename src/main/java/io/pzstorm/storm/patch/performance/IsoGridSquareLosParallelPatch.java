package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.los.StormServerLosConfig;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.MemberSubstitution;
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
 * Server-only structural patch on {@code IsoGridSquare} that makes per-square LOS state safe to run
 * from multiple LOS workers concurrently. Three changes:
 *
 * <ol>
 *   <li>Enter advice on {@code CalcVisibility} lazily allocates {@code lighting[playerIndex]} for
 *       slots &gt; 0 (the server constructor only allocates slot 0).
 *   <li>{@code MemberSubstitution} on {@code CalcVisibility} redirects every read of the shared
 *       {@code static final Vector2} scratch fields {@code tempo} / {@code tempo2} to per-thread
 *       instances ({@code StormLosScratch.tempo()/tempo2()}), eliminating the cross-worker data
 *       race on them.
 *   <li>An ASM visitor enlarges the per-square {@code lighting} array from vanilla's length 4 to
 *       {@link StormServerLosConfig#MAX} in every constructor, so worker slots {@code 4..MAX-1}
 *       have a distinct {@code lighting[slot]} element. The array is built single-threaded during
 *       square creation, so growing it there keeps the "workers only touch disjoint slots"
 *       invariant with no extra synchronisation. The sibling {@code lightInfo} array (also length
 *       4) is left alone — the server LOS path never indexes it by slot.
 * </ol>
 *
 * <p>MUST be registration-gated to the dedicated server ({@code StormEnv.isStormServer()}) — {@code
 * IsoGridSquare} runs on the client and the HARD RULE forbids transforming client bytecode.
 *
 * <p>Fail-loud: every hook here is name-string based, so a vanilla rename or shape change would
 * otherwise silently no-op and reintroduce the cross-worker races (shared {@code tempo}/{@code
 * tempo2} scratch, AIOOBE on {@code lighting[slot]} for slots &ge; 4). {@link #dynamicType} throws
 * if {@code CalcVisibility}/{@code tempo}/{@code tempo2} are no longer declared, and {@link
 * #transform(byte[])} throws if the {@code ILighting} array-size rewrite matched zero allocations —
 * a server that fails to boot beats one that races.
 */
public class IsoGridSquareLosParallelPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.isogridsquarelos.IsoGridSquareCalcVisibilityAdvice";
    private static final String SCRATCH = "io.pzstorm.storm.los.StormLosScratch";

    /** Internal name of the array element type whose per-square allocation we enlarge. */
    private static final String ILIGHTING = "zombie/iso/IsoGridSquare$ILighting";

    private final LightingArraySizeWrapper lightingWrapper = new LightingArraySizeWrapper();

    public IsoGridSquareLosParallelPatch() {
        super("zombie.iso.IsoGridSquare");
    }

    @Override
    public byte[] transform(byte[] rawClass) {
        byte[] transformed = super.transform(rawClass);
        if (lightingWrapper.rewrites == 0) {
            throw new IllegalStateException(
                    "IsoGridSquareLosParallelPatch: no 'new IsoGridSquare$ILighting[...]'"
                            + " allocation was rewritten in any constructor — the vanilla"
                            + " array-init shape changed and LOS workers would AIOOBE on"
                            + " lighting[slot] for slots >= 4. Re-verify the patch against the"
                            + " current game source.");
        }
        return transformed;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(className).resolve();
        requireDeclared(
                !target.getDeclaredMethods()
                        .filter(ElementMatchers.named("CalcVisibility"))
                        .isEmpty(),
                "method CalcVisibility (per-slot lighting advice + scratch substitution target)");
        requireDeclared(
                !target.getDeclaredFields().filter(ElementMatchers.named("tempo")).isEmpty(),
                "static scratch field tempo");
        requireDeclared(
                !target.getDeclaredFields().filter(ElementMatchers.named("tempo2")).isEmpty(),
                "static scratch field tempo2");

        TypeDescription scratch = typePool.describe(SCRATCH).resolve();
        MethodDescription tempoRepl =
                scratch.getDeclaredMethods().filter(ElementMatchers.named("tempo")).getOnly();
        MethodDescription tempo2Repl =
                scratch.getDeclaredMethods().filter(ElementMatchers.named("tempo2")).getOnly();

        builder =
                builder.visit(
                        Advice.to(typePool.describe(ADVICE).resolve(), locator)
                                .on(ElementMatchers.named("CalcVisibility")));
        builder =
                builder.visit(
                        MemberSubstitution.relaxed()
                                .field(ElementMatchers.named("tempo"))
                                .onRead()
                                .replaceWith(tempoRepl)
                                .on(ElementMatchers.named("CalcVisibility")));
        builder =
                builder.visit(
                        MemberSubstitution.relaxed()
                                .field(ElementMatchers.named("tempo2"))
                                .onRead()
                                .replaceWith(tempo2Repl)
                                .on(ElementMatchers.named("CalcVisibility")));
        builder =
                builder.visit(
                        new AsmVisitorWrapper.ForDeclaredMethods()
                                .invokable(ElementMatchers.isConstructor(), lightingWrapper));
        return builder;
    }

    private static void requireDeclared(boolean present, String member) {
        if (!present) {
            throw new IllegalStateException(
                    "IsoGridSquareLosParallelPatch: IsoGridSquare no longer declares "
                            + member
                            + " — the name-string hook would silently no-op and reintroduce the"
                            + " cross-worker LOS race. Re-verify the patch against the current"
                            + " game source.");
        }
    }

    /**
     * Rewrites {@code new IsoGridSquare$ILighting[4]} to {@code [MAX]} in each constructor,
     * counting rewrites so {@link #transform(byte[])} can fail loudly on zero matches.
     */
    private static final class LightingArraySizeWrapper
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {

        int rewrites;

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
                public void visitTypeInsn(int opcode, String type) {
                    if (opcode == Opcodes.ANEWARRAY && ILIGHTING.equals(type)) {
                        super.visitInsn(Opcodes.POP); // discard vanilla size (4)
                        pushInt(this.mv, StormServerLosConfig.MAX); // push MAX
                        LightingArraySizeWrapper.this.rewrites++;
                    }
                    super.visitTypeInsn(opcode, type);
                }
            };
        }
    }

    /** Emits the most compact bytecode to push {@code value} onto the operand stack. */
    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
