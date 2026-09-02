package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.characters.StormIndexedMaps;
import io.pzstorm.storm.core.StormClassTransformer;
import java.util.Map;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Backs a per-character {@code private final Map} field with a {@link
 * io.pzstorm.storm.characters.StormIndexedMap}: adds the {@code stormMap} slot (public {@code Map})
 * with {@link io.pzstorm.storm.entity.StormIndexedMapHolder} accessors and redirects every read of
 * the vanilla field inside the declaring class — including the constructor's population loop — to
 * {@link StormIndexedMaps#mapOf(Object)}. The field's own constructor write is left alone, so no
 * final field is ever written. Same shape as {@link ItemTagMaskPatch}; pairs with the matching
 * {@link RegistryKeyIndexPatch}. Server-only by registration gate. Fails loud if the field is
 * renamed; the bytecode test guards the {@code relaxed()} substitution against a silent miss.
 */
public abstract class IndexedMapFieldPatch extends StormClassTransformer {

    private final String field;

    protected IndexedMapFieldPatch(String target, String field) {
        super(target);
        this.field = field;
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(className)
                .resolve()
                .getDeclaredFields()
                .filter(ElementMatchers.named(field))
                .isEmpty()) {
            throw new IllegalStateException(
                    getClass().getSimpleName()
                            + ": "
                            + className
                            + " no longer declares '"
                            + field
                            + "' — re-verify against the current game source.");
        }
        try {
            return builder.defineField("stormMap", Map.class, Visibility.PUBLIC)
                    .implement(
                            typePool.describe("io.pzstorm.storm.entity.StormIndexedMapHolder")
                                    .resolve())
                    .intercept(FieldAccessor.ofBeanProperty())
                    .visit(
                            MemberSubstitution.relaxed()
                                    .field(
                                            ElementMatchers.named(field)
                                                    .and(
                                                            ElementMatchers.isDeclaredBy(
                                                                    ElementMatchers.named(
                                                                            className))))
                                    .onRead()
                                    .replaceWith(
                                            StormIndexedMaps.class.getDeclaredMethod(
                                                    "mapOf", Object.class))
                                    .on(ElementMatchers.any()));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("StormIndexedMaps helper signature changed", e);
        }
    }
}
