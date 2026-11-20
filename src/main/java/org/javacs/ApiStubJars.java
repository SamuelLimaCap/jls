package org.javacs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * API jars published without method bodies (javaee-api / javaee-web-api 6.0 on Maven Central). javac
 * reads them fine, but a class loader that defines a class from one throws {@code ClassFormatError:
 * Absent Code attribute}. When a project also has a real implementation of the same API (e.g.
 * hibernate-jpa-2.1-api for javax.persistence), the stub must come last in reflective class loaders,
 * otherwise {@code Session.createQuery(...)} could not be resolved for completion.
 */
public final class ApiStubJars {
    private static final Pattern STUB = Pattern.compile("^(javaee-web-api|javaee-api|jakartaee-web-api|jakartaee-api)-[0-9].*\\.jar$");

    private ApiStubJars() {}

    public static boolean isStub(Path path) {
        var name = path.getFileName();
        return name != null && STUB.matcher(name.toString()).matches();
    }

    /** Same paths, same relative order, with the stub API jars moved to the end. */
    public static List<Path> stubsLast(Collection<Path> classPath) {
        var normal = new ArrayList<Path>(classPath.size());
        var stubs = new ArrayList<Path>();
        for (var path : classPath) {
            (isStub(path) ? stubs : normal).add(path);
        }
        normal.addAll(stubs);
        return normal;
    }
}
