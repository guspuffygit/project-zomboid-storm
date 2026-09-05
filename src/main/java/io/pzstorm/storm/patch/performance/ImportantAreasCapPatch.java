package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Makes the engine's important-area cap a sandbox option and its eviction deterministic. See {@link
 * ImportantAreasPolicy} for the behaviour analysis; configured via {@code
 * Storm.ImportantAreasMaximum}, which defaults to vanilla's 100.
 *
 * <p>Targets {@code ImportantAreaManager.updateOrAdd(int, int)}, the one method both engine bookers
 * ({@code IsoStove.update}, {@code BaseVehicle.updateImportantAreas}) call, with an enter/exit
 * advice pair that replaces its body on the server. {@code process()}, {@code load()} and {@code
 * save()} are untouched; the ten-second expiry and the save format are vanilla's.
 *
 * <p>The vanilla cap is an inlined {@code static final}, so the field itself cannot be patched and
 * the advice owns the comparison instead. If a future build renames the method or the list field
 * the advice would go quietly unattached, or attach to a list it does not own; {@link #dynamicType}
 * therefore asserts both shapes and fails loudly at registration, the same way {@code
 * ZombieRainWanderPatch} does.
 */
public class ImportantAreasCapPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.core.ImportantAreaManager";
    private static final String PKG = "io.pzstorm.storm.advice.importantareascap.";
    static final String METHOD = "updateOrAdd";
    static final String LIST_FIELD = "ImportantAreas";

    public ImportantAreasCapPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        TypeDescription target = typePool.describe(TARGET).resolve();
        if (target.getDeclaredMethods()
                .filter(
                        ElementMatchers.named(METHOD)
                                .and(ElementMatchers.takesArguments(int.class, int.class)))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ImportantAreasCapPatch: ImportantAreaManager no longer declares "
                            + METHOD
                            + "(int, int) - the important-area booking path has moved and this"
                            + " patch must be re-read against the new engine build before it is"
                            + " trusted");
        }
        if (target.getDeclaredFields().filter(ElementMatchers.named(LIST_FIELD)).isEmpty()) {
            throw new IllegalStateException(
                    "ImportantAreasCapPatch: ImportantAreaManager no longer declares the static "
                            + LIST_FIELD
                            + " list - the policy would be editing nothing; re-read the engine"
                            + " build before trusting this patch");
        }
        return builder.visit(
                Advice.to(
                                typePool.describe(PKG + "ImportantAreasUpdateOrAddAdvice")
                                        .resolve(),
                                locator)
                        .on(
                                ElementMatchers.named(METHOD)
                                        .and(
                                                ElementMatchers.takesArguments(
                                                        int.class, int.class))));
    }
}
