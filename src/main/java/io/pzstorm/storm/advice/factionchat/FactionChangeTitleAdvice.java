package io.pzstorm.storm.advice.factionchat;

import net.bytebuddy.asm.Advice;

/** Captures the faction name before a rename and re-keys its chat after. */
public class FactionChangeTitleAdvice {

    @Advice.OnMethodEnter
    public static String onEnter(@Advice.This Object self) {
        return FactionChatRekey.nameOf(self);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.This Object self, @Advice.Enter String oldName) {
        FactionChatRekey.afterRename(self, oldName);
    }
}
