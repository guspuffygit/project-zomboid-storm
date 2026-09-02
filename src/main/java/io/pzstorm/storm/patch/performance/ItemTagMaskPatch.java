package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.scripting.StormItemTags;
import java.util.Set;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Backs {@code zombie.scripting.objects.Item}'s tag set with a {@link
 * io.pzstorm.storm.scripting.StormItemTagSet}: adds the {@code stormItemTags} slot (public {@code
 * Set}) with {@link io.pzstorm.storm.entity.StormItemTagsHolder} accessors, and redirects every
 * read of the private final {@code itemTags} field inside {@code Item} — {@code hasTag}, {@code
 * getItemTags}, the script-parse {@code add} — to {@link StormItemTags#tagsOf(Object)}. The field's
 * own constructor write is left alone, so no final field is ever written. Pairs with {@link
 * ItemTagIndexPatch}. Server-only by registration gate. Fails loud if the field is renamed; the
 * bytecode test guards the {@code relaxed()} substitution against a silent miss.
 */
public class ItemTagMaskPatch extends StormClassTransformer {

    private static final String TARGET = "zombie.scripting.objects.Item";

    public ItemTagMaskPatch() {
        super(TARGET);
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        if (typePool.describe(TARGET)
                .resolve()
                .getDeclaredFields()
                .filter(ElementMatchers.named("itemTags"))
                .isEmpty()) {
            throw new IllegalStateException(
                    "ItemTagMaskPatch: Item no longer declares 'itemTags' — re-verify against the"
                            + " current game source.");
        }
        try {
            return builder.defineField("stormItemTags", Set.class, Visibility.PUBLIC)
                    .implement(
                            typePool.describe("io.pzstorm.storm.entity.StormItemTagsHolder")
                                    .resolve())
                    .intercept(FieldAccessor.ofBeanProperty())
                    .visit(
                            MemberSubstitution.relaxed()
                                    .field(
                                            ElementMatchers.named("itemTags")
                                                    .and(
                                                            ElementMatchers.isDeclaredBy(
                                                                    ElementMatchers.named(TARGET))))
                                    .onRead()
                                    .replaceWith(
                                            StormItemTags.class.getDeclaredMethod(
                                                    "tagsOf", Object.class))
                                    .on(ElementMatchers.any()));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("StormItemTags helper signature changed", e);
        }
    }
}
