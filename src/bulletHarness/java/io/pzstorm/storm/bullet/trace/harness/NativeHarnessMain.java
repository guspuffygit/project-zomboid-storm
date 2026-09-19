package io.pzstorm.storm.bullet.trace.harness;

import io.pzstorm.storm.bullet.trace.TraceTool;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TraceTool} with the native library as backend. Usage: {@code NativeHarnessMain --lib
 * /path/libPZBulletNoOpenGL64.so <TraceTool command...>}.
 */
public final class NativeHarnessMain {

    private NativeHarnessMain() {}

    public static void main(String[] args) {
        List<String> rest = new ArrayList<>();
        String lib = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lib") && i + 1 < args.length) {
                lib = args[++i];
            } else {
                rest.add(args[i]);
            }
        }
        if (lib == null) {
            System.err.println("--lib <path to libPZBullet*.so> is required");
            System.exit(2);
        }
        Path libPath = Path.of(lib);
        int status =
                TraceTool.run(
                        rest.toArray(new String[0]), () -> NativeBackend.open(libPath), System.out);
        System.out.flush();
        // skip library destructors / shutdown ordering surprises: exit straight away
        Runtime.getRuntime().halt(status);
    }
}
