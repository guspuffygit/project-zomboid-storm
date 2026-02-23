package io.pzstorm.storm.patch.rendering;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnMainScreenRenderEvent;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.Core;

public class MainScreenStatePatch extends StormClassTransformer {

    public MainScreenStatePatch() {
        super("zombie.gameStates.MainScreenState");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        TypeDescription thisClass = new TypeDescription.ForLoadedType(MainScreenStatePatch.class);
        MethodDescription hookMethod =
                thisClass
                        .getDeclaredMethods()
                        .filter(ElementMatchers.named("hookEndFrameUI"))
                        .getOnly();

        return builder.visit(
                MemberSubstitution.strict()
                        .method(ElementMatchers.named("EndFrameUI"))
                        .replaceWith(hookMethod)
                        .on(ElementMatchers.named("render")));
    }

    public static void hookEndFrameUI(Core core) {
        StormEventDispatcher.dispatchEvent(new OnMainScreenRenderEvent());

        core.EndFrameUI();
    }
}
