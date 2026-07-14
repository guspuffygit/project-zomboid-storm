package io.pzstorm.storm.patch.core;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.zomboid.OnLoadModsEvent;
import io.pzstorm.storm.util.StormEnv;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/** Patches {@link zombie.ZomboidFileSystem} */
public class ZomboidFileSystemPatch extends StormClassTransformer {

    public ZomboidFileSystemPatch() {
        super("zombie.ZomboidFileSystem");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        builder =
                builder.visit(
                        Advice.to(LoadModsAdvice.class)
                                .on(
                                        ElementMatchers.named("loadMods")
                                                .and(
                                                        ElementMatchers.takesArgument(
                                                                0, ArrayList.class))));
        if (StormEnv.isStormServer()) {
            builder =
                    builder.visit(
                            Advice.to(DeleteDirectoryGuardAdvice.class)
                                    .on(
                                            ElementMatchers.named("deleteDirectory")
                                                    .and(
                                                            ElementMatchers.takesArgument(
                                                                    0, String.class))));
        }
        return builder;
    }

    /**
     * Server-only guard for {@code deleteDirectory(String)}, whose sole external caller is the Fail
     * branch of {@code GameServerWorkshopItems.Install}:
     *
     * <ul>
     *   <li>A null path skips the call - vanilla NPEs in {@code new File(null)} when the failing
     *       workshop item was never installed, killing the server before its retry loop runs.
     *   <li>A path inside {@code steamapps/workshop/content} skips the call so a workshop item
     *       whose update download fails (deleted/hidden item, post-update manifest deny-window)
     *       keeps its last successfully installed copy instead of being wiped before a retry that
     *       is about to fail again. Steam commits downloads file-by-file on success, so a later
     *       successful retry overwrites the preserved copy anyway. Set {@code
     *       -Dstorm.workshop.vanillaDeleteOnFail=true} to restore the vanilla wipe.
     * </ul>
     */
    public static class DeleteDirectoryGuardAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.Argument(0) String dirPath) {
            boolean skip = false;
            if (dirPath == null) {
                LOGGER.warn(
                        "deleteDirectory(null) called (failed workshop item with no install"
                                + " folder) - skipping instead of throwing NPE");
                skip = true;
            } else if (!Boolean.getBoolean("storm.workshop.vanillaDeleteOnFail")
                    && dirPath.replace('\\', '/').contains("/steamapps/workshop/content/")) {
                LOGGER.warn(
                        "Preserving workshop item folder after failed download instead of wiping"
                                + " it: {}",
                        dirPath);
                skip = true;
            }
            return skip;
        }
    }

    public static class LoadModsAdvice {
        @Advice.OnMethodEnter
        public static void onEnter(@Advice.Argument(0) ArrayList<String> mods) {
            List<String> modsList = Objects.requireNonNullElse(mods, Collections.emptyList());
            LOGGER.debug("OnLoadMods: {}", String.join(" ", modsList));
            StormEventDispatcher.dispatchEvent(new OnLoadModsEvent(modsList));
        }
    }
}
