package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Skips {@code AdvancedAnimator.searchFolders(URI, Path)} when the folder it is asked to walk does
 * not exist, returning an empty list instead of walking a missing root.
 *
 * <h2>The noise this removes</h2>
 *
 * <p>{@code AdvancedAnimator.load()} builds the anim-set checksum by calling {@code
 * Files.walkFileTree} on {@code media/AnimSets} and {@code media/actiongroups} under every mod's
 * common dir and version dir, without checking that either folder exists. A missing root makes the
 * JDK invoke the visitor's {@code visitFileFailed}, which vanilla logs at {@code Error} severity
 * with a full stack trace and then continues. Every mod that ships no animation XML therefore
 * prints four {@code NoSuchFileException} stack traces at boot; a server with ~90 mods logs ~350 of
 * them before the world loads. The checksum result is identical either way — a missing folder
 * contributes no files.
 *
 * <h2>Why {@code searchFolders} and not the call sites</h2>
 *
 * <p>Both callers ({@code collectBaseGameFiles} and {@code loadModMedia}) do {@code
 * files.addAll(searchFolders(...))} mid-method, so the existence check has to live inside the
 * callee. The exit advice substitutes an empty list for the skipped method's null return so those
 * {@code addAll} calls stay valid.
 */
public class AdvancedAnimatorMissingFolderPatch extends StormClassTransformer {

    public AdvancedAnimatorMissingFolderPatch() {
        super("zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(SearchFoldersAdvice.class)
                        .on(
                                ElementMatchers.named("searchFolders")
                                        .and(ElementMatchers.takesArguments(2))
                                        .and(ElementMatchers.takesArgument(1, Path.class))));
    }

    public static class SearchFoldersAdvice {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(@Advice.Argument(1) Path dir) {
            return dir == null || !Files.isDirectory(dir);
        }

        @Advice.OnMethodExit
        public static void onExit(
                @Advice.Enter boolean skipped,
                @Advice.Return(readOnly = false) List<String> result) {
            if (skipped) {
                result = new ArrayList<>();
            }
        }
    }
}
