package org.javacs.debug;

import com.sun.jdi.*;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
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
            if (size >= 0) return simpleName(typeName) + " size=" + size;
        } catch (Exception e) {
            // fall through to the plain description
        }
        return simpleName(typeName) + " (id=" + object.uniqueID() + ")";
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
