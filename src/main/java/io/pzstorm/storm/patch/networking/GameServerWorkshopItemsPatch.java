package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.advice.gameserverworkshopitems.GameServerWorkshopItemsInstallAdvice;
import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@link zombie.network.GameServerWorkshopItems#Install(ArrayList)} so Storm gets a
 * callback after the dedicated server's blocking workshop sync completes. The advice hands off to
 * {@code StormWorkshopUpdateGuard} which compares on-disk jar mtimes against the snapshot Storm
 * captured in premain; if any jar moved, the JVM exits so the supervisor restarts the server with
 * the updated bytecode.
 *
 * <p>Server-only: this class is only registered when {@code -Dstorm.server=true}.
 */
public class GameServerWorkshopItemsPatch extends StormClassTransformer {

    public GameServerWorkshopItemsPatch() {
        super("zombie.network.GameServerWorkshopItems");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(GameServerWorkshopItemsInstallAdvice.class)
                        .on(
                                ElementMatchers.named("Install")
                                        .and(ElementMatchers.takesArguments(ArrayList.class))));
    }
}
