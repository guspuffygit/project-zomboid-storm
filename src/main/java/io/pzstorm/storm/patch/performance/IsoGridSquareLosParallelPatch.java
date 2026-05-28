package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Server-only structural patch on {@code IsoGridSquare.CalcVisibility} that makes the method safe
 * to run from multiple LOS workers concurrently. Two changes, both scoped to {@code
 * CalcVisibility}:
 *
 * <ol>
 *   <li>Enter advice lazily allocates {@code lighting[playerIndex]} for slots &gt; 0 (the server
 *       constructor only allocates slot 0).
 *   <li>{@code MemberSubstitution} redirects every read of the shared {@code static final Vector2}
 *       scratch fields {@code tempo} / {@code tempo2} to per-thread instances ({@code
 *       StormLosScratch.tempo()/tempo2()}), eliminating the cross-worker data race on them.
 * </ol>
 *
 * <p>MUST be registration-gated to the dedicated server ({@code StormEnv.isStormServer()}) — {@code
 * IsoGridSquare} runs on the client and the HARD RULE forbids transforming client bytecode.
 */
public class IsoGridSquareLosParallelPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.isogridsquarelos.IsoGridSquareCalcVisibilityAdvice";
    private static final String SCRATCH = "io.pzstorm.storm.los.StormLosScratch";

    public IsoGridSquareLosParallelPatch() {
        super("zombie.iso.IsoGridSquare");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
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
        return builder;
    }
}
