package io.pzstorm.launcher;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * The mac depot ships the game as an app bundle with no ProjectZomboid64.json: vanilla boots
 * through the bundle's JavaAppLauncher, which reads its launch config from {@code
 * Contents/Info.plist} (JVMMainClassName, JVMOptions, every jar in {@code Contents/Java} as the
 * classpath) and runs the JRE bundled in {@code Contents/PlugIns/jre-<arch>}. This mirrors that,
 * synthesizing the same inputs {@link PzGameJson} provides on the other platforms.
 */
final class MacAppBundle {

    private MacAppBundle() {}

    /** The bundle's Contents dir when gameDir is an app-bundle payload dir, else null. */
    static Path contentsDir(Path gameDir) {
        Path contents = gameDir == null ? null : gameDir.toAbsolutePath().normalize().getParent();
        if (contents != null && Files.isRegularFile(contents.resolve("Info.plist"))) {
            return contents;
        }
        return null;
    }

    /** Launch config synthesized from Info.plist, or null when gameDir is not an app bundle. */
    static PzGameJson gameJson(Path gameDir) throws IOException {
        Path contents = contentsDir(gameDir);
        if (contents == null) {
            return null;
        }
        Path plist = contents.resolve("Info.plist");
        Map<String, List<String>> dict = parsePlistStrings(plist);
        List<String> mainClass = dict.get("JVMMainClassName");
        if (mainClass == null || mainClass.isEmpty() || mainClass.get(0).isEmpty()) {
            throw new IOException(plist + " has no JVMMainClassName");
        }
        List<String> classpath = jarsIn(gameDir);
        if (classpath.isEmpty()) {
            throw new IOException("no jars found in " + gameDir);
        }
        List<String> vmArgs = dict.getOrDefault("JVMOptions", Collections.emptyList());
        return new PzGameJson(
                mainClass.get(0).replace('/', '.'), classpath, vmArgs, Collections.emptyMap());
    }

    /** The bundled JRE's java binary, preferring the running arch, or null when absent. */
    static Path bundledJvm(Path gameDir) {
        Path contents = contentsDir(gameDir);
        if (contents == null) {
            return null;
        }
        boolean arm = System.getProperty("os.arch", "").toLowerCase().contains("aarch64");
        String[] jres =
                arm
                        ? new String[] {"jre-aarch64", "jre-x86_64"}
                        : new String[] {"jre-x86_64", "jre-aarch64"};
        for (String jre : jres) {
            Path java =
                    contents.resolve(Paths.get("PlugIns", jre, "Contents", "Home", "bin", "java"));
            if (Files.isRegularFile(java)) {
                return java;
            }
        }
        return null;
    }

    /**
     * The top-level plist dict, keeping string values only (a lone {@code <string>} becomes a
     * singleton list, an {@code <array>} its string members) — the JVM keys are all strings.
     */
    private static Map<String, List<String>> parsePlistStrings(Path file) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // the plist declares apple's DTD; never resolve it over the network
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            doc = factory.newDocumentBuilder().parse(file.toFile());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not parse " + file + ": " + e.getMessage(), e);
        }
        Map<String, List<String>> dict = new LinkedHashMap<>();
        Element dictEl = firstChildElement(doc.getDocumentElement(), "dict");
        if (dictEl == null) {
            return dict;
        }
        String key = null;
        for (Node node = dictEl.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) {
                continue;
            }
            Element el = (Element) node;
            if (el.getTagName().equals("key")) {
                key = el.getTextContent().trim();
                continue;
            }
            if (key != null) {
                dict.put(key, stringValues(el));
                key = null;
            }
        }
        return dict;
    }

    private static List<String> stringValues(Element value) {
        List<String> strings = new ArrayList<>();
        if (value.getTagName().equals("string")) {
            strings.add(value.getTextContent());
        } else if (value.getTagName().equals("array")) {
            for (Node node = value.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element && ((Element) node).getTagName().equals("string")) {
                    strings.add(node.getTextContent());
                }
            }
        }
        return strings;
    }

    private static Element firstChildElement(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && ((Element) node).getTagName().equals(tag)) {
                return (Element) node;
            }
        }
        return null;
    }

    /** Classpath entries relative to gameDir, which is also the launch working directory. */
    private static List<String> jarsIn(Path gameDir) throws IOException {
        List<String> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(gameDir, "*.jar")) {
            for (Path jar : stream) {
                jars.add(jar.getFileName().toString());
            }
        }
        Collections.sort(jars);
        return jars;
    }
}
