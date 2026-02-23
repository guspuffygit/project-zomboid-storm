package io.pzstorm.storm.patch;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.lua.OnSendMessageToChatEvent;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import zombie.network.chat.ChatType;

/** Patches {@link zombie.chat.ChatManager} */
public class ChatManagerPatch extends StormClassTransformer {

    public ChatManagerPatch() {
        super("zombie.chat.ChatManager");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(SendMessageToChatAdvice.class)
                        .on(
                                ElementMatchers.named("sendMessageToChat")
                                        .and(ElementMatchers.takesArgument(0, String.class))
                                        .and(
                                                ElementMatchers.takesArgument(
                                                        1,
                                                        ElementMatchers.named(
                                                                "zombie.network.chat.ChatType")))
                                        .and(ElementMatchers.takesArgument(2, String.class))));
    }

    public static class SendMessageToChatAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean onEnter(
                @Advice.Argument(0) String author,
                @Advice.Argument(1) ChatType chatType,
                @Advice.Argument(2) String message) {
            LOGGER.debug(
                    "MESSAGE BEING SENT TO CHAT: {}:{}:{}", chatType.getValue(), author, message);

            OnSendMessageToChatEvent event =
                    new OnSendMessageToChatEvent(author, chatType, message);

            StormEventDispatcher.dispatchEvent(event);

            return event.isCancelled;
        }
    }
}
