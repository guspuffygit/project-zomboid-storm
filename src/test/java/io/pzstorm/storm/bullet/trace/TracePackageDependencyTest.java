package io.pzstorm.storm.bullet.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pzstorm.storm.UnitTest;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.bytebuddy.jar.asm.ClassReader;
import org.junit.jupiter.api.Test;

/**
 * The trace package (and its scenario driver) runs in the native-replay container with only the
 * harness jar on the classpath, so it may reference the JDK and itself, nothing else (the Java port
 * is reached by reflection).
 */
class TracePackageDependencyTest implements UnitTest {

    @Test
    void referencesOnlyTheJdkAndItself() throws IOException, URISyntaxException {
        Path root =
                Path.of(
                        BulletApi.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        Path pkg = root.resolve("io/pzstorm/storm/bullet/trace");
        assertTrue(Files.isDirectory(pkg), "compiled classes directory expected at " + pkg);
        Map<String, String> offenders = new TreeMap<>();
        int classes = 0;
        try (Stream<Path> files = Files.walk(pkg)) {
            for (Path f :
                    (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                classes++;
                ClassReader cr = new ClassReader(Files.readAllBytes(f));
                char[] buf = new char[cr.getMaxStringLength()];
                for (int i = 1; i < cr.getItemCount(); i++) {
                    int off = cr.getItem(i);
                    if (off == 0 || cr.readByte(off - 1) != 7) { // CONSTANT_Class
                        continue;
                    }
                    String name = cr.readUTF8(off, buf);
                    String type = name.replaceFirst("^\\[+L?", "").replaceFirst(";$", "");
                    if (type.length() <= 1 && name.startsWith("[")) {
                        continue; // primitive array
                    }
                    if (!type.startsWith("java/")
                            && !type.startsWith("io/pzstorm/storm/bullet/trace/")) {
                        offenders.put(root.relativize(f) + " -> " + type, type);
                    }
                }
            }
        }
        assertTrue(classes > 20, "found " + classes + " classes");
        assertEquals(Map.of(), offenders);
    }
}
