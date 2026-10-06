package org.javacs.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.LineNumber;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.ZipFile;
import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

/**
 * Source of the classes that are not in the debug session's source roots (JDK, Spring and other
 * libraries), so that stepping into them — or stopping in them after an exception unwinds into a
 * library frame — shows code at the right line.
 *
 * <ul>
 *   <li>JDK classes: the {@code src.zip} of a local JDK with the debuggee's version.
 *   <li>Library classes: the jar under {@code ~/.m2} that holds the class. When several versions
 *       have it, the one whose line tables match the loaded class wins. Its {@code -sources.jar}
 *       is used when present; otherwise the class is decompiled with Vineflower and the JVM lines
 *       are translated to the decompiled file's lines (and back, for breakpoints set there).
 * </ul>
 *
 * Files are written under a directory whose name contains {@code jls-jar-sources}, which the
 * editor plugin opens as read-only external source. Each file keeps the package layout, so its
 * path ends with the class's {@code sourcePath} like a file in a source root.
 */
final class LibrarySources {
    private static final Logger LOG = Logger.getLogger("debug");

    /** Translation between the JVM's line numbers and a decompiled file's lines. */
    static final class LineMap {
        private final TreeMap<Integer, Integer> toFile = new TreeMap<>();
        private final TreeMap<Integer, Integer> toJvm = new TreeMap<>();

        void add(int jvmLine, int fileLine) {
            toFile.merge(jvmLine, fileLine, Math::min);
            toJvm.merge(fileLine, jvmLine, Math::min);
        }

        int fileLine(int jvmLine) {
            var exact = toFile.get(jvmLine);
            if (exact != null) return exact;
            var before = toFile.floorEntry(jvmLine);
            return before != null ? before.getValue() : jvmLine;
        }

        /** JVM line of a line of the decompiled file, or -1 when no code was generated there. */
        int jvmLine(int fileLine) {
            var exact = toJvm.get(fileLine);
            return exact != null ? exact : -1;
        }

        List<String> serialize() {
            var lines = new ArrayList<String>();
            for (var e : toFile.entrySet()) lines.add(e.getKey() + " " + e.getValue());
            return lines;
        }

