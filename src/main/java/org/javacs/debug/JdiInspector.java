package org.javacs.debug;

import com.sun.jdi.*;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.javacs.debug.proto.CompletionItem;
import org.javacs.debug.proto.Variable;

/**
 * Shows JDI values the way a debugger UI needs them and evaluates Java expressions in a stopped
 * frame.
 *
 * <p>Structured values (objects, arrays, collections, maps) get a {@code variablesReference} so the
 * client can expand them. Handles live in their own id range (from {@link #BASE}, apart from the
 * frame-derived scope references) and are dropped whenever the VM resumes.
 *
 * <p>{@code toString()} is only called automatically on plain JDK value types. Calling it on an
 * arbitrary object (a JPA entity, say) could run lazy loading or other code while the VM is
 * suspended; such objects show their type and are expanded field by field instead.
 */
final class JdiInspector {
    static final long BASE = 1_000_000_000L;
    private static final int MAX_CHILDREN = 500;

    private record Handle(ObjectReference object, ThreadReference thread) {}

    private final VirtualMachine vm;
    private final Map<Long, Handle> handles = new HashMap<>();
    private long next = BASE;

    JdiInspector(VirtualMachine vm) {
        this.vm = vm;
    }

    static boolean isHandle(long reference) {
        return reference >= BASE;
    }

    /** Forget every handle: object references are only meaningful while the VM stays suspended. */
    void clear() {
        handles.clear();
        next = BASE;
    }

    // ---------------------------------------------------------------------------------- display

    Variable variable(String name, Value value, ThreadReference thread, String evaluateName) {
        var v = new Variable();
        v.name = name;
        v.value = display(value, thread);
        v.type = value == null ? null : value.type().name();
        v.evaluateName = evaluateName;
        v.variablesReference = reference(value, thread);
        if (value instanceof ArrayReference array) v.indexedVariables = array.length();
        return v;
    }

    long reference(Value value, ThreadReference thread) {
        if (!(value instanceof ObjectReference object) || value instanceof StringReference) return 0;
        if (isBoxedOrSimple(object.referenceType().name())) return 0;
        var id = next++;
        handles.put(id, new Handle(object, thread));
        return id;
    }

    String display(Value value, ThreadReference thread) {
        if (value == null) return "null";
        if (value instanceof StringReference s) return '"' + s.value() + '"';
        if (value instanceof PrimitiveValue) return value.toString();
        if (value instanceof ArrayReference a) return simpleName(a.type().name()).replace("[]", "[" + a.length() + "]");
        var object = (ObjectReference) value;
        var type = object.referenceType();
        var typeName = type.name();
        try {
            if (type instanceof ClassType c && c.isEnum()) return simpleName(typeName) + "." + enumName(object, thread);
            if (isBoxedOrSimple(typeName)) return callToString(object, thread);
            var size = sizeOf(object, thread);
            if (size >= 0) return simpleName(typeName) + " size=" + size + preview(object);
            return summary(object);
        } catch (Exception e) {
            // fall through to the plain description
        }
        return simpleName(typeName) + " (id=" + object.uniqueID() + ")";
    }

    private static final int SUMMARY_FIELDS = 4, SUMMARY_LENGTH = 90, SHALLOW_STRING = 30, PREVIEW_ITEMS = 3;

    /**
     * {@code Tipo{campo=valor, …}} with the first fields of the object (declared ones first, then the
     * superclasses'). Values are read straight from the fields: nothing runs in the suspended VM, so a
     * JPA entity shows its data without triggering lazy loading.
     */
    private String summary(ObjectReference object) {
        var typeName = object.referenceType().name();
        if (typeName.contains("$HibernateProxy$")) {
            return simpleName(typeName.substring(0, typeName.indexOf("$HibernateProxy$"))) + " (proxy Hibernate)";
        }
        var text = new StringBuilder(simpleName(typeName)).append('{');
        var count = 0;
        for (var type = object.referenceType(); type != null; type = type instanceof ClassType c ? c.superclass() : null) {
            if (type.name().equals("java.lang.Object")) break;
            for (var field : type.fields()) {
                if (field.isStatic() || field.isSynthetic() || field.name().startsWith("$$")) continue;
                if (count == SUMMARY_FIELDS || text.length() > SUMMARY_LENGTH) return text.append(", …}").toString();
                if (count++ > 0) text.append(", ");
                text.append(field.name()).append('=').append(shallow(object.getValue(field)));
            }
        }
        return text.append('}').toString();
    }

