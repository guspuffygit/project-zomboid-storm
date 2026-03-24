package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.core.znet.SteamWorkshop;
import zombie.network.GameServer;

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

        /** Steam Workshop ID for Storm (production). */
        private static final long STORM_WORKSHOP_ID = 3670772371L;

        /** Steam Workshop ID for Storm (dev). */
        private static final long STORM_DEV_WORKSHOP_ID = 3676481910L;

        @Advice.OnMethodEnter
        public static ArrayList<Long> beforeInstall(
                @Advice.Argument(value = 0, readOnly = false) ArrayList<Long> itemIDList) {
            ArrayList<Long> removedIds = new ArrayList<>();
            int before = itemIDList.size();
            Iterator<Long> it = itemIDList.iterator();
            while (it.hasNext()) {
                long id = it.next();
                if (id == STORM_WORKSHOP_ID || id == STORM_DEV_WORKSHOP_ID) {
                    removedIds.add(id);
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
            return removedIds;
        }

        @Advice.OnMethodExit
        public static void afterInstall(@Advice.Enter ArrayList<Long> removedIds) {
            if (removedIds == null || removedIds.isEmpty()) {
                return;
            }

            // Collect valid Storm install folders that Steam already knows about
            ArrayList<String> stormFolders = new ArrayList<>();
            ArrayList<Long> stormTimestamps = new ArrayList<>();
            for (int i = 0; i < removedIds.size(); i++) {
                long stormId = removedIds.get(i);
                String folder = SteamWorkshop.instance.GetItemInstallFolder(stormId);
                if (folder != null && new File(folder).exists()) {
                    stormFolders.add(folder);
                    stormTimestamps.add(
                            SteamWorkshop.instance.GetItemInstallTimeStamp(stormId));
                    LOGGER.info("[Storm] Re-added Storm workshop folder: {}", folder);
                } else {
                    LOGGER.warn(
                            "[Storm] Could not find install folder for workshop item {}",
                            stormId);
                }
            }

            if (stormFolders.isEmpty()) {
                return;
            }

            // Expand GameServer arrays to include Storm's folders
            String[] oldFolders = GameServer.workshopInstallFolders;
            long[] oldTimestamps = GameServer.workshopTimeStamps;
            int oldLen = oldFolders != null ? oldFolders.length : 0;
            int newLen = oldLen + stormFolders.size();

            String[] newFolders = new String[newLen];
            long[] newTimestamps = new long[newLen];

            for (int i = 0; i < oldLen; i++) {
                newFolders[i] = oldFolders[i];
                newTimestamps[i] = oldTimestamps[i];
            }

            for (int i = 0; i < stormFolders.size(); i++) {
                newFolders[oldLen + i] = stormFolders.get(i);
                newTimestamps[oldLen + i] = stormTimestamps.get(i);
            }

            GameServer.workshopInstallFolders = newFolders;
            GameServer.workshopTimeStamps = newTimestamps;
        }
    }
}
