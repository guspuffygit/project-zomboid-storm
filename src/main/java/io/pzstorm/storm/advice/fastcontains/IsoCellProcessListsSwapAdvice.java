package io.pzstorm.storm.advice.fastcontains;

import io.pzstorm.storm.util.StormFastContainsList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Constructor-exit advice for {@code IsoCell(int, int)}: replaces the freshly initialized {@code
 * processItems} and {@code processWorldItems} fields with {@link StormFastContainsList} so {@code
 * addToProcessItems}'s membership probe and {@code ProcessRemoveItems}'s empty-argument {@code
 * removeAll} sweep stop scanning the whole list every tick. The patch strips {@code final} from
 * both fields so the write verifies.
 *
 * <p>Fields are bound as {@code Object} with dynamic typing (the write-back checkcast to {@code
 * ArrayList} always passes — the replacement is a subclass). No lambdas / streams — advice bodies
 * are inlined into the target constructor.
 */
public class IsoCellProcessListsSwapAdvice {

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.FieldValue(
                            value = "processItems",
                            readOnly = false,
                            typing = Assigner.Typing.DYNAMIC)
                    Object processItems,
            @Advice.FieldValue(
                            value = "processWorldItems",
                            readOnly = false,
                            typing = Assigner.Typing.DYNAMIC)
                    Object processWorldItems) {
        processItems = StormFastContainsList.copyOf(processItems);
        processWorldItems = StormFastContainsList.copyOf(processWorldItems);
    }
}