    /** First items of an ArrayList, read from its fields (other collections only show the size). */
    private String preview(ObjectReference object) {
        if (!object.referenceType().name().equals("java.util.ArrayList")) return "";
        var sizeField = object.referenceType().fieldByName("size");
        var dataField = object.referenceType().fieldByName("elementData");
        if (sizeField == null || dataField == null) return "";
        var size = ((IntegerValue) object.getValue(sizeField)).value();
        if (size == 0 || !(object.getValue(dataField) instanceof ArrayReference data)) return "";
        var items = new ArrayList<String>();
        for (var item : data.getValues(0, Math.min(size, PREVIEW_ITEMS))) items.add(shallow(item));
        return " [" + String.join(", ", items) + (size > PREVIEW_ITEMS ? ", …" : "") + "]";
    }

    /** One-word description of a field value, without invoking anything in the VM. */
    private String shallow(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference s) {
            var text = s.value();
            return '"' + (text.length() > SHALLOW_STRING ? text.substring(0, SHALLOW_STRING) + "…" : text) + '"';
        }
        if (value instanceof PrimitiveValue) return value.toString();
        if (value instanceof ArrayReference a) return simpleName(a.type().name()).replace("[]", "[" + a.length() + "]");
        var object = (ObjectReference) value;
        var type = object.referenceType();
        var typeName = type.name();
        if (type instanceof ClassType c && c.isEnum()) return enumName(object, null);
        switch (typeName) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
                    "java.lang.Float", "java.lang.Boolean", "java.lang.Character" -> {
                var field = type.fieldByName("value");
                if (field != null) return String.valueOf(object.getValue(field));
            }
            case "java.math.BigDecimal" -> {
                var compact = type.fieldByName("intCompact");
                var scale = type.fieldByName("scale");
                if (compact != null && scale != null
                        && object.getValue(compact) instanceof LongValue l && l.value() != Long.MIN_VALUE
                        && object.getValue(scale) instanceof IntegerValue s) {
                    return java.math.BigDecimal.valueOf(l.value(), s.value()).toPlainString();
                }
            }
            default -> {}
        }
        if (typeName.contains("$HibernateProxy$")) return simpleName(typeName.substring(0, typeName.indexOf("$HibernateProxy$")));
        return simpleName(typeName);
    }

    private static boolean isBoxedOrSimple(String typeName) {
        return switch (typeName) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
                    "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character",
                    "java.lang.String", "java.math.BigDecimal", "java.math.BigInteger", "java.util.Date",
                    "java.sql.Date", "java.sql.Timestamp", "java.time.LocalDate", "java.time.LocalDateTime",
                    "java.time.LocalTime", "java.time.Instant", "java.util.UUID", "java.lang.Class" -> true;
            default -> false;
        };
    }

    private static String simpleName(String typeName) {
        var dot = typeName.lastIndexOf('.');
        return dot < 0 ? typeName : typeName.substring(dot + 1);
    }

    private String enumName(ObjectReference object, ThreadReference thread) {
        var field = object.referenceType().fieldByName("name");
        var value = field == null ? null : object.getValue(field);
        return value instanceof StringReference s ? s.value() : "?";
    }

    private String callToString(ObjectReference object, ThreadReference thread) throws Exception {
        var result = invoke(object, "toString", List.of(), thread);
        return result instanceof StringReference s ? s.value() : String.valueOf(result);
    }

    /** size() of a java.util.Collection or java.util.Map, -1 for anything else. */
    private int sizeOf(ObjectReference object, ThreadReference thread) throws Exception {
        if (!implementsType(object.referenceType(), "java.util.Collection")
                && !implementsType(object.referenceType(), "java.util.Map")) return -1;
        return ((IntegerValue) invoke(object, "size", List.of(), thread)).value();
    }

    private static boolean implementsType(ReferenceType type, String name) {
        if (type instanceof ClassType c) {
            for (var i : c.allInterfaces()) if (i.name().equals(name)) return true;
        } else if (type instanceof InterfaceType i) {
            if (i.name().equals(name)) return true;
            for (var s : i.superinterfaces()) if (implementsType(s, name)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------- children

    List<Variable> children(long reference) {
        var handle = handles.get(reference);
        var children = new ArrayList<Variable>();
        if (handle == null) return children;
        var object = handle.object();
        var thread = handle.thread();
        try {
            if (object instanceof ArrayReference array) {
                var count = Math.min(array.length(), MAX_CHILDREN);
                var values = array.getValues(0, count);
                for (int i = 0; i < count; i++) children.add(variable("[" + i + "]", values.get(i), thread, null));
                return children;
            }
            var type = object.referenceType();
            if (implementsType(type, "java.util.Map")) {
                var entries = (ArrayReference) invoke((ObjectReference) invoke(object, "entrySet", List.of(), thread), "toArray", List.of(), thread);
                for (var entry : entries.getValues(0, Math.min(entries.length(), MAX_CHILDREN))) {
                    var e = (ObjectReference) entry;
                    var key = invoke(e, "getKey", List.of(), thread);
                    children.add(variable(display(key, thread), invoke(e, "getValue", List.of(), thread), thread, null));
                }
                return children;
            }
            if (implementsType(type, "java.util.Collection")) {
                var items = (ArrayReference) invoke(object, "toArray", List.of(), thread);
                var count = Math.min(items.length(), MAX_CHILDREN);
                var values = items.getValues(0, count);
                for (int i = 0; i < count; i++) children.add(variable("[" + i + "]", values.get(i), thread, null));
                return children;
            }
            for (var field : type.allFields()) {
                if (field.isStatic()) continue;
                children.add(variable(field.name(), object.getValue(field), thread, null));
            }
        } catch (Exception e) {
            var error = new Variable();
            error.name = "erro";
            error.value = describe(e);
            children.add(error);
        }
        return children;
    }

    // ---------------------------------------------------------------------------------- completions

    private static final int MAX_COMPLETIONS = 300;
    private static final Set<String> HIDDEN_OBJECT_METHODS =
            Set.of("wait", "notify", "notifyAll", "finalize", "clone", "registerNatives");

    /** Type whose members are offered after a ".", with the value when it is known without running code. */
    private record Target(ReferenceType type, Value value, boolean statics) {}

    /**
     * Completions for the expression typed so far ({@code column} is 1-based, as the client sends).
     * After {@code a.b().} the members of the type of {@code a.b()}; otherwise the names visible in the
     * frame. The receiver's type is resolved without running anything in the VM: locals and fields
     * are read (their runtime type), methods give their declared return type.
     */
    List<CompletionItem> complete(String text, int column, FrameRef ref) {
        var items = new ArrayList<CompletionItem>();
        var upTo = text.substring(0, Math.max(0, Math.min(text.length(), column - 1)));
        int start = upTo.length();
        while (start > 0 && Character.isJavaIdentifierPart(upTo.charAt(start - 1))) start--;
        var fragment = upTo.substring(start);
        try {
            if (start > 0 && upTo.charAt(start - 1) == '.') {
                var receiver = receiver(upTo.substring(0, start - 1));
                var target = receiver.isEmpty() ? null : resolve(receiver, ref);
                if (target != null) members(target, fragment, start + 1, items);
            } else {
                names(ref, fragment, start + 1, items);
            }
        } catch (Exception e) {
            // incomplete or unknown expression: no completions
        }
        return items;
    }

    /** The expression right before the last "." (stops at operators, spaces and unbalanced parens). */
    private static String receiver(String text) {
        int depth = 0, i = text.length();
        while (i > 0) {
            var c = text.charAt(i - 1);
            if (c == ')' || c == ']') depth++;
            else if (c == '(' || c == '[') {
                if (depth == 0) break;
                depth--;
            } else if (depth == 0 && !(Character.isJavaIdentifierPart(c) || c == '.')) break;
            i--;
        }
        return text.substring(i).trim();
    }

    /** "a.b(1, c.d()).e[0]" -> ["a", "b(1, c.d())", "e[0]"] */
    private static List<String> segments(String chain) {
        var parts = new ArrayList<String>();
        int depth = 0, from = 0;
        for (int i = 0; i < chain.length(); i++) {
            var c = chain.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (c == '.' && depth == 0) {
                parts.add(chain.substring(from, i).trim());
                from = i + 1;
            }
        }
        parts.add(chain.substring(from).trim());
        return parts;
    }

    /** Index right after the group opened at {@code text.charAt(0)} ("(...)" or "[...]"). */
    private static int closing(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            var c = text.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if ((c == ')' || c == ']') && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private Target resolve(String chain, FrameRef ref) throws Exception {
        Target target = null;
        for (var segment : segments(chain)) {
            int n = 0;
            while (n < segment.length() && Character.isJavaIdentifierPart(segment.charAt(n))) n++;
            var name = segment.substring(0, n);
            var rest = segment.substring(n).trim();
            if (name.isEmpty()) return null;
            var call = rest.startsWith("(");
            if (call) rest = rest.substring(closing(rest)).trim();
            if (target == null) {
                if (call) {
                    var self = ref.frame().thisObject();
                    var type = self != null ? self.referenceType() : ref.frame().location().declaringType();
                    target = returnType(type, name);
                } else {
                    target = start(name, ref);
                }
            } else {
                target = call ? returnType(target.type(), name) : field(target, name);
            }
            while (target != null && rest.startsWith("[")) {
                rest = rest.substring(closing(rest)).trim();
                target = target.type() instanceof ArrayType a && componentType(a) instanceof ReferenceType c
                        ? new Target(c, null, false)
                        : null;
            }
            if (target == null) return null;
        }
        return target;
    }

    private static Type componentType(ArrayType array) {
        try {
            return array.componentType();
        } catch (ClassNotLoadedException e) {
            return null;
        }
    }

    /** First name of the chain: a local, a field of this, a static field or a class. */
    private Target start(String name, FrameRef ref) throws Exception {
        Result result;
        try {
            result = identifier(name, ref);
        } catch (NameNotFound e) {
            return null;
        }
        if (result.staticType() != null) return new Target(result.staticType(), null, true);
        if (result.value() instanceof ObjectReference o) return new Target(o.referenceType(), o, false);
        if (result.value() != null) return null; // primitive
        // null value: the declared type of the local or field
        var frame = ref.frame();
        try {
            var local = frame.visibleVariableByName(name);
            if (local != null) return local.type() instanceof ReferenceType t ? new Target(t, null, false) : null;
        } catch (AbsentInformationException | ClassNotLoadedException e) {
            // fall through to fields
        }
        var self = frame.thisObject();
        var type = self != null ? self.referenceType() : frame.location().declaringType();
        var field = type.fieldByName(name);
        return field == null ? null : declared(field);
    }

    private Target field(Target target, String name) {
        var field = target.type().fieldByName(name);
        if (field == null) return null;
        if (target.value() instanceof ObjectReference o && !field.isStatic()
                && o.getValue(field) instanceof ObjectReference v) {
            return new Target(v.referenceType(), v, false);
        }
        return declared(field);
    }

    private Target declared(Field field) {
        ReferenceType type;
        try {
            type = field.type() instanceof ReferenceType t ? t : null;
        } catch (ClassNotLoadedException e) {
            type = findClass(field.typeName());
        }
        return type == null ? null : new Target(type, null, false);
    }

    /** Declared return type of the first method with that name (generics are erased: List.get gives Object). */
    private Target returnType(ReferenceType type, String name) {
        for (var method : type.methodsByName(name)) {
            ReferenceType result;
            try {
                result = method.returnType() instanceof ReferenceType t ? t : null;
            } catch (ClassNotLoadedException e) {
                result = findClass(method.returnTypeName());
            }
            if (result != null) return new Target(result, null, false);
        }
        return null;
    }

    private static boolean matches(String name, String fragment) {
        return name.regionMatches(true, 0, fragment, 0, fragment.length());
    }

    private static CompletionItem item(String label, String text, String kind, int start, String fragment) {
        var item = new CompletionItem();
        item.label = label;
        item.text = text;
        item.type = kind;
        item.start = start;
        item.length = fragment.length();
        return item;
    }

    /** Library classes only offer their public API; project classes offer everything (private fields too). */
    private static boolean library(ReferenceType type) {
        var name = type.name();
        for (var prefix : List.of("java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "org.springframework.",
                "org.hibernate.", "org.apache.", "com.fasterxml.")) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    private void members(Target target, String fragment, int start, List<CompletionItem> items) {
        var type = target.type();
        for (var field : type.allFields()) {
            if (field.isSynthetic() || field.name().startsWith("$$") || (target.statics() && !field.isStatic())) continue;
            if (field.name().equals("serialVersionUID") || (library(field.declaringType()) && !field.isPublic())) continue;
            if (!matches(field.name(), fragment)) continue;
            items.add(item(field.name() + " : " + simpleName(field.typeName()), field.name(), "field", start, fragment));
        }
        var seen = new HashSet<String>();
        var fromObject = new ArrayList<CompletionItem>();
        for (var method : type.allMethods()) {
            if (method.isConstructor() || method.isStaticInitializer() || method.isSynthetic() || method.isBridge()) continue;
            if (target.statics() && !method.isStatic()) continue;
            if (library(method.declaringType()) && !method.isPublic()) continue;
            var name = method.name();
            if (name.contains("$") || !matches(name, fragment)) continue;
            var objectMethod = method.declaringType().name().equals("java.lang.Object");
            if (objectMethod && HIDDEN_OBJECT_METHODS.contains(name)) continue;
            var args = method.argumentTypeNames();
            if (!seen.add(name + "/" + String.join(",", args))) continue;
            var label = name + "(" + String.join(", ", args.stream().map(JdiInspector::simpleName).toList()) + ") : "
                    + simpleName(method.returnTypeName());
            var completion = item(label, name + (args.isEmpty() ? "()" : "("), "method", start, fragment);
            (objectMethod ? fromObject : items).add(completion);
        }
        items.addAll(fromObject);
        if (items.size() > MAX_COMPLETIONS) items.subList(MAX_COMPLETIONS, items.size()).clear();
    }

    private void names(FrameRef ref, String fragment, int start, List<CompletionItem> items) throws Exception {
        var frame = ref.frame();
        try {
            for (var local : frame.visibleVariables()) {
                if (matches(local.name(), fragment)) {
                    items.add(item(local.name() + " : " + simpleName(local.typeName()), local.name(), "variable", start, fragment));
                }
            }
        } catch (AbsentInformationException e) {
            // compiled without local variable information
        }
        var self = frame.thisObject();
        if (self != null && matches("this", fragment)) items.add(item("this", "this", "keyword", start, fragment));
        var type = self != null ? self.referenceType() : frame.location().declaringType();
        members(new Target(type, self, self == null), fragment, start, items);
    }

    // ---------------------------------------------------------------------------------- evaluate

    /** Result of an evaluation: the value (may be null) plus a static class when the expression names one. */
    private record Result(Value value, ReferenceType staticType) {
        static Result of(Value value) { return new Result(value, null); }
    }

    /**
     * A stack frame by thread and depth. Invoking a method resumes the thread for a moment, which
     * invalidates every StackFrame object of it, so the frame is fetched again on each use.
     */
    static final class FrameRef {
        final ThreadReference thread;
        private final int depth;

        FrameRef(StackFrame frame) throws IncompatibleThreadStateException {
            this.thread = frame.thread();
            this.depth = thread.frames().indexOf(frame);
        }

        StackFrame frame() throws IncompatibleThreadStateException {
            return thread.frame(Math.max(depth, 0));
        }
    }

    /** Evaluates a Java expression in a stopped frame. Throws with a readable message on failure. */
    Value evaluate(String expression, FrameRef frame) throws Exception {
        var tree = parse(expression);
        var result = eval(tree, frame);
        if (result.staticType() != null) throw new IllegalArgumentException(expression + " é um tipo, não um valor");
        return result.value();
    }

    private static ExpressionTree parse(String expression) throws Exception {
        var source = "class LvimEval { Object lvimValor = (" + expression + "\n); }";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///LvimEval.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        var errors = new ArrayList<String>();
        var task = (JavacTask) ToolProvider.getSystemJavaCompiler().getTask(
                null, null, d -> { if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) errors.add(d.getMessage(null)); },
                List.of("-proc:none"), null, List.of(file));
        var unit = task.parse().iterator().next();
        if (!errors.isEmpty()) throw new IllegalArgumentException("expressão inválida: " + errors.get(0));
        var cls = (ClassTree) unit.getTypeDecls().get(0);
        var field = (VariableTree) cls.getMembers().get(0);
        return field.getInitializer();
    }

    private Result eval(ExpressionTree tree, FrameRef frame) throws Exception {
        var thread = frame.thread;
        switch (tree) {
            case ParenthesizedTree p -> { return eval(p.getExpression(), frame); }
            case TypeCastTree c -> { return eval(c.getExpression(), frame); }
            case LiteralTree l -> { return Result.of(literal(l.getValue())); }
            case IdentifierTree id -> { return identifier(id.getName().toString(), frame); }
            case MemberSelectTree select -> {
                var name = select.getIdentifier().toString();
                Result target;
                try {
                    target = eval(select.getExpression(), frame);
                } catch (NameNotFound e) {
                    // a.b.C as a qualified class name
                    var type = findClass(select.toString());
                    if (type != null) return new Result(null, type);
                    throw e;
                }
                if (name.equals("length") && target.value() instanceof ArrayReference a) return Result.of(vm.mirrorOf(a.length()));
                if (target.staticType() != null) {
                    var field = target.staticType().fieldByName(name);
                    if (field == null || !field.isStatic()) throw new NameNotFound(select.toString());
                    return Result.of(target.staticType().getValue(field));
                }
                var object = requireObject(target.value(), select.getExpression().toString());
                var field = object.referenceType().fieldByName(name);
                if (field == null) throw new NameNotFound(select.toString());
                return Result.of(object.getValue(field));
            }
            case MethodInvocationTree call -> {
                var args = new ArrayList<Value>();
                for (var a : call.getArguments()) args.add(eval(a, frame).value());
                var select = call.getMethodSelect();
                if (select instanceof MemberSelectTree m) {
                    var target = eval(m.getExpression(), frame);
                    var name = m.getIdentifier().toString();
                    if (target.staticType() != null) return Result.of(invokeStatic(target.staticType(), name, args, thread));
                    return Result.of(invoke(requireObject(target.value(), m.getExpression().toString()), name, args, thread));
                }
                var name = ((IdentifierTree) select).getName().toString();
                var current = frame.frame();
                var self = current.thisObject();
                if (self != null) return Result.of(invoke(self, name, args, thread));
                return Result.of(invokeStatic(current.location().declaringType(), name, args, thread));
            }
            case ArrayAccessTree access -> {
                var array = eval(access.getExpression(), frame).value();
                var index = eval(access.getIndex(), frame).value();
                if (!(array instanceof ArrayReference a)) throw new IllegalArgumentException(access.getExpression() + " não é um array");
                return Result.of(a.getValue((int) number(index)));
            }
            case UnaryTree u -> {
                var operand = eval(u.getExpression(), frame).value();
                return Result.of(switch (u.getKind()) {
                    case LOGICAL_COMPLEMENT -> vm.mirrorOf(!bool(operand));
                    case UNARY_MINUS -> isIntegral(operand) ? vm.mirrorOf(-(long) number(operand)) : vm.mirrorOf(-number(operand));
                    case UNARY_PLUS -> operand;
                    default -> throw new IllegalArgumentException("operador não suportado: " + u.getKind());
                });
            }
            case BinaryTree b -> { return Result.of(binary(b, frame)); }
            case ConditionalExpressionTree c -> {
                return bool(eval(c.getCondition(), frame).value()) ? eval(c.getTrueExpression(), frame) : eval(c.getFalseExpression(), frame);
            }
            default -> throw new IllegalArgumentException("expressão não suportada no debug: " + tree.getKind());
        }
    }

    private static final class NameNotFound extends IllegalArgumentException {
        NameNotFound(String name) { super("não encontrado: " + name); }
    }

    private Result identifier(String name, FrameRef ref) throws Exception {
        var frame = ref.frame();
        if (name.equals("this")) {
            var self = frame.thisObject();
            if (self == null) throw new IllegalArgumentException("this não existe em método estático");
            return Result.of(self);
        }
        try {
            var local = frame.visibleVariableByName(name);
            if (local != null) return Result.of(frame.getValue(local));
        } catch (AbsentInformationException e) {
            // compiled without local variable information
        }
        var self = frame.thisObject();
        if (self != null) {
            var field = self.referenceType().fieldByName(name);
            if (field != null) return Result.of(field.isStatic() ? self.referenceType().getValue(field) : self.getValue(field));
        }
        var declaring = frame.location().declaringType();
        var staticField = declaring.fieldByName(name);
        if (staticField != null && staticField.isStatic()) return Result.of(declaring.getValue(staticField));
        var type = findClass(name);
        if (type != null) return new Result(null, type);
        throw new NameNotFound(name);
    }

    /** A loaded class by qualified name, or by simple name when exactly one loaded class has it. */
    private ReferenceType findClass(String name) {
        var exact = vm.classesByName(name);
        if (!exact.isEmpty()) return exact.get(0);
        if (name.contains(".")) return null;
        var lang = vm.classesByName("java.lang." + name);
        if (!lang.isEmpty()) return lang.get(0);
        ReferenceType found = null;
        for (var type : vm.allClasses()) {
            var n = type.name();
            if (n.endsWith("." + name) || n.endsWith("$" + name)) {
                if (found != null && !found.name().equals(n)) return null; // ambiguous
                found = type;
            }
        }
        return found;
    }

    private Value literal(Object value) {
        return switch (value) {
            case null -> null;
            case Integer i -> vm.mirrorOf(i);
            case Long l -> vm.mirrorOf(l);
            case Double d -> vm.mirrorOf(d);
            case Float f -> vm.mirrorOf(f);
            case Boolean b -> vm.mirrorOf(b);
            case Character c -> vm.mirrorOf(c);
            case String s -> vm.mirrorOf(s);
            default -> throw new IllegalArgumentException("literal não suportado: " + value);
        };
    }

    private Value binary(BinaryTree b, FrameRef frame) throws Exception {
        if (b.getKind() == Tree.Kind.CONDITIONAL_AND) return vm.mirrorOf(bool(eval(b.getLeftOperand(), frame).value()) && bool(eval(b.getRightOperand(), frame).value()));
        if (b.getKind() == Tree.Kind.CONDITIONAL_OR) return vm.mirrorOf(bool(eval(b.getLeftOperand(), frame).value()) || bool(eval(b.getRightOperand(), frame).value()));
        var left = unbox(eval(b.getLeftOperand(), frame).value(), frame.thread);
        var right = unbox(eval(b.getRightOperand(), frame).value(), frame.thread);
        var thread = frame.thread;
        if (b.getKind() == Tree.Kind.PLUS && (left instanceof StringReference || right instanceof StringReference)) {
            return vm.mirrorOf(text(left, thread) + text(right, thread));
        }
        if (b.getKind() == Tree.Kind.EQUAL_TO || b.getKind() == Tree.Kind.NOT_EQUAL_TO) {
            boolean same;
            if (left instanceof PrimitiveValue && right instanceof PrimitiveValue) same = number(left) == number(right);
            else same = left == null ? right == null : left.equals(right);
            return vm.mirrorOf(b.getKind() == Tree.Kind.EQUAL_TO == same);
        }
        var integral = isIntegral(left) && isIntegral(right);
        double x = number(left), y = number(right);
        return switch (b.getKind()) {
            case PLUS -> integral ? vm.mirrorOf((long) x + (long) y) : vm.mirrorOf(x + y);
            case MINUS -> integral ? vm.mirrorOf((long) x - (long) y) : vm.mirrorOf(x - y);
            case MULTIPLY -> integral ? vm.mirrorOf((long) x * (long) y) : vm.mirrorOf(x * y);
            case DIVIDE -> integral ? vm.mirrorOf((long) x / (long) y) : vm.mirrorOf(x / y);
            case REMAINDER -> integral ? vm.mirrorOf((long) x % (long) y) : vm.mirrorOf(x % y);
            case LESS_THAN -> vm.mirrorOf(x < y);
            case LESS_THAN_EQUAL -> vm.mirrorOf(x <= y);
            case GREATER_THAN -> vm.mirrorOf(x > y);
            case GREATER_THAN_EQUAL -> vm.mirrorOf(x >= y);
            default -> throw new IllegalArgumentException("operador não suportado: " + b.getKind());
        };
    }

    private String text(Value value, ThreadReference thread) throws Exception {
        if (value == null) return "null";
        if (value instanceof StringReference s) return s.value();
        if (value instanceof PrimitiveValue) return value.toString();
        return callToString((ObjectReference) value, thread);
    }

    private static boolean isIntegral(Value v) {
        return v instanceof IntegerValue || v instanceof LongValue || v instanceof ShortValue
                || v instanceof ByteValue || v instanceof CharValue;
    }

    private static double number(Value v) {
        if (v instanceof PrimitiveValue p && !(v instanceof BooleanValue)) return p.doubleValue();
        throw new IllegalArgumentException("não é número: " + v);
    }

    private static boolean bool(Value v) {
        if (v instanceof BooleanValue b) return b.value();
        throw new IllegalArgumentException("não é boolean: " + v);
    }

    private static ObjectReference requireObject(Value value, String expression) {
        if (value == null) throw new IllegalArgumentException(expression + " é null");
        if (!(value instanceof ObjectReference o)) throw new IllegalArgumentException(expression + " não é um objeto");
        return o;
    }

    // ---------------------------------------------------------------------------------- invocation

    private Value invoke(ObjectReference target, String name, List<Value> args, ThreadReference thread) throws Exception {
        var method = pick(target.referenceType().methodsByName(name), args, false, name);
        return target.invokeMethod(thread, method, convert(method, args, thread), ObjectReference.INVOKE_SINGLE_THREADED);
    }

    private Value invokeStatic(ReferenceType type, String name, List<Value> args, ThreadReference thread) throws Exception {
        var method = pick(type.methodsByName(name), args, true, name);
        if (type instanceof ClassType c) return c.invokeMethod(thread, method, convert(method, args, thread), ObjectReference.INVOKE_SINGLE_THREADED);
        if (type instanceof InterfaceType i) return i.invokeMethod(thread, method, convert(method, args, thread), ObjectReference.INVOKE_SINGLE_THREADED);
        throw new IllegalArgumentException("não dá para chamar " + name + " em " + type.name());
    }

    /** First non-abstract method with this arity whose parameters accept the argument values. */
    private static Method pick(List<Method> candidates, List<Value> args, boolean staticOnly, String name) throws Exception {
        Method fallback = null;
        for (var m : candidates) {
            if (staticOnly && !m.isStatic()) continue;
            if (m.isAbstract() || m.argumentTypeNames().size() != args.size()) continue;
            if (fallback == null) fallback = m;
            var names = m.argumentTypeNames();
            var ok = true;
            for (int i = 0; i < args.size() && ok; i++) ok = accepts(names.get(i), args.get(i));
            if (ok) return m;
        }
        if (fallback != null) return fallback;
        throw new IllegalArgumentException("método " + name + " com " + args.size() + " argumento(s) não encontrado");
    }

    private static boolean accepts(String parameterType, Value arg) {
        if (arg == null) return !isPrimitiveName(parameterType);
        var argType = arg.type().name();
        if (isPrimitiveName(parameterType)) return arg instanceof PrimitiveValue || BOXES.containsValue(argType);
        if (arg instanceof PrimitiveValue) return parameterType.equals(BOXES.get(argType)) || parameterType.equals("java.lang.Object");
        return true; // reference compatibility is checked by the VM on invocation
    }

    private static final Map<String, String> BOXES = Map.of(
            "int", "java.lang.Integer", "long", "java.lang.Long", "double", "java.lang.Double",
            "float", "java.lang.Float", "boolean", "java.lang.Boolean", "char", "java.lang.Character",
            "short", "java.lang.Short", "byte", "java.lang.Byte");

    private static boolean isPrimitiveName(String name) {
        return BOXES.containsKey(name);
    }

    /** Boxes, unboxes and widens arguments to the method's declared parameter types. */
    private List<Value> convert(Method method, List<Value> args, ThreadReference thread) throws Exception {
        var names = method.argumentTypeNames();
        var converted = new ArrayList<Value>();
        for (int i = 0; i < args.size(); i++) {
            var arg = args.get(i);
            var target = names.get(i);
            if (isPrimitiveName(target)) arg = primitive(unbox(arg, thread), target);
            else if (arg instanceof PrimitiveValue p) arg = box(p, thread);
            converted.add(arg);
        }
        return converted;
    }

    private Value unbox(Value value, ThreadReference thread) throws Exception {
        if (value instanceof ObjectReference o && BOXES.containsValue(o.referenceType().name())) {
            var field = o.referenceType().fieldByName("value");
            return o.getValue(field);
        }
        return value;
    }

    private Value primitive(Value value, String target) {
        if (!(value instanceof PrimitiveValue p)) throw new IllegalArgumentException("esperado " + target + ", veio " + value);
        return switch (target) {
            case "int" -> vm.mirrorOf(p.intValue());
            case "long" -> vm.mirrorOf(p.longValue());
            case "double" -> vm.mirrorOf(p.doubleValue());
            case "float" -> vm.mirrorOf(p.floatValue());
            case "short" -> vm.mirrorOf(p.shortValue());
            case "byte" -> vm.mirrorOf(p.byteValue());
            case "char" -> vm.mirrorOf(p.charValue());
            case "boolean" -> vm.mirrorOf(p.booleanValue());
            default -> value;
        };
    }

    private Value box(PrimitiveValue value, ThreadReference thread) throws Exception {
        var boxName = BOXES.get(value.type().name());
        var types = vm.classesByName(boxName);
        if (types.isEmpty()) throw new IllegalArgumentException(boxName + " não carregada na VM");
        var type = (ClassType) types.get(0);
        for (var m : type.methodsByName("valueOf")) {
            if (m.argumentTypeNames().size() == 1 && m.argumentTypeNames().get(0).equals(value.type().name())) {
                return type.invokeMethod(thread, m, List.of(value), ObjectReference.INVOKE_SINGLE_THREADED);
            }
        }
        throw new IllegalArgumentException("não foi possível converter " + value + " para " + boxName);
    }

    static String describe(Throwable e) {
        if (e instanceof InvocationException invocation) {
            return "o método lançou " + invocation.exception().referenceType().name();
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
