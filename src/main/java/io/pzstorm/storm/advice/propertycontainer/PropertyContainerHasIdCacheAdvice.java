package io.pzstorm.storm.advice.propertycontainer;

import java.util.Arrays;
import net.bytebuddy.asm.Advice;
import zombie.core.TilePropertyAliasMap;
import zombie.core.properties.IsoPropertyType;
import zombie.core.properties.PropertyContainer;

/**
 * Advice for {@code zombie.core.properties.PropertyContainer.has(IsoPropertyType)}.
 *
 * <p>Vanilla dispatches through {@code has(String)}, which calls {@code
 * TilePropertyAliasMap.instance.getIDFromPropertyName(String)} — a {@code HashMap<String,Integer>}
 * lookup — every call, then {@code containsKey(short)}. The enum ordinal is stable and the name→ID
 * mapping is fixed for the lifetime of the JVM (the alias map is populated once at game load and
 * never mutated). Live-client sampling attributed 1.75% of MainThread to the {@code has} path under
 * {@code ParameterFootstepMaterial.getMaterial}, which walks every object on the character's square
 * each FMOD parameter update — the same enum passed thousands of times per frame.
 *
 * <p>The fast path caches the resolved short ID per enum ordinal in a {@code short[]} sized by
 * {@code IsoPropertyType.values().length}. Entries default to {@code -1}; on first hit the advice
 * resolves the name via {@link TilePropertyAliasMap} and stores it, then all future calls for that
 * enum bypass the string round-trip entirely and go straight to the Trove {@code
 * containsKey(short)} inherited from {@code TShortShortHashMap}.
 *
 * <p>Return encoding for {@code skipOn = OnNonDefaultValue}: enter returns {@code 0} to run the
 * vanilla body (null enum arg), {@code 1} for {@code false}, {@code 2} for {@code true}. Exit
 * unpacks the flag into the original {@code boolean} return. This lets a single {@code int} enter
 * return communicate both "skip body" and "which boolean value".
 */
public class PropertyContainerHasIdCacheAdvice {

    public static final short[] IDS = init();

    private static short[] init() {
        short[] a = new short[IsoPropertyType.values().length];
        Arrays.fill(a, (short) -1);
        return a;
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static int onEnter(
            @Advice.This PropertyContainer self, @Advice.Argument(0) IsoPropertyType type) {
        if (type == null) {
            return 0;
        }
        int ord = type.ordinal();
        short id = IDS[ord];
        if (id == -1) {
            id = (short) TilePropertyAliasMap.instance.getIDFromPropertyName(type.getName());
            IDS[ord] = id;
        }
        return self.containsKey(id) ? 2 : 1;
    }

    @Advice.OnMethodExit
    public static void onExit(
            @Advice.Enter int flag, @Advice.Return(readOnly = false) boolean ret) {
        if (flag == 2) {
            ret = true;
        } else if (flag == 1) {
            ret = false;
        }
    }
}