        static LineMap parse(List<String> lines) {
            var map = new LineMap();
            for (var line : lines) {
                var parts = line.trim().split(" ");
                if (parts.length == 2) map.add(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            return map;
        }
    }

    /** A file to show for a class; {@code lines} is null when the file has the JVM's own lines. */
    record Resolved(Path file, LineMap lines) {
        int fileLine(int jvmLine) {
            return lines == null ? jvmLine : lines.fileLine(jvmLine);
        }
    }

    private final Path cacheRoot;
    private final String vmVersion;
    private final CompletableFuture<Map<String, List<Path>>> jarsByPackage;
    private final Map<String, Optional<Resolved>> byType = new ConcurrentHashMap<>();
    /** Line maps of the decompiled files handed out, by absolute path (breakpoints set in them). */
    private final Map<String, LineMap> byFile = new ConcurrentHashMap<>();
    private volatile Optional<Map<String, String>> jdkSources;
    private Path jdkSrcZip;

    LibrarySources(String vmVersion) {
        this.vmVersion = vmVersion == null ? "" : vmVersion;
        var cache = System.getenv("XDG_CACHE_HOME");
        var base = cache != null && !cache.isBlank() ? Path.of(cache) : Path.of(System.getProperty("user.home"), ".cache");
        this.cacheRoot = base.resolve("jls-jar-sources-debug");
        // ~1 s for ~1,500 jars: built in the background while the user gets to the first stop
        this.jarsByPackage = CompletableFuture.supplyAsync(LibrarySources::indexJars);
    }

    /** File for the code at this location, when it is outside the source roots. */
    Optional<Resolved> resolve(Location location) {
        var type = location.declaringType();
        var loader = type.classLoader();
        var key = type.name() + "@" + (loader == null ? 0 : loader.uniqueID());
        return byType.computeIfAbsent(key, __ -> {
            try {
                var relative = location.sourcePath();
                var found = isJdk(loader) ? fromJdk(relative) : fromJars(type, relative);
                if (found.isEmpty()) LOG.info("No source found for " + type.name());
                return found;
            } catch (AbsentInformationException __2) {
                return Optional.empty();
            } catch (RuntimeException | IOException e) {
                LOG.warning("Could not resolve source of " + type.name() + ": " + e);
                return Optional.empty();
            }
        });
    }

    /** The JVM line for a breakpoint at {@code line} of {@code path} (a decompiled file has its own lines). */
    int jvmLine(String path, int line) {
        var map = path == null ? null : byFile.get(path);
        if (map == null && path != null && path.startsWith(cacheRoot.toString())) {
            map = loadLineMap(Path.of(path));
            if (map != null) byFile.put(path, map);
        }
        return map == null ? line : map.jvmLine(line);
    }

    // ---------------------------------------------------------------- JDK

    private static boolean isJdk(ClassLoaderReference loader) {
        if (loader == null) return true; // bootstrap
        var name = loader.referenceType().name();
        return name.equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader")
                || name.equals("sun.misc.Launcher$ExtClassLoader");
    }

    private Optional<Resolved> fromJdk(String relative) throws IOException {
        var entries = jdkSources();
        if (entries.isEmpty()) return Optional.empty();
        var entry = entries.get().get(relative);
        if (entry == null) return Optional.empty();
        var target = cacheRoot.resolve("jdk-" + safe(vmVersion)).resolve(relative);
        if (!Files.isRegularFile(target)) {
            try (var zip = new ZipFile(jdkSrcZip.toFile())) {
                write(target, zip.getInputStream(zip.getEntry(entry)).readAllBytes());
            }
        }
        return Optional.of(new Resolved(target, null));
    }

    /** "java/util/Date.java" → entry of src.zip ("java.base/java/util/Date.java" since JDK 9). */
    private synchronized Optional<Map<String, String>> jdkSources() throws IOException {
        if (jdkSources != null) return jdkSources;
        jdkSrcZip = findSrcZip();
        if (jdkSrcZip == null) {
            LOG.warning("No src.zip found for the debuggee's JDK " + vmVersion);
            return jdkSources = Optional.empty();
        }
        LOG.info("JDK sources: " + jdkSrcZip);
        var map = new HashMap<String, String>();
        try (var zip = new ZipFile(jdkSrcZip.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (!name.endsWith(".java")) continue;
                var slash = name.indexOf('/');
                // JDK 9+: the first directory is the module ("java.base"); JDK 8 has no module directory
                var module = slash > 0 && name.substring(0, slash).contains(".");
                map.putIfAbsent(module ? name.substring(slash + 1) : name, name);
            }
        }
        return jdkSources = Optional.of(map);
    }

    /** src.zip of a JDK with the debuggee's version: the same release, else the same major version. */
    private Path findSrcZip() {
        var homes = new ArrayList<Path>();
        var javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) homes.add(Path.of(javaHome));
        for (var dir : List.of(Path.of("/usr/lib/jvm"), Path.of(System.getProperty("user.home"), ".sdkman/candidates/java"))) {
            if (!Files.isDirectory(dir)) continue;
            try (var list = Files.list(dir)) {
                list.sorted().forEach(homes::add);
            } catch (IOException ignored) {
            }
        }
        Path sameMajor = null;
        for (var home : homes) {
            var zip = home.resolve("lib/src.zip");
            if (!Files.isRegularFile(zip)) zip = home.resolve("src.zip"); // JDK 8
            if (!Files.isRegularFile(zip)) continue;
            var version = releaseVersion(home);
            if (version.equals(vmVersion)) return zip;
            if (sameMajor == null && major(version).equals(major(vmVersion))) sameMajor = zip;
        }
        return sameMajor;
    }

