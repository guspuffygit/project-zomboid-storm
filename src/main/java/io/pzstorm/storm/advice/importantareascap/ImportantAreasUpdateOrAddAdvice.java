package io.pzstorm.storm.advice.importantareascap;

import io.pzstorm.storm.patch.performance.ImportantAreasPolicy;
import java.util.LinkedList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import zombie.core.ImportantArea;

/**
 * Body-replacement advice for {@code ImportantAreaManager.updateOrAdd(int, int)}. The enter advice
 * hands the instrumented class's own static {@code ImportantAreas} list to {@link
 * ImportantAreasPolicy#decide}; a non-null decision skips the vanilla body and the exit advice
 * turns it into the return value ({@link ImportantAreasPolicy#EVICTED} becomes vanilla's {@code
 * null}). A null decision, which is what the policy returns off the server or once its failure
 * latch has tripped, runs the vanilla body untouched.
 *
 * <p>The list is taken from the instrumented class rather than read through {@code
 * ImportantAreaManager.ImportantAreas} inside the policy so that the policy edits exactly the list
 * whose method it is replacing, whichever class loader defined it. Advice bodies are inlined into
 * the target method, so this class stays plain imperative Java.
 */
public class ImportantAreasUpdateOrAddAdvice {

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static Object onEnter(
            @Advice.FieldValue("ImportantAreas") LinkedList<ImportantArea> areas,
            @Advice.Argument(0) int x,
            @Advice.Argument(1) int y) {
        return ImportantAreasPolicy.decide(areas, x, y);
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter Object decision,
            @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object result) {
        if (decision != null) {
            result = decision == ImportantAreasPolicy.EVICTED ? null : decision;
        }
    }
}
