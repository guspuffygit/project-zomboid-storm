package io.pzstorm.storm;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.LoaderClassPath;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The static entry point. Users install this JAR once.
 * It dynamically loads the real Storm engine from the Steam Workshop path.
 */
public class StormBootstrapper {

    private static final String WORKSHOP_PATH = "../../workshop/content/108600/3670772371/mods/storm/42/lib";
    private static final String LOCAL_DEV_PATH = "storm/Contents/mods/storm/42/lib";
    private static final String STORM_BOOTSTRAP_PAGE = "https://guspuffy.s3.us-east-1.amazonaws.com/storm-bootstrap-message.html";

    private static final String CORE_LAUNCHER_CLASS = "io.pzstorm.storm.core.StormLauncher";

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[StormAgent] Agent initializing...");

        boolean isServer = Boolean.getBoolean("storm.server");
        String targetMainClass = isServer
                ? "zombie.network.GameServer"
                : "zombie.gameStates.MainScreenState";
        System.out.println("[StormAgent] Targeting class for replacement: " + targetMainClass);

        HijackTransformer transformer = new HijackTransformer(targetMainClass, inst);
        inst.addTransformer(transformer);
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

            checkForStormBootstrapMessage();

            Path libraryDir;
            if ("local".equals(System.getProperty("stormType"))) {
                Path workshopDir = Paths.get(System.getProperty("user.home"), "Zomboid", "Workshop");
                libraryDir = workshopDir.resolve(LOCAL_DEV_PATH).normalize();
            } else {
                Path gameRoot = Paths.get(System.getProperty("user.dir"));
                libraryDir = gameRoot.resolve(WORKSHOP_PATH).normalize();
            }

            System.out.println("[StormBootstrapper] Searching for libraries in: " + libraryDir);

            if (!Files.exists(libraryDir) || !Files.isDirectory(libraryDir)) {
                System.err.println("[StormBootstrapper] ERROR: Workshop directory not found!");
                System.err.println("Expected: " + libraryDir.toAbsolutePath());
                System.exit(1);
            }

            // 2. Discover all JARs in the media folder
            List<URL> libraryUrls = new ArrayList<>();
            try (Stream<Path> files = Files.list(libraryDir)) {
                files.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(p -> {
                            try {
                                System.out.println("[StormBootstrapper] Found library: " + p.getFileName());
                                libraryUrls.add(p.toUri().toURL());
                            } catch (Exception e) {
                                System.err.println("Skipping invalid jar: " + p);
                            }
                        });
            }

            if (libraryUrls.isEmpty()) {
                throw new RuntimeException("No JAR files found in Workshop directory.");
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

    private static void checkForStormBootstrapMessage() {
        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(STORM_BOOTSTRAP_PAGE))
                .GET()
                .build();

        String response = "";
        try {
            HttpResponse<String> result = client.send(request, HttpResponse.BodyHandlers.ofString());
            response = result.body().trim();
        } catch (Exception e) {
            System.err.println("Unable to request storm bootstrap message");
            e.printStackTrace();
        }

        if (!response.isEmpty()) {
            String os = System.getProperty("os.name").toLowerCase();

            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(response));
                    return;
                }
            } catch (Exception e) {
                System.err.println("Standard Desktop API failed: " + e.getMessage());
            }

            Runtime rt = Runtime.getRuntime();
            try {
                if (os.contains("win")) {
                    rt.exec("rundll32 url.dll,FileProtocolHandler " + STORM_BOOTSTRAP_PAGE);
                } else if (os.contains("mac")) {
                    rt.exec("open " + STORM_BOOTSTRAP_PAGE);
                } else if (os.contains("nix") || os.contains("nux")) {
                    rt.exec("xdg-open " + STORM_BOOTSTRAP_PAGE);
                } else {
                    System.out.println("Unsupported operating system.");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
