package io.pzstorm.storm.patch.fixes;

import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Re-keys the faction chat when {@code FactionChangeTitlePacket.processServer} renames a faction.
 * See {@link io.pzstorm.storm.advice.factionchat.FactionChatRekey}. Server-only.
 */
public class FactionChangeTitleChatPatch extends StormClassTransformer {

    private static final String ADVICE =
            "io.pzstorm.storm.advice.factionchat.FactionChangeTitleAdvice";

    public FactionChangeTitleChatPatch() {
        super("zombie.network.packets.faction.FactionChangeTitlePacket");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(typePool.describe(ADVICE).resolve(), locator)
                        .on(ElementMatchers.named("processServer")));
    }
}
