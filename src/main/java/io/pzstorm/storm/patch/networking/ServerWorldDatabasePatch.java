package io.pzstorm.storm.patch.networking;

import io.pzstorm.storm.advice.serverworlddatabase.BanIpAdvice;
import io.pzstorm.storm.advice.serverworlddatabase.BanSteamIDAdvice;
import io.pzstorm.storm.advice.serverworlddatabase.BanUserAdvice;
import io.pzstorm.storm.core.StormClassTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@link zombie.network.ServerWorldDatabase} to dispatch events after each of the ban
 * methods ({@code banUser}, {@code banIp}, {@code banSteamID}) completes.
 */
public class ServerWorldDatabasePatch extends StormClassTransformer {

    public ServerWorldDatabasePatch() {
        super("zombie.network.ServerWorldDatabase");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                        Advice.to(BanUserAdvice.class)
                                .on(
                                        ElementMatchers.named("banUser")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class, boolean.class))))
                .visit(
                        Advice.to(BanIpAdvice.class)
                                .on(
                                        ElementMatchers.named("banIp")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class,
                                                                String.class,
                                                                String.class,
                                                                boolean.class))))
                .visit(
                        Advice.to(BanSteamIDAdvice.class)
                                .on(
                                        ElementMatchers.named("banSteamID")
                                                .and(
                                                        ElementMatchers.takesArguments(
                                                                String.class,
                                                                String.class,
                                                                boolean.class))));
    }
}