    private static String releaseVersion(Path home) {
        try {
            for (var line : Files.readAllLines(home.resolve("release"))) {
                if (line.startsWith("JAVA_VERSION=")) return line.substring(13).replace("\"", "").trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    private static String major(String version) {
        var parts = version.split("[._+-]");
        if (parts.length == 0) return "";
        return parts[0].equals("1") && parts.length > 1 ? parts[1] : parts[0];
    }

    // ---------------------------------------------------------------- libraries

    private static Map<String, List<Path>> indexJars() {
        var started = System.nanoTime();
        var roots = new ArrayList<Path>();
        roots.add(Path.of(System.getProperty("user.home"), ".m2", "repository"));
        // container classes (Undertow, RESTEasy...): WildFly's modules, when the server is known
        var jboss = System.getenv("JBOSS_HOME");
        if (jboss != null && !jboss.isBlank()) roots.add(Path.of(jboss, "modules"));
        var jars = new ArrayList<Path>();
        for (var root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(p -> {
                            var n = p.getFileName().toString();
                            return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
                        })
                        .forEach(jars::add);
            } catch (IOException e) {
                LOG.warning("Could not list " + root + ": " + e);
            }
        }
        var index = new ConcurrentHashMap<String, List<Path>>();
        jars.parallelStream().forEach(jar -> {
            var packages = new HashSet<String>();
            try (var zip = new ZipFile(jar.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var name = entries.nextElement().getName();
                    if (!name.endsWith(".class")) continue;
                    var slash = name.lastIndexOf('/');
                    packages.add(slash < 0 ? "" : name.substring(0, slash));
                }
            } catch (IOException ignored) {
                return; // corrupt or partially downloaded jar
            }
            for (var p : packages) index.computeIfAbsent(p, __ -> Collections.synchronizedList(new ArrayList<>())).add(jar);
        });
        LOG.info(String.format("Indexed %d jars, %d packages in %d ms", jars.size(), index.size(), (System.nanoTime() - started) / 1_000_000));
        return index;
    }

    private Optional<Resolved> fromJars(ReferenceType type, String relative) throws IOException {
        var classEntry = type.name().replace('.', '/') + ".class";
        var slash = classEntry.lastIndexOf('/');
        var candidates = jarsByPackage.join().getOrDefault(slash < 0 ? "" : classEntry.substring(0, slash), List.of());
        Path best = null;
        var bestScore = -1;
        var jvmLines = jvmLineTable(type);
        for (var jar : List.copyOf(candidates)) {
            byte[] bytes;
            try (var zip = new ZipFile(jar.toFile())) {
                var entry = zip.getEntry(classEntry);
                if (entry == null) continue;
                bytes = zip.getInputStream(entry).readAllBytes();
            } catch (IOException e) {
                continue;
            }
            var score = matchingMethods(jvmLines, bytes);
            if (score > bestScore) {
                best = jar;
                bestScore = score;
            }
        }
        if (best == null) return Optional.empty();
        LOG.info(String.format("%s: %s (%d/%d methods with the same lines)", type.name(), best, bestScore, jvmLines.size()));
        var dir = cacheRoot.resolve(safe(best.getFileName().toString().replaceAll("\\.jar$", "")) + "-" + Integer.toHexString(best.toString().hashCode()));
        var sourcesJar = best.resolveSibling(best.getFileName().toString().replaceAll("\\.jar$", "-sources.jar"));
        if (Files.isRegularFile(sourcesJar)) {
            var target = dir.resolve(relative);
            if (Files.isRegularFile(target)) return Optional.of(new Resolved(target, null));
            try (var zip = new ZipFile(sourcesJar.toFile())) {
                var entry = zip.getEntry(relative);
                if (entry != null) {
                    write(target, zip.getInputStream(entry).readAllBytes());
                    return Optional.of(new Resolved(target, null));
                }
            }
        }
        return decompile(best, relative, dir.resolve("decompiled"));
    }

    /** Line numbers of each method of the loaded class, by name + descriptor. */
    private static Map<String, Set<Integer>> jvmLineTable(ReferenceType type) {
        var result = new HashMap<String, Set<Integer>>();
        for (var method : type.methods()) {
            try {
                var lines = new HashSet<Integer>();
                for (var l : method.allLineLocations()) lines.add(l.lineNumber());
                if (!lines.isEmpty()) result.put(method.name() + method.signature(), lines);
            } catch (AbsentInformationException ignored) {
            }
        }
        return result;
    }

    /** How many methods of the class file have exactly the loaded class's line numbers. */
    private static int matchingMethods(Map<String, Set<Integer>> jvmLines, byte[] classFile) {
        try {
            var model = ClassFile.of().parse(classFile);
            var score = 0;
            for (var method : model.methods()) {
                var wanted = jvmLines.get(method.methodName().stringValue() + method.methodType().stringValue());
                if (wanted == null || method.code().isEmpty()) continue;
                var lines = new HashSet<Integer>();
                for (var element : method.code().get()) {
                    if (element instanceof LineNumber n) lines.add(n.line());
                }
                if (lines.equals(wanted)) score++;
            }
            return score;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- decompiler

    private Optional<Resolved> decompile(Path jar, String relative, Path dir) throws IOException {
        var target = dir.resolve(relative);
        var mapFile = lineMapFile(target);
        if (Files.isRegularFile(target) && Files.isRegularFile(mapFile)) {
            var map = loadLineMap(target);
            byFile.put(target.toString(), map);
            return Optional.of(new Resolved(target, map));
        }
        // the top-level class of the file and its nested/anonymous classes
        var topLevel = relative.substring(0, relative.length() - ".java".length());
        var inputs = Files.createTempDirectory("jls-debug-decompile");
        var files = new ArrayList<File>();
        try (var zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                var name = entry.getName();
                if (!name.endsWith(".class") || !(name.equals(topLevel + ".class") || name.startsWith(topLevel + "$"))) continue;
                var out = inputs.resolve(name);
                write(out, zip.getInputStream(entry).readAllBytes());
                files.add(out.toFile());
            }
        }
        if (files.isEmpty()) return Optional.empty();
        var result = new String[1];
        var map = new LineMap();
        var wanted = topLevel;
        try {
            Decompiler.builder()
                    .inputs(files.toArray(new File[0]))
                    .libraries(jar.toFile())
                    .output(new IResultSaver() {
                        @Override public void saveFolder(String path) {}
                        @Override public void copyFile(String source, String path, String entryName) {}
                        @Override public void createArchive(String path, String archiveName, java.util.jar.Manifest manifest) {}
                        @Override public void saveDirEntry(String path, String archiveName, String entryName) {}
                        @Override public void copyEntry(String source, String path, String archiveName, String entry) {}
                        @Override public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName, String content) {}
                        @Override public void closeArchive(String path, String archiveName) {}

                        @Override
                        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
                            if (!wanted.equals(qualifiedName) || content == null) return;
                            result[0] = content;
                            // pairs (JVM line, line of the decompiled text), 1-based
                            for (var i = 0; mapping != null && i + 1 < mapping.length; i += 2) map.add(mapping[i], mapping[i + 1]);
                        }
                    })
                    .logger(IFernflowerLogger.NO_OP)
                    .option(IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1")
                    .option(IFernflowerPreferences.REMOVE_SYNTHETIC, "1")
                    .option(IFernflowerPreferences.REMOVE_BRIDGE, "1")
                    .option(IFernflowerPreferences.DECOMPILER_COMMENTS, "0")
                    .option(IFernflowerPreferences.SOURCE_FILE_COMMENTS, "0")
                    .option(IFernflowerPreferences.INDENT_STRING, "    ")
                    .build()
                    .decompile();
        } finally {
            try (var walk = Files.walk(inputs)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (result[0] == null) return Optional.empty();
        write(target, result[0].getBytes(StandardCharsets.UTF_8));
        Files.write(mapFile, map.serialize());
        byFile.put(target.toString(), map);
        return Optional.of(new Resolved(target, map));
    }

    private static Path lineMapFile(Path decompiled) {
        return decompiled.resolveSibling("." + decompiled.getFileName() + ".lines");
    }

    private static LineMap loadLineMap(Path decompiled) {
        var file = lineMapFile(decompiled);
        if (!Files.isRegularFile(file)) return null;
        try {
            return LineMap.parse(Files.readAllLines(file));
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        var temp = target.resolveSibling("." + target.getFileName() + ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
