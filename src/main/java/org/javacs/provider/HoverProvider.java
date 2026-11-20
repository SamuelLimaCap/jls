package org.javacs.provider;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import java.util.logging.Logger;
import org.javacs.*;
import org.javacs.lsp.*;

public class HoverProvider {
    private static final Logger LOG = Logger.getLogger("main");
    final CompilerProvider compiler;

    public HoverProvider(CompilerProvider compiler) {
        this.compiler = compiler;
    }

    public MarkupContent hover(Path file, int line, int column) {
        var start = System.currentTimeMillis();
        try (var task = compiler.compile(file)) {
            var root = task.root(file);
            long cursor;
            try {
                cursor = FileStore.offset(root.getSourceFile().getCharContent(true).toString(), line, column);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            var path = new FindNameAt(task).scan(root, cursor);
            if (path == null) return null;

            var element = task.trees.getElement(path);
            if (element == null) return null;
            // On "new Foo(...)" FindNameAt may land on the class; prefer the invoked constructor.
            if (element instanceof TypeElement && path.getParentPath() != null
                    && path.getParentPath().getLeaf() instanceof NewClassTree newClass
                    && newClass.getIdentifier() == path.getLeaf()) {
                var ctor = task.trees.getElement(path.getParentPath());
                if (ctor instanceof ExecutableElement) element = ctor;
            }

            var markdown = new StringBuilder();

            var typeElement = enclosingType(element);
            if (typeElement == null && element instanceof TypeElement self) {
                typeElement = self;
            }
            if (typeElement != null) {
                var qualified = typeElement.getQualifiedName().toString();
                var lastDot = qualified.lastIndexOf('.');
                if (lastDot >= 0) {
                    markdown.append("**")
                            .append(qualified.substring(0, lastDot))
                            .append("**\n\n");
                }
            }

            SourceMethod source = null;
            if (element instanceof ExecutableElement method) {
                source = sourceMethod(method, task);
            }

            var sig = renderSignature(element, source);
            var docTree = docCommentTree(element, task, source);
            String doc;
            if (docTree != null) {
                doc = MarkdownHelper.asMarkdown(docTree, element instanceof ExecutableElement);
            } else {
                doc = getDocComment(element, task);
            }

            markdown.append("```java\n").append(sig).append("\n```");
            if (element instanceof ExecutableElement method && !method.getParameters().isEmpty()) {
                markdown.append("\n\n").append(renderParameters(method, source, docTree));
            }
            if (!doc.isEmpty()) {
                markdown.append("\n\n---\n\n").append(doc);
            }

            return new MarkupContent(MarkupKind.Markdown, markdown.toString());
        } catch (Exception e) {
            return null;
        } finally {
            LOG.info(String.format("[cache] hover:compile %dms", System.currentTimeMillis() - start));
        }
    }

    /** The method's declaration in source (project or JDK/library sources), parsed once per hover. */
    private record SourceMethod(ParseTask parse, MethodTree tree, String[] names) {}

    private SourceMethod sourceMethod(ExecutableElement method, CompileTask task) {
        if (!(method.getEnclosingElement() instanceof TypeElement type)) return null;
        var className = type.getQualifiedName().toString();
        try {
            var sourceFile = compiler.findAnywhere(className);
            if (sourceFile.isEmpty()) return null;
            var parse = compiler.parse(sourceFile.get());
            var erasedTypes = FindHelper.erasedParameterTypes(task, method);
            var name = method.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : method.getSimpleName().toString();
            MethodTree tree;
            try {
                tree = FindHelper.findMethod(parse, className, name, erasedTypes);
            } catch (RuntimeException e) {
                tree = uniqueByArity(parse, className, name, erasedTypes.length);
            }
            if (tree != null) {
                var names = tree.getParameters().stream().map(v -> v.getName().toString()).toArray(String[]::new);
                return new SourceMethod(parse, tree, names);
            }
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                var names = lombokConstructorNames(parse, className, method);
                if (names != null) return new SourceMethod(parse, null, names);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Type variables (T... a) don't match their erasure (Object[]): accept a single same-name, same-arity method. */
    private static MethodTree uniqueByArity(ParseTask parse, String className, String name, int arity) {
        var classTree = FindHelper.findType(parse, className);
        if (classTree == null) return null;
        MethodTree found = null;
        for (var member : classTree.getMembers()) {
            if (!(member instanceof MethodTree m)) continue;
            if (!m.getName().contentEquals(name) || m.getParameters().size() != arity) continue;
            if (found != null) return null;
            found = m;
        }
        return found;
    }

    /**
     * Lombok @AllArgsConstructor / @RequiredArgsConstructor have no source: the parameters are the
     * instance fields in declaration order (all of them, or only the uninitialized final/@NonNull ones).
     */
    private static String[] lombokConstructorNames(ParseTask parse, String className, ExecutableElement ctor) {
        var classTree = FindHelper.findType(parse, className);
        if (classTree == null) return null;
        var all = new java.util.ArrayList<VariableTree>();
        var required = new java.util.ArrayList<VariableTree>();
        for (var member : classTree.getMembers()) {
            if (!(member instanceof VariableTree v)) continue;
            var flags = v.getModifiers().getFlags();
            if (flags.contains(Modifier.STATIC)) continue;
            var isFinal = flags.contains(Modifier.FINAL);
            if (isFinal && v.getInitializer() != null) continue;
            all.add(v);
            var nonNull = v.getModifiers().getAnnotations().stream()
                    .anyMatch(a -> a.getAnnotationType().toString().endsWith("NonNull"));
            if (v.getInitializer() == null && (isFinal || nonNull)) required.add(v);
        }
        for (var fields : java.util.List.of(all, required)) {
            if (fieldsMatch(fields, ctor)) {
                return fields.stream().map(v -> v.getName().toString()).toArray(String[]::new);
            }
        }
        return null;
    }

    private static boolean fieldsMatch(java.util.List<VariableTree> fields, ExecutableElement ctor) {
        var params = ctor.getParameters();
        if (fields.size() != params.size() || fields.isEmpty()) return false;
        for (int i = 0; i < fields.size(); i++) {
            var fieldType = fields.get(i).getType().toString().replaceAll("<.*", "");
            fieldType = fieldType.substring(fieldType.lastIndexOf('.') + 1);
            var paramType = params.get(i).asType();
            var simple = paramType instanceof DeclaredType d
                    ? d.asElement().getSimpleName().toString()
                    : paramType.toString();
            if (!fieldType.equals(simple)) return false;
        }
        return true;
    }

    private DocCommentTree docCommentTree(Element element, CompileTask task, SourceMethod source) {
        try {
            var tree = DocTrees.instance(task.task).getDocCommentTree(element);
            if (tree != null) return tree;
        } catch (RuntimeException e) {
            // element came from a class file: fall through to the source copy
        }
        if (source == null || source.tree() == null) return null;
        try {
            var path = Trees.instance(source.parse().task()).getPath(source.parse().root(), source.tree());
            return path == null ? null : DocTrees.instance(source.parse().task()).getDocCommentTree(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Parâmetros" list: type, real name and the @param text when the Javadoc has one. */
    private String renderParameters(ExecutableElement method, SourceMethod source, DocCommentTree doc) {
        var names = parameterNames(method, source);
        var descriptions = new java.util.HashMap<String, String>();
        if (doc != null) {
            for (var tag : doc.getBlockTags()) {
                if (tag instanceof com.sun.source.doctree.ParamTree param && !param.isTypeParameter()) {
                    descriptions.put(param.getName().toString(), MarkdownHelper.asMarkdown(param.getDescription()));
                }
            }
        }
        var sb = new StringBuilder("**Parâmetros**\n");
        var params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            var type = simpleTypeName(params.get(i).asType());
            if (method.isVarArgs() && i == params.size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            sb.append("\n- `").append(type).append(" ").append(names[i]).append("`");
            var desc = descriptions.get(names[i]);
            if (desc != null && !desc.isBlank()) {
                sb.append(" — ").append(desc.replace("\n", " "));
            }
        }
        return sb.toString();
    }

    /** Real parameter names: the element's own, then the class file's LocalVariableTable, then the source. */
    private String[] parameterNames(ExecutableElement method, SourceMethod source) {
        var params = method.getParameters();
        var names = new String[params.size()];
        for (int i = 0; i < names.length; i++) names[i] = params.get(i).getSimpleName().toString();
        if (!hasSyntheticNames(params)) return names;
        if (source != null && source.names().length == names.length) {
            return source.names().clone();
        }
        var fromClass = resolveParamNames(method);
        if (fromClass != null) {
            for (int i = 0; i < names.length; i++) if (fromClass[i] != null) names[i] = fromClass[i];
        }
        return names;
    }

    private String renderSignature(Element element, SourceMethod source) {
        return switch (element.getKind()) {
            case METHOD, CONSTRUCTOR -> renderMethodSignature((ExecutableElement) element, source);
            case FIELD, ENUM_CONSTANT -> renderFieldSignature((VariableElement) element);
            case CLASS, INTERFACE, ENUM, ANNOTATION_TYPE, RECORD -> renderClassSignature((TypeElement) element);
            case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER -> renderVariableSignature(
                    (VariableElement) element);
            default -> element.toString();
        };
    }

    private String renderMethodSignature(ExecutableElement method, SourceMethod source) {
        var sb = new StringBuilder();
        var modifiers = method.getModifiers().stream()
                .map(m -> m.toString().toLowerCase())
                .filter(m -> !m.isEmpty())
                .collect(Collectors.joining(" "));
        if (!modifiers.isEmpty()) sb.append(modifiers).append(" ");

        var typeParams = method.getTypeParameters();
        if (!typeParams.isEmpty()) {
            sb.append("<");
            var tp = new StringJoiner(", ");
            for (var t : typeParams) tp.add(t.asType().toString());
            sb.append(tp).append("> ");
        }

        if (method.getKind() == ElementKind.CONSTRUCTOR) {
            sb.append(method.getEnclosingElement().getSimpleName()).append("(");
        } else {
            sb.append(simpleTypeName(method.getReturnType())).append(" ");
            sb.append(method.getSimpleName()).append("(");
        }

        // javac gives synthetic arg0/arg1 for members read from class files: use the real names
        var paramElements = method.getParameters();
        var names = parameterNames(method, source);

        var params = new StringJoiner(", ");
        for (int i = 0; i < paramElements.size(); i++) {
            var type = simpleTypeName(paramElements.get(i).asType());
            if (method.isVarArgs() && i == paramElements.size() - 1 && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            params.add(type + " " + names[i]);
        }
        sb.append(params).append(")");
        var thrown = method.getThrownTypes();
        if (!thrown.isEmpty()) {
            sb.append(" throws ");
            var tj = new StringJoiner(", ");
            for (var t : thrown) tj.add(simpleTypeName(t));
            sb.append(tj);
        }
        return sb.toString();
    }

    private static boolean hasSyntheticNames(java.util.List<? extends VariableElement> params) {
        for (var p : params) {
            var name = p.getSimpleName().toString();
            if (name.matches("arg\\d+")) return true;
        }
        return false;
    }

    private String[] resolveParamNames(ExecutableElement method) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type)) return null;
        var className = type.getQualifiedName().toString();
        var classBytes = compiler.findClassFile(className);
        if (classBytes.isEmpty()) return null;
        var erasedTypes = new String[method.getParameters().size()];
        for (int i = 0; i < erasedTypes.length; i++) {
            erasedTypes[i] = method.getParameters().get(i).asType().toString();
        }
        return ClassFileParameterNames.read(
                classBytes.get(), method.getSimpleName().toString(), erasedTypes,
                method.getModifiers().contains(javax.lang.model.element.Modifier.STATIC));
    }

    private String renderFieldSignature(VariableElement field) {
        return simpleTypeName(field.asType()) + " " + field.getSimpleName();
    }

    private String renderClassSignature(TypeElement type) {
        var sb = new StringBuilder();
        var modifiers = type.getModifiers().stream()
                .map(m -> m.toString().toLowerCase())
                .filter(m -> !m.isEmpty())
                .collect(Collectors.joining(" "));
        if (!modifiers.isEmpty()) sb.append(modifiers).append(" ");

        var kind = switch (type.getKind()) {
            case ANNOTATION_TYPE -> "@interface";
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            default -> "class";
        };
        sb.append(kind).append(" ").append(type.getSimpleName());
        return sb.toString();
    }

    private String renderVariableSignature(VariableElement variable) {
        return simpleTypeName(variable.asType()) + " " + variable.getSimpleName();
    }

    private TypeElement enclosingType(Element element) {
        var current = element.getEnclosingElement();
        while (current != null) {
            if (current instanceof TypeElement type) return type;
            current = current.getEnclosingElement();
        }
        return null;
    }

    private String simpleTypeName(TypeMirror type) {
        return switch (type.getKind()) {
            case DECLARED -> {
                var declared = (DeclaredType) type;
                var name = declared.asElement().getSimpleName().toString();
                var args = declared.getTypeArguments();
                if (args.isEmpty()) yield name;
                var ja = new StringJoiner(", ");
                for (var a : args) ja.add(simpleTypeName(a));
                yield name + "<" + ja + ">";
            }
            case ARRAY -> {
                var array = (ArrayType) type;
                yield simpleTypeName(array.getComponentType()) + "[]";
            }
            case TYPEVAR -> type.toString();
            case WILDCARD -> "?";
            default -> type.toString();
        };
    }

    private String getDocComment(Element element, CompileTask task) {
        var elements = task.elements;
        var doc = elements.getDocComment(element);
        if (doc != null && !doc.isEmpty()) return MarkdownHelper.asMarkdown(doc.trim());

        if (element instanceof ExecutableElement method) {
            return methodDocFromSource(method, task);
        }
        if (element instanceof TypeElement type) {
            return typeDocFromSource(type, task);
        }
        return "";
    }

    private String methodDocFromSource(ExecutableElement method, CompileTask task) {
        var enclosing = method.getEnclosingElement();
        if (!(enclosing instanceof TypeElement type)) return "";
        var className = type.getQualifiedName().toString();
        var sourceFile = compiler.findAnywhere(className);
        if (sourceFile.isEmpty()) return "";

        try {
            var parse = compiler.parse(sourceFile.get());
            var erasedTypes = FindHelper.erasedParameterTypes(task, method);
            var methodTree = FindHelper.findMethod(
                    parse, className, method.getSimpleName().toString(), erasedTypes);
            var path = Trees.instance(parse.task()).getPath(parse.root(), methodTree);
            var docTree = DocTrees.instance(parse.task()).getDocCommentTree(path);
            return docTree != null ? MarkdownHelper.asMarkdown(docTree) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String typeDocFromSource(TypeElement type, CompileTask task) {
        var className = type.getQualifiedName().toString();
        var sourceFile = compiler.findAnywhere(className);
        if (sourceFile.isEmpty()) return "";

        try {
            var parse = compiler.parse(sourceFile.get());
            var typeTree = FindHelper.findType(parse, className);
            if (typeTree == null) return "";
            var path = Trees.instance(parse.task()).getPath(parse.root(), typeTree);
            if (path == null) return "";
            var docTree = DocTrees.instance(parse.task()).getDocCommentTree(path);
            return docTree != null ? MarkdownHelper.asMarkdown(docTree) : "";
        } catch (RuntimeException e) {
            return "";
        }
    }
}
