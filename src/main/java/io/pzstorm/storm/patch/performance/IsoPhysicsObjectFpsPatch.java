package io.pzstorm.storm.patch.performance;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.PerformanceSettings;
import zombie.network.GameServer;

/**
 * Substitutes the FPS-resolution expression in {@code IsoPhysicsObject.update()}. Vanilla:
 *
 * <pre>{@code
 * int fps = GameServer.server ? 10 : PerformanceSettings.getLockFPS();
 * }</pre>
 *
 * After substitution (scoped to {@code update()}):
 *
 * <ul>
 *   <li>{@code GameServer.server} field read → {@link IsoPhysicsObjectFpsConfig#alwaysFalse()}
 *       (always {@code false}, collapsing the ternary to its second branch).
 *   <li>{@code PerformanceSettings.getLockFPS()} call → {@link
 *       IsoPhysicsObjectFpsConfig#resolveFps()} (returns the configured server fps when {@code
 *       GameServer.server} is true, else delegates to the vanilla {@code lockFps}).
 * </ul>
 *
 * <p>Net effect: on the server, {@code fps} becomes the value configured via {@link
 * IsoPhysicsObjectFpsConfig#PHYSICS_FPS_PROPERTY}; on the client, behavior is unchanged.
 */
public class IsoPhysicsObjectFpsPatch extends StormClassTransformer {

    public IsoPhysicsObjectFpsPatch() {
        super("zombie.iso.IsoPhysicsObject");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        try {
            ElementMatcher.Junction<MethodDescription> updateMatcher =
                    ElementMatchers.named("update").and(ElementMatchers.takesArguments(0));
            return builder.visit(
                            MemberSubstitution.relaxed()
                                    .field(
                                            ElementMatchers.isDeclaredBy(GameServer.class)
                                                    .and(ElementMatchers.named("server")))
                                    .onRead()
                                    .replaceWith(
                                            IsoPhysicsObjectFpsConfig.class.getDeclaredMethod(
                                                    "alwaysFalse"))
                                    .on(updateMatcher))
                    .visit(
                            MemberSubstitution.relaxed()
                                    .method(
                                            ElementMatchers.isDeclaredBy(PerformanceSettings.class)
                                                    .and(ElementMatchers.named("getLockFPS"))
                                                    .and(ElementMatchers.takesArguments(0)))
                                    .replaceWith(
                                            IsoPhysicsObjectFpsConfig.class.getDeclaredMethod(
                                                    "resolveFps"))
                                    .on(updateMatcher));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(
                    "Failed to setup MemberSubstitution for IsoPhysicsObject fps", e);
        }
    }
}
