package io.pzstorm.storm.hotreload;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.annotations.Nullable;

/**
 * Loads a freshly compiled {@code EvalScript} class and invokes its {@code public static Object
 * run()} method, returning the result rendered as a string. Backs the {@code GET /eval} endpoint of
 * {@link HotReloadEndpoints}.
 *
 * <p>The class directory is taken from {@code -Dstorm.hotreload.eval.classes}; {@code EvalScript}
 * must live in the default package there. A new {@link URLClassLoader} (parented to this runner's
 * loader, i.e. the Storm class loader) is created per call and closed afterward, so each invocation
 * sees the latest recompiled bytecode with no stale state.
 *
 * <p>If {@code -Dstorm.hotreload.eval.source} points at the directory holding {@code
 * EvalScript.java}, a staleness guard fails fast when the source is newer than the compiled class.
 * The guard is skipped when the property is unset.
 */
public final class JavaEvalRunner {

    public static final String CLASSES_DIR_PROPERTY = "storm.hotreload.eval.classes";
    public static final String SOURCE_DIR_PROPERTY = "storm.hotreload.eval.source";

    private static final String CLASS_NAME = "EvalScript";
    private static final String SOURCE_FILE_NAME = CLASS_NAME + ".java";
    private static final String CLASS_FILE_NAME = CLASS_NAME + ".class";

    private JavaEvalRunner() {}

    public static String run() {
        String classesDirProp = System.getProperty(CLASSES_DIR_PROPERTY);
        if (classesDirProp == null || classesDirProp.isBlank()) {
            return "ERROR: -D" + CLASSES_DIR_PROPERTY + " not set";
        }
        Path classDir = Path.of(classesDirProp);
        if (!Files.isDirectory(classDir)) {
            return "ERROR: eval classes dir not found at "
                    + classDir
                    + "\nCompile EvalScript.java into that directory first.";
        }
        Path classFile = classDir.resolve(CLASS_FILE_NAME);
        Path sourceFile = evalSourceFile();
        if (sourceFile != null) {
            String stale = checkStale(sourceFile, classFile);
            if (stale != null) {
                return stale;
            }
        }
        try {
            URL[] urls = {classDir.toUri().toURL()};
            try (URLClassLoader loader =
                    new URLClassLoader(urls, JavaEvalRunner.class.getClassLoader())) {
                Class<?> cls = Class.forName(CLASS_NAME, true, loader);
                Method method = cls.getMethod("run");
                Object result = method.invoke(null);
                LOGGER.debug("EvalScript returned: {}", result);
                return String.valueOf(result);
            }
        } catch (InvocationTargetException ite) {
            return errorString(ite.getCause() != null ? ite.getCause() : ite);
        } catch (Throwable t) {
            return errorString(t);
        }
    }

    private static @Nullable Path evalSourceFile() {
        String sourceDirProp = System.getProperty(SOURCE_DIR_PROPERTY);
        if (sourceDirProp == null || sourceDirProp.isBlank()) {
            return null;
        }
        return Path.of(sourceDirProp).resolve(SOURCE_FILE_NAME);
    }

    private static @Nullable String checkStale(Path sourceFile, Path classFile) {
        try {
            if (!Files.isRegularFile(sourceFile) || !Files.isRegularFile(classFile)) {
                return null;
            }
            long srcMs = Files.getLastModifiedTime(sourceFile).toMillis();
            long clsMs = Files.getLastModifiedTime(classFile).toMillis();
            if (srcMs > clsMs) {
                return "ERROR: stale class — "
                        + sourceFile.getFileName()
                        + " is newer than "
                        + classFile.getFileName()
                        + " (src="
                        + srcMs
                        + ", cls="
                        + clsMs
                        + ").\nRecompile EvalScript.java and check it succeeded.";
            }
            return null;
        } catch (Throwable t) {
            LOGGER.warn("Stale check failed", t);
            return null;
        }
    }

    private static String errorString(Throwable t) {
        LOGGER.error("EvalScript execution failed", t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return "ERROR:\n" + sw;
    }
}
