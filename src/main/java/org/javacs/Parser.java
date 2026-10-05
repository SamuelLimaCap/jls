package org.javacs;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.*;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class Parser {
    private static final JavaCompiler COMPILER = ServiceLoader.load(JavaCompiler.class).iterator().next();
    /**
     * One file manager per thread: javac file managers are not thread-safe, and the workspace index
     * parses on several threads at once (WorkspaceTypeIndex.fromSources).
     */
    private static final ThreadLocal<SourceFileManager> FILE_MANAGER = ThreadLocal.withInitial(Parser::parseOnlyFileManager);

    /**
     * Parsing needs no classpath. Left unset, javac falls back to java.class.path (the server's own
     * jars) and every new task re-reads their manifests and builds a plugin class loader over them,
     * which cost about a quarter of the workspace index build.
     */
    private static SourceFileManager parseOnlyFileManager() {
        var files = new SourceFileManager();
        try {
            files.setLocation(javax.tools.StandardLocation.CLASS_PATH, List.of());
            files.setLocation(javax.tools.StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return files;
    }

    /** Create a task that compiles a single file */
    private static JavacTask singleFileTask(
            JavaFileObject file, DiagnosticCollector<JavaFileObject> diagnostics) {
        return (JavacTask)
                COMPILER.getTask(null, FILE_MANAGER.get(), diagnostics, List.of("-proc:none"), List.of(), List.of(file));
    }

    final JavaFileObject file;
    final String contents;
    final JavacTask task;
    final CompilationUnitTree root;
    final Trees trees;
    final boolean hasSyntaxErrors;

    private Parser(JavaFileObject file) {
        this.file = file;
        try {
            this.contents = file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        this.task = singleFileTask(file, diagnostics);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.trees = Trees.instance(task);
        this.hasSyntaxErrors = diagnostics.getDiagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR);
    }

    static void parseFile(Path file) {
        parseJavaFileObject(new SourceFileObject(file));
    }

    static Parser parseJavaFileObject(JavaFileObject file) {
        // Parse directly from the current SourceFileObject document contents.
        // This avoids cross-request stale AST races on shared global parse state.
        return new Parser(file);
    }
}
