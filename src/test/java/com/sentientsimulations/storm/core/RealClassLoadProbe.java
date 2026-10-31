package com.sentientsimulations.storm.core;

import io.pzstorm.storm.core.StormBootstrap;
import io.pzstorm.storm.core.StormClassLoader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Child JVM of {@link RealClassLoadIntegrationTest}. Loads every transformer target through {@link
 * StormBootstrap#CLASS_LOADER}, the way the game does, then reports each target that failed to load
 * or that loaded without every transformer applied. The first argument names a target that must be
 * registered, so a run that found nothing to check cannot pass.
 */
public final class RealClassLoadProbe {

    static final String RESULT_PREFIX = "REALLOAD ";

    private RealClassLoadProbe() {}

    public static void main(String[] args) throws Exception {
        ClassLoader loader = StormBootstrap.CLASS_LOADER;
        Class<?> transformers =
                Class.forName("io.pzstorm.storm.core.StormClassTransformers", true, loader);
        Field registered = transformers.getDeclaredField("TRANSFORMERS");
        registered.setAccessible(true);

        Set<String> targets = new TreeSet<>();
        for (Object name : ((Map<?, ?>) registered.get(null)).keySet()) {
            String target = (String) name;
            // Agent-transformed, never defined by StormClassLoader.
            if (!StormClassLoader.isBlacklistedClass(target)
                    && !StormClassLoader.isJdkRuntimeClass(target)) {
                targets.add(target);
            }
        }

        List<String> failures = new ArrayList<>();
        if (!targets.contains(args[0])) {
            failures.add(args[0] + " is not a registered target");
        }
        for (String target : targets) {
            try {
                Class.forName(target, false, loader);
            } catch (Throwable t) {
                failures.add(target + " failed to load: " + rootCause(t));
            }
        }

        Set<?> transformed = (Set<?>) transformers.getMethod("getTransformedClasses").invoke(null);
        for (String target : targets) {
            if (!transformed.contains(target)) {
                failures.add(target + " is not fully transformed");
            }
        }

        for (String failure : failures) {
            System.out.println(RESULT_PREFIX + "FAILED " + failure);
        }
        System.out.println(RESULT_PREFIX + "targets=" + targets.size());
        System.out.flush();
        // Bootstrap leaves non-daemon threads behind.
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private static String rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }
}
