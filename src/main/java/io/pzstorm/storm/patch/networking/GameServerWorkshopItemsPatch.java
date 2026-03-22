package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.util.ArrayList;
import java.util.Iterator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@code GameServerWorkshopItems.Install()} to remove Storm's own workshop ID from the
 * update list. This prevents the PZ server from re-downloading Storm during startup, which would
 * cause a race condition since Storm's JARs are already loaded by the bootstrap agent.
 *
 * <p>Storm's workshop items should be updated externally (e.g. via steamcmd) before the server
 * starts.
 */
public class GameServerWorkshopItemsPatch extends StormClassTransformer {

    public GameServerWorkshopItemsPatch() {
        super("zombie.network.GameServerWorkshopItems");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        return builder.visit(
                Advice.to(InstallAdvice.class)
                        .on(
                                ElementMatchers.named("Install")
                                        .and(ElementMatchers.takesArgument(0, ArrayList.class))));
    }

    public static class InstallAdvice {

        @Advice.OnMethodEnter
        public static void beforeInstall(
                @Advice.Argument(value = 0, readOnly = false) ArrayList<Long> itemIDList) {
            int before = itemIDList.size();
            Iterator<Long> it = itemIDList.iterator();
            while (it.hasNext()) {
                long id = it.next();
                if (id == 3670772371L || id == 3676481910L) {
                    it.remove();
                }
            }
            int removed = before - itemIDList.size();
            if (removed > 0) {
                LOGGER.info(
                        "[Storm] Excluded {} Storm workshop item(s) from server update check",
                        removed);
            } else {
                LOGGER.warn("Storm was not removed from workshop ids");
            }
        }
    }
}
