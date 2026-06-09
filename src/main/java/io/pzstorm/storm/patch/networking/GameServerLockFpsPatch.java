package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.PerformanceSettings;

/**
 * Substitutes the {@code PerformanceSettings.setLockFPS(int)} call inside {@code GameServer.main()}
 * with {@link ServerLockFpsConfig#applyServerLockFps(int)} so {@link ServerLockFpsConfig} can cache
 * the live value. The {@code Storm.ServerFps} sandbox option drives this via {@link
 * ServerFpsConfig#applyUnifiedFps(int)}.
 *
 * <p>Substitution is scoped to {@code main(String[])} so the client-side {@code setLockFPS} call
 * sites in {@code zombie.core.Core} (monitor refresh selection) are untouched.
 */
public class GameServerLockFpsPatch extends StormClassTransformer {

    public GameServerLockFpsPatch() {
        super("zombie.network.GameServer");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            ElementMatcher.Junction<MethodDescription> mainMatcher =
                    ElementMatchers.named("main")
                            .and(ElementMatchers.takesArgument(0, String[].class));
            return builder.visit(
                    MemberSubstitution.relaxed()
                            .method(
                                    ElementMatchers.isDeclaredBy(PerformanceSettings.class)
                                            .and(ElementMatchers.named("setLockFPS"))
                                            .and(ElementMatchers.takesArguments(int.class)))
                            .replaceWith(
                                    ServerLockFpsConfig.class.getDeclaredMethod(
                                            "applyServerLockFps", int.class))
                            .on(mainMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for GameServer lockFps", e);
        }
    }
}
