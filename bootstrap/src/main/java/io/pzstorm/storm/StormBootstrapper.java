package io.pzstorm.storm;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * The static entry point. Users install this JAR once.
 * It dynamically loads the real Storm engine from the Steam Workshop path.
 */
public class StormBootstrapper {

    private static final String LOCAL_DEV_PATH = "storm/Contents/mods/storm/42/lib";

    private static final String CORE_LAUNCHER_CLASS = "io.pzstorm.storm.core.StormLauncher";

    private static final Boolean isServer = Boolean.getBoolean("storm.server");

    /**
     * Keep in sync with io.pzstorm.launcher.GameLaunch#HANDOFF_PROPERTY. The launcher sets it to
     * false on the game JVM it spawns; players set it to false to keep the old direct-boot behavior.
     */
    private static final String LAUNCHER_HANDOFF_PROPERTY = "storm.launcher.handoff";

    /** Stored from premain so Storm core can retrieve it via reflection. */
    public static volatile Instrumentation instrumentation;

    private static Path getJarDirectory() {
        try {
            URI jarUri = StormBootstrapper.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(jarUri).getParent();
        } catch (Exception e) {
            throw new RuntimeException("Failed to determine JAR location", e);
        }
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[StormAgent] Agent initializing...");
        instrumentation = inst;

        if (!isServer && handOffToLauncher()) {
            System.exit(0);
        }

        String targetMainClass = isServer
                ? "zombie.network.GameServer"
                : "zombie.gameStates.MainScreenState";
        System.out.println("[StormAgent] Targeting class for replacement: " + targetMainClass);

        HijackTransformer transformer = new HijackTransformer(targetMainClass, inst);
        inst.addTransformer(transformer);
    }

