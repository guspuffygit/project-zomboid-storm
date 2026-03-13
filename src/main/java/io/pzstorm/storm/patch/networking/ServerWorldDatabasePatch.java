package io.pzstorm.storm.patch.networking;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import io.pzstorm.storm.core.StormClassTransformer;
import io.pzstorm.storm.event.core.StormEventDispatcher;
import io.pzstorm.storm.event.lua.OnAuthAttemptEvent;
import java.lang.reflect.Field;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;

/**
 * Patches {@link zombie.network.ServerWorldDatabase} to log and dispatch authentication attempt
 * events.
 */
public class ServerWorldDatabasePatch extends StormClassTransformer {

    public ServerWorldDatabasePatch() {
        super("zombie.network.ServerWorldDatabase");
    }

    @Override
    public DynamicType.Builder<Object> dynamicType(
            ClassFileLocator locator, TypePool typePool, DynamicType.Builder<Object> builder) {
        return builder.visit(
                Advice.to(AuthClientAdvice.class)
                        .on(
                                ElementMatchers.named("authClient")
                                        .and(ElementMatchers.takesArguments(5))));
    }

    public static boolean getAuthorized(Object result) {
        try {
            Field f = result.getClass().getDeclaredField("authorized");
            f.setAccessible(true);
            return f.getBoolean(result);
        } catch (Exception e) {
            LOGGER.debug("Failed to read LogonResult.authorized", e);
            return false;
        }
    }

    public static String getStringField(Object result, String fieldName) {
        try {
            Field f = result.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return (String) f.get(result);
        } catch (Exception e) {
            LOGGER.debug("Failed to read LogonResult.{}", fieldName, e);
            return null;
        }
    }

    public static class AuthClientAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void afterAuthClient(
                @Advice.Argument(0) String user,
                @Advice.Argument(2) String ip,
                @Advice.Argument(3) long steamID,
                @Advice.Argument(4) int authType,
                @Advice.Return Object result) {

            boolean authorized = ServerWorldDatabasePatch.getAuthorized(result);
            String dcReason = ServerWorldDatabasePatch.getStringField(result, "dcReason");
            String bannedReason = ServerWorldDatabasePatch.getStringField(result, "bannedReason");

            if (authorized) {
                LOGGER.info(
                        "Auth success: username=\"{}\" ip={} steamId={} authType={}",
                        user,
                        ip,
                        steamID,
                        authType);
            } else {
                LOGGER.warn(
                        "Auth failed: username=\"{}\" ip={} steamId={} authType={}"
                                + " dcReason=\"{}\" bannedReason=\"{}\"",
                        user,
                        ip,
                        steamID,
                        authType,
                        dcReason,
                        bannedReason);
            }

            OnAuthAttemptEvent event =
                    new OnAuthAttemptEvent(
                            user, ip, steamID, authType, authorized, dcReason, bannedReason);
            StormEventDispatcher.dispatchEvent(event);
        }
    }
}
