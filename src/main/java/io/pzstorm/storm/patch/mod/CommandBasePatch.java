package io.pzstorm.storm.patch.mod;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.core.StormCommandRegistry;
import java.util.List;
import java.util.regex.Pattern;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.commands.CommandName;
import zombie.commands.DisabledCommand;

public class CommandBasePatch extends StormClassTransformer {

    public CommandBasePatch() {
        super("zombie.commands.CommandBase");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {

        return builder.visit(
                        Advice.to(GetSubClassesAdvice.class)
                                .on(ElementMatchers.named("getSubClasses")))
                .visit(
                        Advice.to(FindCommandClsAdvice.class)
                                .on(ElementMatchers.named("findCommandCls")));
    }

    public static class GetSubClassesAdvice {

        @Advice.OnMethodExit
        public static void afterGetSubClasses(
                @Advice.Return(readOnly = false) Class<?>[] returned) {

            List<Class<?>> modCommands = StormCommandRegistry.getModCommands();
            if (modCommands.isEmpty()) {
                return;
            }

            Class<?>[] merged = new Class<?>[returned.length + modCommands.size()];
            System.arraycopy(returned, 0, merged, 0, returned.length);
            for (int i = 0; i < modCommands.size(); i++) {
                merged[returned.length + i] = modCommands.get(i);
            }

            returned = merged;
        }
    }

    public static class FindCommandClsAdvice {

        @Advice.OnMethodExit
        public static void afterFindCommandCls(
                @Advice.Argument(0) String command,
                @Advice.Return(readOnly = false) Class<?> returned) {

            if (returned != null) {
                return;
            }

            for (Class<?> cls : StormCommandRegistry.getModCommands()) {
                if (cls.getAnnotation(DisabledCommand.class) != null) {
                    continue;
                }
                CommandName[] nameAnnotations = cls.getAnnotationsByType(CommandName.class);
                for (CommandName nameAnnotation : nameAnnotations) {
                    Pattern p =
                            Pattern.compile(
                                    "^" + nameAnnotation.name() + "\\b", Pattern.CASE_INSENSITIVE);
                    if (p.matcher(command).find()) {
                        returned = cls;
                        return;
                    }
                }
            }
        }
    }
}
