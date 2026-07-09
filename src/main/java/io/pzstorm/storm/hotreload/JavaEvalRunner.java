package io.pzstorm.storm.hotreload;

import static io.pzstorm.storm.logging.StormLogger.LOGGER;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Defines a supplied {@code EvalScript} class from raw bytecode and invokes its {@code public
 * static Object run()} method, returning the result rendered as a string. Backs the {@code POST
 * /eval} endpoint of {@link HotReloadEndpoints}.
 *
 * <p>The caller ships the compiled {@code .class} bytes in the request body. A one-shot {@link
 * ClassLoader} parented to Storm's own loader is created per call so each invocation sees exactly
 * the bytecode it posted — no shared directory, no stale-class window, no configuration.
 */
public final class JavaEvalRunner {

    private static final String CLASS_NAME = "EvalScript";

    private JavaEvalRunner() {}

    public static String run(byte[] classBytes) {
        if (classBytes == null || classBytes.length == 0) {
            return "ERROR: empty request body (expected raw " + CLASS_NAME + ".class bytes)";
        }
        if (!hasClassFileMagic(classBytes)) {
            return "ERROR: request body is not a valid Java class file (missing 0xCAFEBABE magic)";
        }
        try {
            EvalClassLoader loader = new EvalClassLoader(JavaEvalRunner.class.getClassLoader());
            Class<?> cls = loader.defineEvalClass(CLASS_NAME, classBytes);
            Method method = cls.getMethod("run");
            Object result = method.invoke(null);
            LOGGER.debug("EvalScript returned: {}", result);
            return String.valueOf(result);
        } catch (InvocationTargetException ite) {
            return errorString(ite.getCause() != null ? ite.getCause() : ite);
        } catch (Throwable t) {
            return errorString(t);
        }
    }

    private static boolean hasClassFileMagic(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 0xCA
                && (bytes[1] & 0xFF) == 0xFE
                && (bytes[2] & 0xFF) == 0xBA
                && (bytes[3] & 0xFF) == 0xBE;
    }

    private static String errorString(Throwable t) {
        LOGGER.error("EvalScript execution failed", t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return "ERROR:\n" + sw;
    }

    private static final class EvalClassLoader extends ClassLoader {
        EvalClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> defineEvalClass(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
