package org.javacs;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;
import org.javacs.lsp.Position;

/**
 * Attributed type of the largest expression that ends at a position, for the editor's ".var"
 * postfix: in {@code Context.getCustomer().var} the expression before ".var" still attributes
 * even though ".var" itself does not resolve.
 */
final class ExpressionTypeAt {
    private ExpressionTypeAt() {}

    /** {type: "List<String>", qualified: "java.util.List<java.lang.String>", imports: [...]} or null. */
    static Map<String, Object> find(CompileTask task, CompilationUnitTree root, int line, int character) {
        var offset = LspPosition.offset(root, new Position(line, character));
        var positions = task.trees.getSourcePositions();
        TreePath[] best = {null};
        long[] bestStart = {Long.MAX_VALUE};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree instanceof ExpressionTree && positions.getEndPosition(root, tree) == offset) {
                    var start = positions.getStartPosition(root, tree);
                    if (start >= 0 && start < bestStart[0]) {
                        bestStart[0] = start;
                        best[0] = new TreePath(getCurrentPath(), tree);
                    }
                }
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        if (best[0] == null) return null;
        var type = task.trees.getTypeMirror(best[0]);
        if (type == null || type.getKind() == TypeKind.ERROR || type.getKind() == TypeKind.NONE
                || type.getKind() == TypeKind.VOID || type.getKind() == TypeKind.NULL) {
            return null;
        }
        var imports = new LinkedHashSet<String>();
        var simple = simpleName(type, imports);
        return Map.of("type", simple, "qualified", type.toString(), "imports", imports);
    }

    /** Source-style name with simple class names; collects the classes it needs imported. */
    private static String simpleName(TypeMirror type, Set<String> imports) {
        switch (type.getKind()) {
            case DECLARED -> {
                var declared = (DeclaredType) type;
                var element = (TypeElement) declared.asElement();
                var qualified = element.getQualifiedName().toString();
                // nested types are written Outer.Inner and imported through the top-level class
                var name = element.getSimpleName().toString();
                var top = element;
                while (top.getEnclosingElement() instanceof TypeElement outer) {
                    name = outer.getSimpleName() + "." + name;
                    top = outer;
                }
                var topName = top.getQualifiedName().toString();
                if (!topName.startsWith("java.lang.") || topName.substring("java.lang.".length()).contains(".")) {
                    imports.add(topName);
                }
                if (declared.getTypeArguments().isEmpty()) return name;
                var args = new StringBuilder();
                for (var arg : declared.getTypeArguments()) {
                    if (!args.isEmpty()) args.append(", ");
                    args.append(simpleName(arg, imports));
                }
                return name + "<" + args + ">";
            }
            case ARRAY -> {
                return simpleName(((ArrayType) type).getComponentType(), imports) + "[]";
            }
            case WILDCARD -> {
                var wildcard = (WildcardType) type;
                if (wildcard.getExtendsBound() != null) return "? extends " + simpleName(wildcard.getExtendsBound(), imports);
                if (wildcard.getSuperBound() != null) return "? super " + simpleName(wildcard.getSuperBound(), imports);
                return "?";
            }
            case TYPEVAR -> {
                // a captured wildcard (capture#1 of ? extends Foo) is declared through its bound
                var variable = (TypeVariable) type;
                if (variable.toString().startsWith("capture#")) return simpleName(variable.getUpperBound(), imports);
                return variable.toString();
            }
            case INTERSECTION -> {
                return simpleName(((javax.lang.model.type.IntersectionType) type).getBounds().get(0), imports);
            }
            default -> {
                return type.toString();
            }
        }
    }
}