    /**
     * Steam's Launch Options paste is the only hook players have, so the agent line that used to
     * boot the game with Storm boots the Storm Launcher instead: spawn the launcher jar shipped
     * next to this bootstrap in the workshop item, then exit before any game code runs. The
     * launcher starts the real game itself with -Dstorm.launcher.handoff=false, so that JVM boots
     * straight into Storm. Fails soft: any problem here means "no launcher", never "no game".
     */
    private static boolean handOffToLauncher() {
        if ("false".equalsIgnoreCase(System.getProperty(LAUNCHER_HANDOFF_PROPERTY))) {
            return false;
        }
        try {
            Path launcherJar = getJarDirectory().resolve("../launcher/storm-launcher.jar").normalize();
            if (!Files.isRegularFile(launcherJar)) {
                System.out.println("[StormAgent] No launcher jar at " + launcherJar
                        + " — starting the game directly.");
                return false;
            }
            List<String> command = new ArrayList<>();
            command.add(launcherJvm());
            command.add("-jar");
            command.add(launcherJar.toString());
            // the launcher must not touch Steam while this JVM still maps agentlib.dll
            command.add("--parent-pid=" + ProcessHandle.current().pid());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(launcherJar.getParent().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(handOffLog());
            // Steam injects its overlay into every process it launches via DYLD_INSERT_LIBRARIES
            // (macOS); inherited into the launcher's Swing JVM, the overlay's Metal hook crashes
            // it inside Java2D's MTLLayer blit. Keep the overlay out of the launcher but stash the
            // value so the launcher can restore it for the game JVM, where the overlay belongs.
            // Keep the stash name in sync with io.pzstorm.launcher.GameLaunch.
            String overlay = pb.environment().remove("DYLD_INSERT_LIBRARIES");
            if (overlay != null && !overlay.isEmpty()) {
                pb.environment().put("STORM_GAME_DYLD_INSERT_LIBRARIES", overlay);
            }
            pb.start();
            System.out.println("[StormAgent] Handed off to Storm Launcher: " + launcherJar);
            return true;
        } catch (Exception e) {
            System.err.println("[StormAgent] Launcher hand-off failed — starting the game directly.");
            e.printStackTrace();
            return false;
        }
    }

    /** javaw on Windows so the launcher window never owns a console. */
    private static String launcherJvm() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        for (String exe : new String[] {"javaw.exe", "java.exe", "java"}) {
            Path candidate = bin.resolve(exe);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "java";
    }

    private static File handOffLog() throws IOException {
        Path logDir = Paths.get(System.getProperty("user.home"), "Zomboid", "storm", "launcher", "logs");
        Files.createDirectories(logDir);
        return logDir.resolve("handoff.log").toFile();
    }

    static class HijackTransformer implements ClassFileTransformer {
        private final String targetClassName;
        private final Instrumentation inst;

        public HijackTransformer(String targetClassName, Instrumentation inst) {
            this.targetClassName = targetClassName.replace('.', '/');
            this.inst = inst;
        }

        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {

            if (!className.equals(targetClassName)) {
                return null;
            }

            try {
                System.out.println("[StormAgent] Intercepting Main Class: " + className);

                ClassPool cp = ClassPool.getDefault();
                cp.appendClassPath(new LoaderClassPath(loader));
                CtClass cc = cp.makeClass(new ByteArrayInputStream(classfileBuffer));
                CtMethod m = cc.getDeclaredMethod("main");
                m.setBody("{ io.pzstorm.storm.StormBootstrapper.bootstrap($1); }");

                byte[] byteCode = cc.toBytecode();
                cc.detach();

                inst.removeTransformer(this);
                System.out.println("[StormAgent] Successfully injected bootstrap logic.");

                return byteCode;
            } catch (Exception e) {
                System.err.println("[StormAgent] Failed to transform main class.");
                e.printStackTrace();
                System.exit(1);
                return null;
            }
        }
    }

    public static void main(String[] args) {
        bootstrap(args);
    }

    public static void bootstrap(String[] args) {
        try {
            System.out.println("[StormBootstrapper] Initializing... v1");

            Path libraryDir;
            if ("local".equals(System.getProperty("stormType"))) {
                Path workshopDir = Paths.get(System.getProperty("user.home"), "Zomboid", "Workshop");
                libraryDir = workshopDir.resolve(LOCAL_DEV_PATH).normalize();
            } else {
                libraryDir = getJarDirectory().resolve("../42/lib").normalize();
            }

            System.out.println("[StormBootstrapper] Searching for libraries in: " + libraryDir);

            if (!Files.exists(libraryDir) || !Files.isDirectory(libraryDir)) {
                System.err.println("[StormBootstrapper] ERROR: Workshop directory not found!");
                System.err.println("Expected: " + libraryDir.toAbsolutePath());
                System.exit(1);
            }

            // 2. Discover all JARs in the media folder
            List<Path> libraryJars = new ArrayList<>();
            try (Stream<Path> files = Files.list(libraryDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> {
                            System.out.println("[StormBootstrapper] Found library: " + p.getFileName());
                            libraryJars.add(p);
                        });
            }

            if (libraryJars.isEmpty()) {
                throw new RuntimeException("No JAR files found in Workshop directory.");
            }

            // Storm core self-updates from the CDN like the launcher does: when a newer
            // storm.jar is published, load the SHA-verified staged copy instead of the item's.
            Path stormJar = null;
            for (Path jar : libraryJars) {
                String fileName = jar.getFileName().toString().toLowerCase();
                if (fileName.startsWith("storm-") && fileName.endsWith(".jar")) {
                    stormJar = jar;
                }
            }
            if (stormJar != null) {
                Path resolved = StormCoreUpdate.resolve(stormJar);
                if (!resolved.equals(stormJar)) {
                    libraryJars.set(libraryJars.indexOf(stormJar), resolved);
                    stormJar = resolved;
                }
            }

            // Append logback jars to the system classloader so SLF4J's ServiceLoader
            // can discover LogbackServiceProvider. Without this, logback is only visible
            // in the child workshopLoader and SLF4J (loaded by the system classloader
            // from projectzomboid.jar) falls back to NOP.
            List<URL> libraryUrls = new ArrayList<>();
            for (Path jar : libraryJars) {
                libraryUrls.add(jar.toUri().toURL());
                if (jar.getFileName().toString().toLowerCase().contains("logback")) {
                    System.out.println("[StormBootstrapper] Appending to system classloader: " + jar.getFileName());
                    instrumentation.appendToSystemClassLoaderSearch(new JarFile(jar.toFile()));
                }
            }

            // Point logback to our config inside storm.jar
            if (stormJar != null) {
                String configUrl = "jar:" + stormJar.toUri().toURL() + "!/logback.xml";
                System.setProperty("logback.configurationFile", configUrl);
                System.out.println("[StormBootstrapper] Set logback.configurationFile=" + configUrl);
            }

            URLClassLoader workshopLoader = new URLClassLoader(
                    libraryUrls.toArray(new URL[0]),
                    ClassLoader.getSystemClassLoader()
            );

            Class<?> launcherClass = Class.forName(CORE_LAUNCHER_CLASS, true, workshopLoader);
            Method mainMethod = launcherClass.getMethod("main", String[].class);

            System.out.println("[StormBootstrapper] Launching Storm Core...");

            Thread.currentThread().setContextClassLoader(workshopLoader);

            mainMethod.invoke(null, (Object) args);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[StormBootstrapper] CRITICAL FAILURE: Could not launch Storm Core.");
            System.exit(1);
        }
    }
}
