package org.javacs.resolve;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.javacs.CompilerProvider;
import org.javacs.ParseTask;
import org.javacs.index.TypeIndexRouter;
import org.javacs.index.WorkspaceTypeIndex;
import org.javacs.completion.FindCompletionsAt;
import org.javacs.index.IndexedMember;
import org.javacs.index.IndexedType;
import org.javacs.lsp.CompletionItemKind;

/**
 * Parse-first type resolver used by fast providers that must stay on the parse/index boundary and
 * avoid a semantic compile on the hot path.
 *
 * <p>The resolution order is intentionally layered:
 *
 * <ol>
 *   <li>Lexical locals and parameters.
 *   <li>Enclosing fields and nested types.
 *   <li>Workspace and external index members/types.
 *   <li>Current-file parse-local inference only.
 *   <li>Invocation, lambda, SAM, and functional-target inference.
 *   <li>External reflective inference for dependency-only behavior.
 * </ol>
 */
public final class ParseTypeResolver {
    public record TypeResolution(
            String qualifiedType, boolean staticContext, boolean arrayType, List<String> typeArguments) {
        public TypeResolution(String qualifiedType, boolean staticContext, boolean arrayType) {
            this(qualifiedType, staticContext, arrayType, List.of());
        }

        public String firstTypeArgument() {
            return typeArguments.isEmpty() || typeArguments.get(0) == null || typeArguments.get(0).isBlank()
                    ? null : typeArguments.get(0);
        }

        public TypeResolution withFirstTypeArgument(String arg) {
            return new TypeResolution(qualifiedType, staticContext, arrayType,
                    arg == null ? List.of() : List.of(arg));
        }

        public TypeResolution withTypeArguments(List<String> args) {
            return new TypeResolution(qualifiedType, staticContext, arrayType,
                    args == null ? List.of() : List.copyOf(args));
        }
    }

    public record MethodReferenceTarget(TypeResolution receiverType, int argumentCount) {}

    private record VisibleVariable(VariableTree tree, TreePath path, int depth, long start) {}

    private record ScopeSnapshot(TreePath enclosingClassPath, java.util.List<VisibleVariable> visibleVariables) {}

    private static final int MAX_RESOLVE_DEPTH = 24;

    private final CompilationUnitTree root;
    private final SourcePositions positions;
    private final CompilerProvider compiler;
    private final TypeIndexRouter index;
    private final FunctionalTargetResolver functionalTargetResolver;
    private final long cursor;
    private final TreePath cursorPath;
    private ScopeSnapshot scopeSnapshot;
    private ClassLoader externalClassLoader;
    private TypeResolution thisType;
    private TypeResolution superType;

    public ParseTypeResolver(ParseTask parseTask, CompilerProvider compiler, TypeIndexRouter index, long cursor) {
        this.root = parseTask.root();
        this.positions = Trees.instance(parseTask.task()).getSourcePositions();
        this.compiler = compiler;
        this.index = index == null ? TypeIndexRouter.EMPTY : index;
        this.cursor = cursor;
        this.cursorPath = new FindCompletionsAt(parseTask.task()).scan(parseTask.root(), cursor);
        this.functionalTargetResolver =
                        new FunctionalTargetResolver(
                                root,
                                this.index,
                                new FunctionalTargetResolver.Support() {
                                    @Override
                                    public Optional<TypeResolution> resolveExpression(Tree expression, int depth) {
                                        return ParseTypeResolver.this.resolveExpressionAtDepth(expression, depth);
                                    }

                            @Override
                            public Optional<TypeResolution> resolveTypeTree(
                                    Tree tree, CompilationUnitTree sourceRoot, boolean staticContext) {
                                return ParseTypeResolver.this.resolveTypeTree(tree, sourceRoot, staticContext);
                            }

                            @Override
                            public Optional<TypeResolution> resolveMember(TypeResolution receiverType, String memberName) {
                                return ParseTypeResolver.this.resolveDirectMemberType(receiverType, memberName);
                            }

                            @Override
                            public Optional<TypeResolution> resolveInvocation(
                                    TypeResolution receiverType, String methodName, int argumentCount) {
                                return ParseTypeResolver.this.resolveUniqueInvocationReturnType(
                                        receiverType, methodName, argumentCount);
                            }

                            @Override
                            public Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType) {
                                return ParseTypeResolver.this.resolveSamParameterType(functionalType);
                            }

                            @Override
                            public Optional<Integer> resolveSamArity(TypeResolution functionalType) {
                                return ParseTypeResolver.this.resolveSamArity(functionalType);
                            }
                        });
    }

    // Public entrypoints.
    // Reflection over classpath jars can fail while linking: API stub jars without method bodies
    // (javaee-web-api 6.0 -> ClassFormatError on javax.persistence.*) or JDK modules missing from the
    // jls runtime (javax.naming -> NoClassDefFoundError). That is an Error, which used to escape the
    // request loop and kill the server; the expression is just unresolved.
    public Optional<TypeResolution> resolve(Tree expression, String fallbackIdentifier) {
        try {
            return resolveUnguarded(expression, fallbackIdentifier);
        } catch (TypeNotPresentException | LinkageError ex) {
            return Optional.empty();
        }
    }

    public Optional<TypeResolution> resolveExpression(Tree expression) {
        try {
            return resolveExpressionAtDepth(expression, 0);
        } catch (TypeNotPresentException | LinkageError ex) {
            return Optional.empty();
        }
    }

    private Optional<TypeResolution> resolveUnguarded(Tree expression, String fallbackIdentifier) {
        if (expression != null) {
            var direct = resolveExpressionAtDepth(expression, 0);
            if (direct.isPresent()) {
                return direct;
            }
        }
        if (fallbackIdentifier == null || fallbackIdentifier.isBlank()) {
            return Optional.empty();
        }
        if ("this".equals(fallbackIdentifier)) {
            return resolveThisType();
        }
        if ("super".equals(fallbackIdentifier)) {
            return resolveSuperType();
        }
        var variable = resolveVisibleVariable(fallbackIdentifier, 1);
        if (variable.isPresent()) {
            return variable;
        }
        var enclosingField = resolveEnclosingField(fallbackIdentifier);
        if (enclosingField.isPresent()) {
            return enclosingField;
        }
        var staticImportField = resolveStaticImportField(fallbackIdentifier);
        if (staticImportField.isPresent()) {
            return staticImportField;
        }
        return resolveIdentifier(fallbackIdentifier)
                .map(type -> new TypeResolution(type.qualifiedName, true, false));
    }

    public Optional<String> currentEnclosingTypeName() {
        return resolveThisType().map(TypeResolution::qualifiedType);
    }

    // Expression dispatch.
    private Optional<TypeResolution> resolveExpressionAtDepth(Tree expression, int depth) {
        if (expression == null || depth > MAX_RESOLVE_DEPTH) {
            return Optional.empty();
        }
        if (expression instanceof ParenthesizedTree parenthesized) {
            return resolveExpressionAtDepth(parenthesized.getExpression(), depth + 1);
        }
        if (expression instanceof IdentifierTree identifier) {
            var name = identifier.getName().toString();
            if ("this".equals(name)) {
                return resolveThisType();
            }
            if ("super".equals(name)) {
                return resolveSuperType();
            }
            var variable = resolveVisibleVariable(name, depth + 1);
            if (variable.isPresent()) {
                return variable;
            }
            var enclosingField = resolveEnclosingField(name);
            if (enclosingField.isPresent()) {
                return enclosingField;
            }
            var staticImportField = resolveStaticImportField(name);
            if (staticImportField.isPresent()) {
                return staticImportField;
            }
            return resolveIdentifier(name).map(type -> new TypeResolution(type.qualifiedName, true, false));
        }
        if (expression instanceof NewClassTree newClassTree) {
            return resolveTypeTree(newClassTree.getIdentifier(), root, false);
        }
        if (expression instanceof NewArrayTree newArrayTree) {
            if (newArrayTree.getType() == null) {
                return Optional.empty();
            }
            var component = resolveTypeTree(newArrayTree.getType(), root, false);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), false, true));
        }
        if (expression instanceof MethodInvocationTree invocationTree) {
            return resolveMethodInvocation(invocationTree, depth + 1);
        }
        if (expression instanceof MemberSelectTree memberSelectTree) {
            return resolveMemberSelect(memberSelectTree, depth + 1);
        }
        if (expression instanceof TypeCastTree castTree) {
            return resolveTypeTree(castTree.getType(), root, false);
        }
        if (expression instanceof ArrayAccessTree arrayAccessTree) {
            var array = resolveExpressionAtDepth(arrayAccessTree.getExpression(), depth + 1);
            if (array.isEmpty()) {
                return Optional.empty();
            }
            var type = array.get();
            if (!type.arrayType() || type.qualifiedType() == null || type.qualifiedType().isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new TypeResolution(type.qualifiedType(), false, false));
        }
        if (expression instanceof LiteralTree literal) {
            return literalType(literal);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodInvocation(MethodInvocationTree invocationTree, int depth) {
        var select = invocationTree.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            var thisType = resolveThisType();
            if (thisType.isEmpty()) {
                return Optional.empty();
            }
            var classLiteralBound = resolveClassLiteralTypeVarReturn(
                    thisType.get(), identifier.getName().toString(), false, invocationTree);
            if (classLiteralBound.isPresent()) {
                return classLiteralBound;
            }
            return resolveOverloadedReturnType(
                            thisType.get(), identifier.getName().toString(), false, invocationTree, depth)
                    .or(() -> resolveMethodReturnType(thisType.get(), identifier.getName().toString(), false));
        }
        if (select instanceof MemberSelectTree memberSelectTree) {
            var receiver = resolveExpressionAtDepth(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return Optional.empty();
            }
            // The steps below use reflection, which can fail to link a library class (a JDK module
            // missing from the jls runtime, like javax.naming for Hibernate's SessionFactory). They
            // are refinements: on failure fall through to the plain return type.
            var functional = guardReflection(() ->
                    functionalTargetResolver.resolveInvocationReturnType(
                            invocationTree, memberSelectTree, receiver.get(), depth + 1));
            if (functional.isPresent()) {
                return functional;
            }
            var classLiteralBound = guardReflection(() -> resolveClassLiteralTypeVarReturn(
                    receiver.get(),
                    memberSelectTree.getIdentifier().toString(),
                    receiver.get().staticContext(),
                    invocationTree));
            if (classLiteralBound.isPresent()) {
                return classLiteralBound;
            }
            var argumentBound = guardReflection(() -> resolveGenericMethodReturn(
                    receiver.get(), memberSelectTree.getIdentifier().toString(), invocationTree, depth + 1));
            if (argumentBound.isPresent()) {
                return argumentBound;
            }
            var methodName = memberSelectTree.getIdentifier().toString();
            return resolveOverloadedReturnType(
                            receiver.get(), methodName, receiver.get().staticContext(), invocationTree, depth)
                    .or(() -> resolveMethodReturnType(receiver.get(), methodName, receiver.get().staticContext()));
        }
        return Optional.empty();
    }

    /**
     * Return type of an overloaded method, picking the overload by argument count and by the
     * argument types that can be resolved. {@code rawMember} by name returns the first overload,
     * which can be the wrong one: Hibernate's {@code Session.createQuery} has (String), (String, Class),
     * (CriteriaQuery), (CriteriaUpdate), (CriteriaDelete) plus {@code EntityManager.createQuery(String)}
     * with another return type, so {@code session.createQuery(hql).} completed nothing.
     * Among covariant returns of the same signature the most specific one wins.
     *
     * <p>Returns empty when there is at most one overload with that arity (the normal path handles
     * it) or when the remaining candidates return unrelated types.
     */
    private Optional<TypeResolution> resolveOverloadedReturnType(
            TypeResolution receiver,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int depth) {
        if (receiver == null
                || receiver.qualifiedType() == null
                || receiver.qualifiedType().isBlank()
                || methodName == null
                || invocation == null
                || depth > MAX_RESOLVE_DEPTH) {
            return Optional.empty();
        }
        try {
            var args = invocation.getArguments();
            var candidates = new ArrayList<IndexedMember>();
            for (var member : index.members(receiver.qualifiedType(), staticContext)) {
                if (member.kind != CompletionItemKind.Method || !methodName.equals(member.name)) {
                    continue;
                }
                var count = member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length;
                if (count == args.size()) {
                    candidates.add(member);
                }
            }
            var external = canUseExternalInference(receiver.qualifiedType());
            if (candidates.isEmpty() || (candidates.size() == 1 && !external)) {
                return Optional.empty();
            }
            var argTypes = new ArrayList<String>();
            for (var arg : args) {
                var resolved = resolveExpressionAtDepth(arg, depth + 1);
                argTypes.add(
                        resolved.isPresent() && !resolved.get().staticContext() && !resolved.get().arrayType()
                                ? resolved.get().qualifiedType()
                                : null);
            }
            TypeResolution best = null;
            Class<?> bestClass = null;
            for (var member : candidates) {
                var fits = true;
                for (int i = 0; i < args.size() && fits; i++) {
                    fits = argumentFits(argTypes.get(i), member.erasedParameterTypes[i]);
                }
                if (!fits) {
                    continue;
                }
                var type = overloadReturnType(receiver, member);
                if (type.isEmpty()) {
                    continue;
                }
                if (best == null) {
                    best = type.get();
                    var bestName = best.qualifiedType();
                    bestClass = guardReflection(() -> loadExternalClass(bestName)).orElse(null);
                    continue;
                }
                if (Objects.equals(best.qualifiedType(), type.get().qualifiedType())) {
                    continue;
                }
                var typeName = type.get().qualifiedType();
                var typeClass = guardReflection(() -> loadExternalClass(typeName)).orElse(null);
                if (typeClass == null || bestClass == null) {
                    return Optional.empty();
                }
                if (bestClass.isAssignableFrom(typeClass)) {
                    best = type.get();
                    bestClass = typeClass;
                } else if (!typeClass.isAssignableFrom(bestClass)) {
                    return Optional.empty();
                }
            }
            return Optional.ofNullable(best);
        } catch (LinkageError | TypeNotPresentException | SecurityException e) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> guardReflection(java.util.function.Supplier<Optional<T>> step) {
        try {
            return step.get();
        } catch (TypeNotPresentException | LinkageError | SecurityException e) {
            return Optional.empty();
        }
    }

    /** Argument of type {@code argumentType} (null = unknown) can be passed to {@code parameterType}? */
    private boolean argumentFits(String argumentType, String parameterType) {
        if (argumentType == null || parameterType == null) {
            return true;
        }
        var arg = TypeNames.normalize(argumentType);
        var param = TypeNames.normalize(parameterType);
        if (arg.equals(param) || param.equals("java.lang.Object")) {
            return true;
        }
        if (TypeNames.isPrimitive(arg) || TypeNames.isPrimitive(param)) {
            return true; // boxing/widening: don't filter
        }
        try {
            var paramClass = loadExternalClass(param);
            var argClass = loadExternalClass(arg);
            if (paramClass.isPresent() && argClass.isPresent()) {
                return paramClass.get().isAssignableFrom(argClass.get());
            }
        } catch (TypeNotPresentException | LinkageError | SecurityException e) {
            // can't tell: keep the overload
        }
        return true;
    }

    /**
     * Return type of one indexed overload. For library methods it is read by reflection from the
     * receiver class, taking the most specific return among same-signature methods (covariant
     * overrides inherited from several interfaces).
     */
    private Optional<TypeResolution> overloadReturnType(TypeResolution receiver, IndexedMember member) {
        if (member.provenance == IndexedMember.Provenance.WORKSPACE || isWorkspaceType(member.ownerType)) {
            return returnTypeOf(member);
        }
        var reflective = guardReflection(() -> reflectiveOverloadReturnType(receiver, member));
        return reflective.isPresent() ? reflective : returnTypeOf(member);
    }

    private Optional<TypeResolution> reflectiveOverloadReturnType(TypeResolution receiver, IndexedMember member) {
        var owner = loadExternalClass(receiver.qualifiedType()).or(() -> loadExternalClass(member.ownerType));
        if (owner.isPresent()) {
            Method best = null;
            for (var method : owner.get().getMethods()) {
                if (!method.getName().equals(member.name)
                        || member.isStatic != java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != member.erasedParameterTypes.length) {
                    continue;
                }
                var types = method.getParameterTypes();
                var same = true;
                for (int i = 0; i < types.length && same; i++) {
                    same = types[i].getTypeName().equals(member.erasedParameterTypes[i]);
                }
                if (same && (best == null || best.getReturnType().isAssignableFrom(method.getReturnType()))) {
                    best = method;
                }
            }
            if (best != null) {
                var bindings = bindingsForMethodOwner(receiver, best.getDeclaringClass());
                return resolveReflectiveType(best.getGenericReturnType(), bindings);
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMemberSelect(MemberSelectTree memberSelectTree, int depth) {
        var receiver = resolveExpressionAtDepth(memberSelectTree.getExpression(), depth + 1);
        if (receiver.isEmpty()) {
            return resolveTypeTree(memberSelectTree, root, true);
        }
        return resolveDirectMemberType(receiver.get(), memberSelectTree.getIdentifier().toString());
    }

    private Optional<IndexedType> resolveIdentifier(String identifier) {
        var nested = resolveNestedTypeInEnclosingScopes(identifier);
        if (nested.isPresent()) {
            return index.typeInfo(nested.get());
        }
        var sourceType = resolveTypeNameInSource(identifier, root);
        return sourceType.flatMap(index::typeInfo).or(() -> index.resolveType(identifier, root));
    }

    private Optional<TypeResolution> resolveEnclosingField(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        var current = resolveThisType();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        var field = resolveIndexedMember(current.get().qualifiedType(), identifier, false, null);
        if (field.isEmpty() || field.get().kind != CompletionItemKind.Field) {
            // Also try static context — interface constants are implicitly static.
            field = resolveIndexedMember(current.get().qualifiedType(), identifier, true, null);
        }
        if (field.isEmpty() || field.get().kind != CompletionItemKind.Field) {
            return Optional.empty();
        }
        return returnTypeOf(field.get());
    }

    private Optional<TypeResolution> resolveStaticImportField(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        var owners = WorkspaceTypeIndex.staticImportOwnerTypes(identifier, root);
        for (var owner : owners) {
            var field = resolveIndexedMember(owner, identifier, true, null);
            if (field.isPresent() && field.get().kind == CompletionItemKind.Field) {
                return returnTypeOf(field.get());
            }
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodReturnType(
            TypeResolution receiverType, String methodName, boolean staticContext) {
        if (receiverType == null
                || receiverType.qualifiedType() == null
                || receiverType.qualifiedType().isBlank()
                || methodName == null
                || methodName.isBlank()) {
            return Optional.empty();
        }
        var member = resolveIndexedMember(receiverType.qualifiedType(), methodName, staticContext, null);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        if (member.get().provenance == IndexedMember.Provenance.WORKSPACE) {
            return returnTypeOf(member.get());
        }
        return resolveExternalMethodReturnType(receiverType, member.get()).or(() -> returnTypeOf(member.get()));
    }

    /**
     * Return type of a library method whose own type variables are bound by its arguments.
     *
     * <p>Example: {@code List.of(customer)} is {@code <E> List<E> of(E e1)}, so it returns
     * {@code List<Customer>}; {@code Arrays.asList(array)} binds {@code T} to the component type.
     */
    private Optional<TypeResolution> resolveGenericMethodReturn(
            TypeResolution receiver, String methodName, MethodInvocationTree invocation, int depth) {
        if (receiver == null || !canUseExternalInference(receiver.qualifiedType()) || depth > MAX_RESOLVE_DEPTH) {
            return Optional.empty();
        }
        var args = invocation.getArguments();
        var method = reflectiveMethodForArguments(receiver.qualifiedType(), methodName, receiver.staticContext(), args.size());
        if (method.isEmpty() || method.get().getTypeParameters().length == 0) {
            return Optional.empty();
        }
        var methodVars = java.util.Set.of(method.get().getTypeParameters());
        var bindings = new HashMap<>(bindingsForMethodOwner(receiver, method.get().getDeclaringClass()));
        var params = method.get().getGenericParameterTypes();
        var bound = false;
        for (int i = 0; i < args.size() && params.length > 0; i++) {
            var last = params.length - 1;
            var param = params[Math.min(i, last)];
            var argument = resolveExpressionAtDepth(args.get(i), depth + 1);
            if (argument.isEmpty() || argument.get().staticContext()) {
                continue;
            }
            var type = argument.get();
            if (method.get().isVarArgs() && i >= last && param instanceof GenericArrayType array) {
                // T... called with elements binds T to an element; called with an array, to its component
                param = array.getGenericComponentType();
                if (type.arrayType()) type = new TypeResolution(type.qualifiedType(), false, false);
            }
            if (param instanceof java.lang.reflect.TypeVariable<?> variable
                    && methodVars.contains(variable)
                    && !bindings.containsKey(variable)
                    && !type.arrayType()) {
                bindings.put(variable, type);
                bound = true;
            }
        }
        if (!bound) {
            return Optional.empty();
        }
        return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
    }

    /** Overload for a call with {@code argumentCount} arguments: exact arity first, then varargs. */
    private Optional<Method> reflectiveMethodForArguments(
            String ownerType, String methodName, boolean staticContext, int argumentCount) {
        var rawClass = loadExternalClass(ownerType);
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        Method exact = null;
        Method varargs = null;
        for (var method : rawClass.get().getMethods()) {
            if (!method.getName().equals(methodName)
                    || staticContext != java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getParameterCount() == argumentCount && !method.isVarArgs()) {
                if (exact != null && !sameReflectiveSignature(exact, method)) return Optional.empty();
                exact = method;
            } else if (method.isVarArgs() && argumentCount >= method.getParameterCount() - 1) {
                if (varargs != null && !sameReflectiveSignature(varargs, method)) varargs = null;
                else varargs = method;
            }
        }
        return Optional.ofNullable(exact != null ? exact : varargs);
    }

    private Optional<IndexedMember> resolveIndexedMember(
            String ownerType, String memberName, boolean staticContext, String[] erasedParameterTypes) {
        if (index.isWorkspaceOwnedType(ownerType)) {
            return erasedParameterTypes == null
                    ? index.workspace().member(ownerType, memberName, staticContext)
                    : index.workspace().member(ownerType, memberName, staticContext, erasedParameterTypes);
        }
        return erasedParameterTypes == null
                ? index.external().rawMember(ownerType, memberName, staticContext)
                : index.external().rawMember(ownerType, memberName, staticContext, erasedParameterTypes);
    }

    /**
     * Infer return type for methods where the return type is a method-level type variable bound by
     * a {@code Class<T>} argument at the call site.
     *
     * <p>Example: {@code <T> T readValue(String content, Class<T> valueType)} — called as
     * {@code mapper.readValue(json, Foo.class)} resolves to {@code com.example.Foo}.
     *
     * <p>Uses indexed generic signatures first and a reflective fallback for dependency classes
     * when the index cannot disambiguate an overload.
     */
    private Optional<TypeResolution> resolveClassLiteralTypeVarReturn(
            TypeResolution receiverType, String methodName, boolean staticContext,
            MethodInvocationTree invocation) {
        if (receiverType == null
                || receiverType.qualifiedType() == null
                || receiverType.qualifiedType().isBlank()
                || methodName == null
                || methodName.isBlank()) {
            return Optional.empty();
        }
        TypeResolution match = null;
        for (var member : index.members(receiverType.qualifiedType(), staticContext)) {
            if (member.kind != CompletionItemKind.Method
                    || !methodName.equals(member.name)
                    || member.declaredReturnType == null
                    || member.declaredReturnType.isBlank()
                    || member.declaredParameterTypes == null) {
                continue;
            }
            var arity = member.declaredParameterTypes.length;
            if (arity != invocation.getArguments().size()) {
                continue;
            }
            var bound = bindClassLiteralTypeVarReturn(member.declaredReturnType, member.declaredParameterTypes, invocation);
            if (bound.isEmpty()) {
                continue;
            }
            if (match != null && !Objects.equals(typeName(match), typeName(bound.get()))) {
                return Optional.empty();
            }
            match = bound.get();
        }
        return Optional.ofNullable(match);
    }

    private Optional<TypeResolution> bindClassLiteralTypeVarReturn(
            String declaredReturn, String[] declaredParameterTypes, MethodInvocationTree invocation) {
        if (!isLikelyMethodTypeVar(declaredReturn) || declaredParameterTypes == null) {
            return Optional.empty();
        }
        var args = invocation.getArguments();
        for (int i = 0; i < declaredParameterTypes.length && i < args.size(); i++) {
            if (!isClassLiteralParamForTypeVar(declaredParameterTypes[i], declaredReturn)) {
                continue;
            }
            var arg = args.get(i);
            if (!(arg instanceof MemberSelectTree classLiteral)) {
                continue;
            }
            if (!"class".equals(classLiteral.getIdentifier().toString())) {
                continue;
            }
            var qualifier = resolveTypeTree(classLiteral.getExpression(), root, false);
            if (qualifier.isPresent()) {
                return qualifier;
            }
        }
        return Optional.empty();
    }

    /**
     * Return true if {@code typeName} looks like a method-level type variable: a bare identifier
     * with no dots, angle brackets, array brackets, or spaces.
     *
     * <p>Type variables by convention are short uppercase tokens. Qualified class names always
     * contain a dot in the index, so false positives are negligible.
     */
    private static boolean isLikelyMethodTypeVar(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return false;
        }
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
            if (c == '.' || c == '<' || c == '[' || c == ' ') {
                return false;
            }
        }
        return true;
    }

    /**
     * Return true if {@code paramType} is {@code java.lang.Class<VAR>} or {@code Class<VAR>} where
     * {@code VAR} equals {@code expectedTypeVar}.
     */
    private static boolean isClassLiteralParamForTypeVar(String paramType, String expectedTypeVar) {
        if (paramType == null || expectedTypeVar == null) {
            return false;
        }
        var start = paramType.indexOf('<');
        var end = paramType.lastIndexOf('>');
        if (start < 0 || end <= start) {
            return false;
        }
        var prefix = paramType.substring(0, start);
        if (!"java.lang.Class".equals(prefix) && !"Class".equals(prefix)) {
            return false;
        }
        var typeArg = paramType.substring(start + 1, end).trim();
        while (typeArg.startsWith("? extends ")) {
            typeArg = typeArg.substring("? extends ".length()).trim();
        }
        while (typeArg.startsWith("? super ")) {
            typeArg = typeArg.substring("? super ".length()).trim();
        }
        return typeArg.equals(expectedTypeVar);
    }

    // Current-file and enclosing-scope fallback.
    private Optional<String> resolveNestedTypeInEnclosingScopes(String simpleName) {
        for (var classPath = enclosingClassPath();
                classPath != null;
                classPath = parentClassPath(classPath.getParentPath())) {
            var owner = qualifiedClassName(root, classPath);
            var candidate = owner + "." + simpleName;
            if (index.workspace().containsType(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private TreePath parentClassPath(TreePath from) {
        for (var cursor = from; cursor != null; cursor = cursor.getParentPath()) {
            if (cursor.getLeaf() instanceof ClassTree) {
                return cursor;
            }
        }
        return null;
    }

    // Lexical scope resolution.
    private Optional<TypeResolution> resolveVisibleVariable(String targetName, int depth) {
        var best = findVisibleVariable(targetName);
        if (best.isEmpty()) {
            return Optional.empty();
        }
        var variableTree = best.get().tree();
        if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
            return resolveDeclaredTypeName(variableTree.getType().toString());
        }
        return resolveVariableType(variableTree, best.get().path(), depth + 1);
    }

    private Optional<TypeResolution> resolveVariableType(VariableTree variableTree, TreePath path, int depth) {
        if (variableTree.getType() != null && !"var".equals(variableTree.getType().toString())) {
            return resolveTypeTree(variableTree.getType(), root, false);
        }
        var enhancedFor = resolveEnhancedForVariableType(variableTree, path, depth + 1);
        if (enhancedFor.isPresent()) {
            return enhancedFor;
        }
        var lambda = resolveLambdaParameterType(variableTree, path, depth + 1);
        if (lambda.isPresent()) {
            return lambda;
        }
        if (variableTree.getInitializer() != null) {
            return resolveExpressionAtDepth(variableTree.getInitializer(), depth + 1);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveEnhancedForVariableType(
            VariableTree variableTree, TreePath path, int depth) {
        var parent = path == null ? null : path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof EnhancedForLoopTree loop) || loop.getVariable() != variableTree) {
            return Optional.empty();
        }
        var iterableType = resolveExpressionAtDepth(loop.getExpression(), depth + 1);
        if (iterableType.isEmpty()) {
            return Optional.empty();
        }
        if (iterableType.get().arrayType()) {
            return Optional.of(new TypeResolution(iterableType.get().qualifiedType(), false, false));
        }
        if (iterableType.get().firstTypeArgument() == null || iterableType.get().firstTypeArgument().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new TypeResolution(iterableType.get().firstTypeArgument(), false, false));
    }

    /**
     * Infer the type of a lambda parameter without semantic attribution.
     *
     * <p>Example: in {@code items.stream().map(item -> item.getFamily())}, the parameter
     * {@code item} gets its type from the stream element. For non-stream cases, this method falls
     * back to SAM parameter lookup from indexed method signatures.
     */
    private Optional<TypeResolution> resolveLambdaParameterType(
            VariableTree variableTree, TreePath path, int depth) {
        var parent = path == null ? null : path.getParentPath();
        if (parent == null || !(parent.getLeaf() instanceof LambdaExpressionTree lambda)) {
            return Optional.empty();
        }
        var parameterIndex = -1;
        for (int i = 0; i < lambda.getParameters().size(); i++) {
            if (lambda.getParameters().get(i) == variableTree) parameterIndex = i;
        }
        if (parameterIndex < 0) {
            return Optional.empty();
        }
        var arity = lambda.getParameters().size();
        var invocationPath = parent.getParentPath();
        if (invocationPath == null || !(invocationPath.getLeaf() instanceof MethodInvocationTree invocation)) {
            return Optional.empty();
        }
        var argumentIndex = -1;
        for (int i = 0; i < invocation.getArguments().size(); i++) {
            if (invocation.getArguments().get(i) == lambda) {
                argumentIndex = i;
                break;
            }
        }
        if (argumentIndex < 0) {
            return Optional.empty();
        }
        if (arity == 1) {
            var streamParameterType =
                    functionalTargetResolver.resolveLambdaParameterType(
                            lambda, parent, invocation, argumentIndex, depth + 1);
            if (streamParameterType.isPresent()) {
                return streamParameterType;
            }
        }
        var functionalType = resolveInvocationArgumentType(invocation, argumentIndex, depth + 1);
        var index = parameterIndex;
        return functionalType.flatMap(type -> resolveSamParameterType(type, index, arity));
    }

    /** Resolve a source type tree into a qualified type before any declaration lookup begins. */
    private Optional<TypeResolution> resolveTypeTree(Tree tree, CompilationUnitTree sourceRoot, boolean staticContext) {
        if (tree == null) {
            return Optional.empty();
        }
        if (tree instanceof AnnotatedTypeTree annotatedTypeTree) {
            return resolveTypeTree(annotatedTypeTree.getUnderlyingType(), sourceRoot, staticContext);
        }
        if (tree instanceof ParameterizedTypeTree parameterizedTypeTree) {
            var raw = resolveTypeTree(parameterizedTypeTree.getType(), sourceRoot, staticContext);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            // Every type argument, by position ("" when unresolved): Map<K, V>.get/values need V.
            var arguments = new ArrayList<String>();
            for (var argument : parameterizedTypeTree.getTypeArguments()) {
                arguments.add(typeArgumentName(argument, sourceRoot).orElse(""));
            }
            return Optional.of(raw.get().withTypeArguments(
                    arguments.stream().allMatch(String::isBlank) ? List.of() : arguments));
        }
        if (tree instanceof ArrayTypeTree arrayTypeTree) {
            var component = resolveTypeTree(arrayTypeTree.getType(), sourceRoot, staticContext);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), staticContext, true));
        }
        var typeName = tree.toString();
        switch (typeName) {
            case "boolean", "byte", "char", "double", "float", "int", "long", "short", "void" ->
                    {
                        return Optional.of(new TypeResolution(typeName, staticContext, false));
                    }
            default -> {}
        }
        var resolved =
                sourceRoot == root
                        ? resolveTypeNameInSource(typeName, sourceRoot)
                                .or(
                                        () ->
                                                index.resolveType(typeName, sourceRoot)
                                                        .map(indexed -> indexed.qualifiedName))
                        : index.resolveType(typeName, sourceRoot).map(indexed -> indexed.qualifiedName);
        if (resolved.isEmpty() && sourceRoot != root) {
            resolved = resolveTypeNameInSource(typeName, sourceRoot);
        }
        return resolved.map(type -> new TypeResolution(type, staticContext, false));
    }

    /** A type argument's name; a bounded wildcard ({@code ? extends Foo}) stands for its bound. */
    private Optional<String> typeArgumentName(Tree argument, CompilationUnitTree sourceRoot) {
        if (argument instanceof com.sun.source.tree.WildcardTree wildcard) {
            return wildcard.getBound() == null
                    ? Optional.empty()
                    : resolveTypeTree(wildcard.getBound(), sourceRoot, false).map(ParseTypeResolver::typeName);
        }
        return resolveTypeTree(argument, sourceRoot, false).map(ParseTypeResolver::typeName);
    }

    private Optional<String> firstTypeArgument(ParameterizedTypeTree parameterizedTypeTree, CompilationUnitTree sourceRoot) {
        if (parameterizedTypeTree.getTypeArguments().isEmpty()) {
            return Optional.empty();
        }
        var resolved = resolveTypeTree(parameterizedTypeTree.getTypeArguments().get(0), sourceRoot, false);
        return resolved.map(ParseTypeResolver::typeName);
    }

    private Optional<TypeResolution> resolveThisType() {
        if (thisType != null) {
            return Optional.of(thisType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) {
            return Optional.empty();
        }
        var qualified = qualifiedClassName(root, classPath);
        thisType = new TypeResolution(qualified, false, false);
        return Optional.of(thisType);
    }

    private Optional<TypeResolution> resolveSuperType() {
        if (superType != null) {
            return Optional.of(superType);
        }
        var classPath = enclosingClassPath();
        if (classPath == null) {
            return Optional.empty();
        }
        var classTree = (ClassTree) classPath.getLeaf();
        if (classTree.getExtendsClause() == null) {
            superType = new TypeResolution("java.lang.Object", false, false);
            return Optional.of(superType);
        }
        var resolved = resolveTypeTree(classTree.getExtendsClause(), root, false);
        resolved.ifPresent(typeResolution -> superType = typeResolution);
        return resolved;
    }

    // Workspace member/type resolution.
    private Optional<TypeResolution> returnTypeOf(IndexedMember member) {
        if (member == null) {
            return Optional.empty();
        }
        var typeName = member.declaredReturnType != null && !member.declaredReturnType.isBlank()
                ? member.declaredReturnType : member.returnType;
        // For workspace members with simple (non-qualified) return type names, resolve against the
        // declaring type's source imports first. This avoids the caller's wildcard imports shadowing
        // the correct type (e.g. caller imports com.foo.model.* which contains a same-named class).
        if (member.provenance == IndexedMember.Provenance.WORKSPACE
                && typeName != null && !typeName.isBlank()
                && !TypeNames.normalize(typeName).contains(".")) {
            var ownerResolved = resolveViaOwnerSource(member);
            if (ownerResolved.isPresent()) {
                return ownerResolved;
            }
        }
        if (member.declaredReturnType != null && !member.declaredReturnType.isBlank()) {
            var declared = resolveDeclaredTypeName(member.declaredReturnType);
            if (declared.isPresent()) {
                return declared;
            }
        }
        var result = resolveDeclaredTypeName(member.returnType);
        if (result.isPresent()) {
            return result;
        }
        // Fallback: resolve using the declaring type's source imports when the current file
        // doesn't import the return type. This handles parse-tree-indexed simple names.
        // TODO: fix root cause in addParseTreeMethod by qualifying return types at index time.
        return resolveViaOwnerSource(member);
    }

    private Optional<TypeResolution> resolveViaOwnerSource(IndexedMember member) {
        // Only useful for workspace members — external members have qualified return types already.
        if (member.provenance == IndexedMember.Provenance.EXTERNAL_BINARY) {
            return Optional.empty();
        }
        if (member.ownerType == null || member.ownerType.isBlank() || compiler == null) {
            return Optional.empty();
        }
        // Workaround for parse-tree-indexed members (addParseTreeMethod) that store simple
        // return type names (raw AST tokens) instead of qualified names. The compiled index
        // path stores qualified names and never reaches here. This fallback resolves the
        // simple name against the owner's import scope instead of the caller's.
        var source = compiler.findAnywhere(member.ownerType);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        var ownerRoot = compiler.parse(source.get()).root();
        if (ownerRoot == null) {
            return Optional.empty();
        }
        var typeName = member.declaredReturnType != null && !member.declaredReturnType.isBlank()
                ? member.declaredReturnType : member.returnType;
        var normalized = TypeNames.normalize(typeName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        var resolved = index.resolveType(normalized, ownerRoot).map(indexed -> indexed.qualifiedName);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var array = typeName.endsWith("[]");
        var qualified = resolved.get();
        if (array && qualified.endsWith("[]")) {
            qualified = qualified.substring(0, qualified.length() - 2);
        }
        // Resolve the type arguments in the owner's scope so generic return types
        // (e.g. Optional<ModuleInfo>, Map<String, Foo>) propagate them correctly.
        var arguments = typeArgumentsFromTypeName(typeName, arg -> {
            var argNormalized = TypeNames.normalize(arg);
            return argNormalized.isEmpty()
                    ? Optional.empty()
                    : index.resolveType(argNormalized, ownerRoot).map(indexed -> indexed.qualifiedName);
        });
        return Optional.of(
                new TypeResolution(qualified, false, array)
                        .withTypeArguments(arguments));
    }

    private Optional<TypeResolution> resolveDirectMemberType(TypeResolution receiverType, String memberName) {
        if (receiverType == null || memberName == null || memberName.isBlank()) {
            return Optional.empty();
        }
        if (receiverType.arrayType() && "length".equals(memberName)) {
            return Optional.of(new TypeResolution("int", false, false));
        }
        if (receiverType.staticContext() && "class".equals(memberName)) {
            return Optional.of(new TypeResolution("java.lang.Class", false, false));
        }
        if (receiverType.staticContext()) {
            var nestedType =
                    index.workspace()
                            .typeInfo(receiverType.qualifiedType())
                            .flatMap(
                                    info ->
                                            info.nestedTypes.stream()
                                                    .filter(candidate -> candidate.endsWith("." + memberName))
                                                    .findFirst());
            if (nestedType.isPresent()) {
                return Optional.of(new TypeResolution(nestedType.get(), true, false));
            }
        }
        var member =
                resolveIndexedMember(
                        receiverType.qualifiedType(),
                        memberName,
                        receiverType.staticContext(),
                        null);
        if (member.isEmpty()) {
            return Optional.empty();
        }
        return returnTypeOf(member.get());
    }

    // Invocation, lambda, and SAM inference.
    private Optional<TypeResolution> resolveUniqueInvocationReturnType(
            TypeResolution receiverType, String methodName, int argumentCount) {
        if (receiverType == null
                || receiverType.qualifiedType() == null
                || receiverType.qualifiedType().isBlank()
                || methodName == null
                || methodName.isBlank()
                || argumentCount < 0) {
            return Optional.empty();
        }
        var indexed = uniqueIndexedMethod(receiverType, methodName, argumentCount);
        if (indexed.isPresent()) {
            if (index.isWorkspaceOwnedType(indexed.get().ownerType)) {
                return returnTypeOf(indexed.get());
            }
            return resolveExternalMethodReturnType(receiverType, indexed.get())
                    .or(() -> returnTypeOf(indexed.get()));
        }
        if (!canUseExternalInference(receiverType.qualifiedType())) {
            return Optional.empty();
        }
        return resolveReflectiveInvocationReturnType(receiverType, methodName, argumentCount);
    }

    private Optional<IndexedMember> uniqueIndexedMethod(
            TypeResolution receiverType, String methodName, int argumentCount) {
        IndexedMember match = null;
        for (var member : index.members(receiverType.qualifiedType(), receiverType.staticContext())) {
            if (member.kind != CompletionItemKind.Method
                    || !Objects.equals(member.name, methodName)
                    || member.isStatic != receiverType.staticContext()) {
                continue;
            }
            var arity = member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length;
            if (arity != argumentCount) {
                continue;
            }
            if (match != null && !Objects.equals(match.canonicalKey, member.canonicalKey)) {
                return Optional.empty();
            }
            match = member;
        }
        return Optional.ofNullable(match);
    }

    private Optional<TypeResolution> resolveInvocationArgumentType(
            MethodInvocationTree invocation, int argumentIndex, int depth) {
        var select = invocation.getMethodSelect();
        if (select instanceof IdentifierTree identifier) {
            var current = resolveThisType();
            if (current.isEmpty()) {
                return Optional.empty();
            }
            return resolveMethodArgumentType(
                    current.get(), current.get().qualifiedType(), identifier.getName().toString(), false, invocation, argumentIndex);
        }
        if (select instanceof MemberSelectTree memberSelectTree) {
            var receiver = resolveExpressionAtDepth(memberSelectTree.getExpression(), depth + 1);
            if (receiver.isEmpty()) {
                return Optional.empty();
            }
            return resolveMethodArgumentType(
                    receiver.get(),
                    receiver.get().qualifiedType(),
                    memberSelectTree.getIdentifier().toString(),
                    receiver.get().staticContext(),
                    invocation,
                    argumentIndex);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveMethodArgumentType(
            TypeResolution receiverType,
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        // Library methods: reflection binds the receiver's type arguments (forEach(Consumer<? super T>)
        // on a List<Customer> -> Consumer<Customer>); the index keeps the declared, unbound T.
        if (canUseExternalInference(ownerType)) {
            var external = resolveExternalMethodArgumentType(
                    receiverType, ownerType, methodName, staticContext, invocation, argumentIndex);
            if (external.isPresent()) {
                return external;
            }
        }
        return resolveIndexedMethodArgumentType(ownerType, methodName, staticContext, invocation, argumentIndex);
    }

    private Optional<TypeResolution> resolveIndexedMethodArgumentType(
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        IndexedMember match = null;
        for (var member : index.members(ownerType, staticContext)) {
            if (member.kind != CompletionItemKind.Method) {
                continue;
            }
            if (!methodName.equals(member.name)) {
                continue;
            }
            var arity = member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length;
            if (arity != invocation.getArguments().size()) {
                continue;
            }
            if (match != null && !Objects.equals(match.canonicalKey, member.canonicalKey)) {
                return Optional.empty();
            }
            match = member;
        }
        if (match == null) {
            return Optional.empty();
        }
        if (match.declaredParameterTypes != null
                && argumentIndex >= 0
                && argumentIndex < match.declaredParameterTypes.length) {
            return resolveDeclaredTypeName(match.declaredParameterTypes[argumentIndex]);
        }
        if (match.erasedParameterTypes != null
                && argumentIndex >= 0
                && argumentIndex < match.erasedParameterTypes.length) {
            return resolveDeclaredTypeName(match.erasedParameterTypes[argumentIndex]);
        }
        return Optional.empty();
    }

    private Optional<TypeResolution> resolveExternalMethodArgumentType(
            TypeResolution receiverType,
            String ownerType,
            String methodName,
            boolean staticContext,
            MethodInvocationTree invocation,
            int argumentIndex) {
        var method = resolveReflectiveMethod(ownerType, methodName, staticContext, invocation.getArguments().size());
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        var parameterTypes = method.get().getGenericParameterTypes();
        if (argumentIndex < 0 || argumentIndex >= parameterTypes.length) {
            return Optional.empty();
        }
        return resolveReflectiveType(parameterTypes[argumentIndex], bindings);
    }

    private Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType) {
        return resolveSamParameterType(functionalType, 0, 1);
    }

    /** Type of parameter {@code index} of the functional interface's method, which takes {@code arity}. */
    private Optional<TypeResolution> resolveSamParameterType(TypeResolution functionalType, int index, int arity) {
        if (functionalType == null || functionalType.qualifiedType() == null || functionalType.qualifiedType().isBlank()) {
            return Optional.empty();
        }
        if (isWorkspaceType(functionalType.qualifiedType())) {
            return resolveIndexedSamParameterType(functionalType, index, arity);
        }
        return resolveExternalSamParameterType(functionalType, index, arity);
    }

    private Optional<Integer> resolveSamArity(TypeResolution functionalType) {
        if (functionalType == null || functionalType.qualifiedType() == null || functionalType.qualifiedType().isBlank()) {
            return Optional.empty();
        }
        if (isWorkspaceType(functionalType.qualifiedType())) {
            var sam = indexedSam(functionalType.qualifiedType());
            if (sam.isEmpty()) return Optional.empty();
            return Optional.of(sam.get().declaredParameterTypes == null ? 0 : sam.get().declaredParameterTypes.length);
        }
        var rawClass = loadExternalClass(functionalType.qualifiedType());
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        return singleAbstractMethod(rawClass.get()).map(Method::getParameterCount);
    }

    private Optional<TypeResolution> resolveIndexedSamParameterType(TypeResolution functionalType, int index, int arity) {
        var sam = indexedSam(functionalType.qualifiedType());
        if (sam.isEmpty()) return Optional.empty();
        if (sam.get().declaredParameterTypes == null || sam.get().declaredParameterTypes.length != arity) {
            return Optional.empty();
        }
        return resolveDeclaredTypeName(sam.get().declaredParameterTypes[index]);
    }

    private Optional<IndexedMember> indexedSam(String qualifiedType) {
        IndexedMember sam = null;
        for (var member : index.workspace().members(qualifiedType, false)) {
            if (member.kind != CompletionItemKind.Method || member.isStatic || member.isPrivate) continue;
            if (!member.isAbstract) continue;
            if (sam != null && !Objects.equals(sam.canonicalKey, member.canonicalKey)) return Optional.empty();
            sam = member;
        }
        return Optional.ofNullable(sam);
    }

    // External reflective inference.
    private Optional<TypeResolution> resolveExternalMethodReturnType(
            TypeResolution receiverType, IndexedMember member) {
        if (receiverType == null
                || member == null
                || member.ownerType == null
                || member.name == null
                || isWorkspaceType(member.ownerType)) {
            return Optional.empty();
        }
        var method =
                resolveReflectiveMethod(
                        member.ownerType,
                        member.name,
                        member.isStatic,
                        member.erasedParameterTypes == null ? 0 : member.erasedParameterTypes.length);
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
    }

    private Optional<TypeResolution> resolveReflectiveInvocationReturnType(
            TypeResolution receiverType, String methodName, int argumentCount) {
        var method =
                resolveReflectiveMethod(
                        receiverType.qualifiedType(),
                        methodName,
                        receiverType.staticContext(),
                        argumentCount);
        if (method.isEmpty()) {
            return Optional.empty();
        }
        var bindings = bindingsForMethodOwner(receiverType, method.get().getDeclaringClass());
        return resolveReflectiveType(method.get().getGenericReturnType(), bindings);
    }

    private Optional<TypeResolution> resolveExternalSamParameterType(TypeResolution functionalType, int index, int arity) {
        var rawClass = loadExternalClass(functionalType.qualifiedType());
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        var sam = singleAbstractMethod(rawClass.get());
        if (sam.isEmpty() || sam.get().getParameterCount() != arity) {
            return Optional.empty();
        }
        var bindings = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var vars = rawClass.get().getTypeParameters();
        var typeArgs = functionalType.typeArguments();
        for (int i = 0; i < vars.length && i < typeArgs.size(); i++) {
            var arg = typeArgs.get(i);
            if (arg != null && !arg.isBlank()) {
                bindings.put(vars[i], simpleResolution(arg));
            }
        }
        // The SAM may be declared by a superinterface (BinaryOperator<T> -> BiFunction<T, U, R>.apply).
        var samBindings = resolveBindingsForClass(rawClass.get(), bindings, sam.get().getDeclaringClass()).orElse(bindings);
        return resolveReflectiveType(sam.get().getGenericParameterTypes()[index], samBindings);
    }

    private Optional<Method> resolveReflectiveMethod(
            String ownerType, String methodName, boolean staticContext, int parameterCount) {
        var rawClass = loadExternalClass(ownerType);
        if (rawClass.isEmpty()) {
            return Optional.empty();
        }
        Method match = null;
        for (var method : rawClass.get().getMethods()) {
            if (!method.getName().equals(methodName)
                    || method.getParameterCount() != parameterCount
                    || staticContext != java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (match != null && !sameReflectiveSignature(match, method)) {
                return Optional.empty();
            }
            match = method;
        }
        return Optional.ofNullable(match);
    }

    private boolean sameReflectiveSignature(Method left, Method right) {
        return left.getDeclaringClass().equals(right.getDeclaringClass())
                && Objects.deepEquals(left.getParameterTypes(), right.getParameterTypes());
    }

    private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForMethodOwner(
            TypeResolution receiverType, Class<?> declaringClass) {
        if (receiverType == null || receiverType.qualifiedType() == null || receiverType.qualifiedType().isBlank()) {
            return Map.of();
        }
        var receiverClass = loadExternalClass(receiverType.qualifiedType());
        if (receiverClass.isEmpty()) {
            return Map.of();
        }
        var initial = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var receiverVars = receiverClass.get().getTypeParameters();
        var typeArgs = receiverType.typeArguments();
        for (int i = 0; i < receiverVars.length && i < typeArgs.size(); i++) {
            var arg = typeArgs.get(i);
            if (arg != null && !arg.isBlank()) {
                initial.put(receiverVars[i], simpleResolution(arg));
            }
        }
        return resolveBindingsForClass(receiverClass.get(), initial, declaringClass).orElse(Map.of());
    }

    private Optional<Map<java.lang.reflect.TypeVariable<?>, TypeResolution>> resolveBindingsForClass(
            Class<?> current,
            Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings,
            Class<?> target) {
        if (current.equals(target)) {
            return Optional.of(bindings);
        }
        var candidates = new ArrayList<Type>();
        Collections.addAll(candidates, current.getGenericInterfaces());
        if (current.getGenericSuperclass() != null) {
            candidates.add(current.getGenericSuperclass());
        }
        for (var candidate : candidates) {
            var raw = rawClass(candidate);
            if (raw.isEmpty()) {
                continue;
            }
            var nextBindings = bindingsForSuperType(candidate, bindings);
            if (raw.get().equals(target)) {
                return Optional.of(nextBindings);
            }
            var nested = resolveBindingsForClass(raw.get(), nextBindings, target);
            if (nested.isPresent()) {
                return nested;
            }
        }
        return Optional.empty();
    }

    private Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindingsForSuperType(
            Type superType, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> currentBindings) {
        if (!(superType instanceof ParameterizedType parameterized) || !(parameterized.getRawType() instanceof Class<?> rawClass)) {
            return Map.of();
        }
        var next = new HashMap<java.lang.reflect.TypeVariable<?>, TypeResolution>();
        var vars = rawClass.getTypeParameters();
        var args = parameterized.getActualTypeArguments();
        for (int i = 0; i < vars.length && i < args.length; i++) {
            var resolved = resolveReflectiveType(args[i], currentBindings);
            if (resolved.isPresent()) {
                next.put(vars[i], resolved.get());
            }
        }
        return next;
    }

    private Optional<TypeResolution> resolveReflectiveType(
            Type type, Map<java.lang.reflect.TypeVariable<?>, TypeResolution> bindings) {
        if (type instanceof Class<?> rawClass) {
            if (rawClass.isArray()) {
                return Optional.of(new TypeResolution(sourceName(rawClass.getComponentType()), false, true));
            }
            return Optional.of(new TypeResolution(sourceName(rawClass), false, false));
        }
        if (type instanceof ParameterizedType parameterized) {
            var raw = resolveReflectiveType(parameterized.getRawType(), bindings);
            if (raw.isEmpty()) {
                return Optional.empty();
            }
            var actualArgs = parameterized.getActualTypeArguments();
            var resolvedArgs = new ArrayList<String>();
            for (var arg : actualArgs) {
                resolveReflectiveType(arg, bindings).map(ParseTypeResolver::typeName).ifPresent(resolvedArgs::add);
            }
            return Optional.of(raw.get().withTypeArguments(resolvedArgs));
        }
        if (type instanceof java.lang.reflect.TypeVariable<?> typeVariable) {
            var bound = bindings.get(typeVariable);
            if (bound != null) {
                return Optional.of(bound);
            }
            var bounds = typeVariable.getBounds();
            return bounds.length == 0 ? Optional.empty() : resolveReflectiveType(bounds[0], bindings);
        }
        if (type instanceof java.lang.reflect.WildcardType wildcard) {
            if (wildcard.getLowerBounds().length > 0) {
                return resolveReflectiveType(wildcard.getLowerBounds()[0], bindings);
            }
            for (var upper : wildcard.getUpperBounds()) {
                var resolved = resolveReflectiveType(upper, bindings);
                if (resolved.isPresent() && !"java.lang.Object".equals(resolved.get().qualifiedType())) {
                    return resolved;
                }
            }
            return Optional.empty();
        }
        if (type instanceof GenericArrayType arrayType) {
            var component = resolveReflectiveType(arrayType.getGenericComponentType(), bindings);
            return component.map(typeResolution -> new TypeResolution(typeResolution.qualifiedType(), false, true));
        }
        return Optional.empty();
    }

    /** Source-style name: nested classes as {@code java.util.Map.Entry}, as the index keys them. */
    private static String sourceName(Class<?> type) {
        var canonical = type.getCanonicalName();
        return canonical != null ? canonical : type.getName();
    }

    private Optional<Class<?>> rawClass(Type type) {
        if (type instanceof Class<?> rawClass) {
            return Optional.of(rawClass);
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawClass) {
            return Optional.of(rawClass);
        }
        return Optional.empty();
    }

    private Optional<Class<?>> loadExternalClass(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return Optional.empty();
        }
        var loader = externalClassLoader();
        if (loader == null) {
            return Optional.empty();
        }
        var binaryName = qualifiedType;
        while (true) {
            try {
                return Optional.of(Class.forName(binaryName, false, loader));
            } catch (ClassNotFoundException ignored) {
                var split = binaryName.lastIndexOf('.');
                if (split < 0) {
                    return Optional.empty();
                }
                binaryName = binaryName.substring(0, split) + "$" + binaryName.substring(split + 1);
            }
        }
    }

    private ClassLoader externalClassLoader() {
        if (externalClassLoader != null) {
            return externalClassLoader;
        }
        var urls =
                org.javacs.ApiStubJars.stubsLast(compiler.classPathRoots()).stream()
                        .map(Path::toUri)
                        .map(
                                uri -> {
                                    try {
                                        return uri.toURL();
                                    } catch (java.io.IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                })
                        .toArray(URL[]::new);
        externalClassLoader = new URLClassLoader(urls, ParseTypeResolver.class.getClassLoader());
        return externalClassLoader;
    }

    private Optional<Method> singleAbstractMethod(Class<?> rawClass) {
        Method found = null;
        for (var method : rawClass.getMethods()) {
            if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                    || method.getDeclaringClass().equals(Object.class)) {
                continue;
            }
            if (found != null && !sameReflectiveSignature(found, method)) {
                return Optional.empty();
            }
            found = method;
        }
        return Optional.ofNullable(found);
    }

    /**
     * Every type argument of a type written as text ({@code Map<String, Foo>} -> [String, Foo]), by
     * position; "" where unresolved. Wildcards stand for their bound.
     */
    private static List<String> typeArgumentsFromTypeName(
            String typeName, java.util.function.Function<String, Optional<String>> resolve) {
        if (typeName == null) return List.of();
        var start = typeName.indexOf('<');
        var end = typeName.lastIndexOf('>');
        if (start < 0 || end <= start) return List.of();
        var parts = new ArrayList<String>();
        var depth = 0;
        var from = start + 1;
        for (int i = start + 1; i < end; i++) {
            var c = typeName.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(typeName.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(typeName.substring(from, end));
        var result = new ArrayList<String>();
        for (var part : parts) {
            var arg = part.trim();
            while (arg.startsWith("? extends ")) arg = arg.substring("? extends ".length()).trim();
            while (arg.startsWith("? super ")) arg = arg.substring("? super ".length()).trim();
            result.add(arg.isBlank() || "?".equals(arg) ? "" : resolve.apply(arg).orElse(""));
        }
        return result.stream().allMatch(String::isBlank) ? List.of() : result;
    }

    private Optional<TypeResolution> resolveDeclaredTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var normalized = TypeNames.normalize(typeName);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        var resolved =
                resolveTypeNameInSource(normalized, root)
                        .or(() -> index.resolveType(normalized, root).map(indexed -> indexed.qualifiedName));
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        var array = typeName.endsWith("[]");
        var qualified = resolved.get();
        if (array && qualified.endsWith("[]")) {
            qualified = qualified.substring(0, qualified.length() - 2);
        }
        return Optional.of(
                new TypeResolution(qualified, false, array)
                        .withTypeArguments(typeArgumentsFromTypeName(
                                typeName, arg -> resolveDeclaredTypeName(arg).map(TypeResolution::qualifiedType))));
    }

    private boolean isWorkspaceType(String qualifiedType) {
        return qualifiedType != null
                && !qualifiedType.isBlank()
                && index.isWorkspaceOwnedType(qualifiedType);
    }

    private boolean canUseExternalInference(String qualifiedType) {
        return qualifiedType != null && !qualifiedType.isBlank() && !isWorkspaceType(qualifiedType);
    }

    // Current-file type-name fallback.
    /**
     * Resolve a simple or nested type name from the current parse tree only.
     *
     * <p>This is intentionally narrower than the index. It exists for same-file declarations that
     * are visible in the open parse tree before any additional workspace repair would be possible.
     * If two nested classes in the same file share the same simple name in different scopes, this
     * method returns empty rather than guessing.
     */
    private Optional<String> resolveTypeNameInSource(String typeName, CompilationUnitTree sourceRoot) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        var raw = typeName.replace('$', '.');
        if (raw.contains(".") && declaredClassPathInRoot(sourceRoot, raw) != null) {
            return Optional.of(raw);
        }
        final String[] match = new String[1];
        final boolean[] ambiguous = {false};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                var qualified = qualifiedClassName(sourceRoot, getCurrentPath());
                var simple = classTree.getSimpleName().toString();
                if (!simple.equals(raw)) {
                    return super.visitClass(classTree, unused);
                }
                if (match[0] == null) {
                    match[0] = qualified;
                } else if (!match[0].equals(qualified)) {
                    ambiguous[0] = true;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(sourceRoot, null);
        if (ambiguous[0]) {
            return Optional.empty();
        }
        return Optional.ofNullable(match[0]);
    }

    private TreePath declaredClassPathInRoot(CompilationUnitTree sourceRoot, String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return null;
        }
        final TreePath[] match = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void unused) {
                if (qualifiedType.equals(qualifiedClassName(sourceRoot, getCurrentPath()))) {
                    match[0] = getCurrentPath();
                    return null;
                }
                return super.visitClass(classTree, unused);
            }
        }.scan(sourceRoot, null);
        return match[0];
    }

    private Optional<TypeResolution> literalType(LiteralTree literal) {
        var value = literal.getValue();
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof String) {
            return Optional.of(new TypeResolution("java.lang.String", false, false));
        }
        if (value instanceof Integer) {
            return Optional.of(new TypeResolution("java.lang.Integer", false, false));
        }
        if (value instanceof Long) {
            return Optional.of(new TypeResolution("java.lang.Long", false, false));
        }
        if (value instanceof Float) {
            return Optional.of(new TypeResolution("java.lang.Float", false, false));
        }
        if (value instanceof Double) {
            return Optional.of(new TypeResolution("java.lang.Double", false, false));
        }
        if (value instanceof Boolean) {
            return Optional.of(new TypeResolution("java.lang.Boolean", false, false));
        }
        if (value instanceof Character) {
            return Optional.of(new TypeResolution("java.lang.Character", false, false));
        }
        return Optional.empty();
    }

    // Cursor and scope utilities.
    private TreePath enclosingClassPath() {
        return scopeSnapshot().enclosingClassPath();
    }

    private ScopeSnapshot scopeSnapshot() {
        if (scopeSnapshot != null) {
            return scopeSnapshot;
        }

        final TreePath[] enclosingClass = {parentClassPath(cursorPath)};
        final int[] bestDepth = {enclosingClass[0] == null ? -1 : depth(enclosingClass[0])};
        var visibleVariables = new ArrayList<VisibleVariable>();
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classTree, Void p) {
                if (!containsCursor(classTree)) {
                    return null;
                }
                var depth = depth(getCurrentPath());
                if (depth > bestDepth[0]) {
                    bestDepth[0] = depth;
                    enclosingClass[0] = getCurrentPath();
                }
                for (var member : classTree.getMembers()) {
                    if (member instanceof VariableTree) {
                        scan(member, p);
                    } else if (containsCursor(member)) {
                        scan(member, p);
                    }
                }
                return null;
            }

            @Override
            public Void visitMethod(MethodTree methodTree, Void p) {
                if (!containsCursor(methodTree)) {
                    return null;
                }
                for (var parameter : methodTree.getParameters()) {
                    addVisibleVariable(parameter, new TreePath(getCurrentPath(), parameter));
                }
                if (methodTree.getBody() != null) {
                    scan(methodTree.getBody(), p);
                }
                return null;
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree lambdaTree, Void p) {
                if (!containsCursor(lambdaTree)) {
                    return null;
                }
                for (var parameter : lambdaTree.getParameters()) {
                    addVisibleVariable(parameter, new TreePath(getCurrentPath(), parameter));
                }
                scan(lambdaTree.getBody(), p);
                return null;
            }

            @Override
            public Void visitBlock(BlockTree blockTree, Void p) {
                if (!containsCursor(blockTree)) {
                    return null;
                }
                for (var statement : blockTree.getStatements()) {
                    var start = startOf(statement);
                    if (start >= 0 && start >= cursor) {
                        break;
                    }
                    if (containsCursor(statement)) {
                        scan(statement, p);
                        break;
                    }
                    if (statement instanceof VariableTree variableTree) {
                        addVisibleVariable(variableTree, new TreePath(getCurrentPath(), variableTree));
                    }
                }
                return null;
            }

            @Override
            public Void visitEnhancedForLoop(EnhancedForLoopTree loopTree, Void p) {
                if (!containsCursor(loopTree)) {
                    return null;
                }
                addVisibleVariable(loopTree.getVariable(), new TreePath(getCurrentPath(), loopTree.getVariable()));
                if (containsCursor(loopTree.getStatement())) {
                    scan(loopTree.getStatement(), p);
                }
                return null;
            }

            @Override
            public Void visitForLoop(ForLoopTree loopTree, Void p) {
                if (!containsCursor(loopTree)) {
                    return null;
                }
                for (var initializer : loopTree.getInitializer()) {
                    if (containsCursor(initializer)) {
                        scan(initializer, p);
                        continue;
                    }
                    if (initializer instanceof VariableTree variableTree) {
                        addVisibleVariable(variableTree, new TreePath(getCurrentPath(), variableTree));
                    }
                }
                if (loopTree.getCondition() != null && containsCursor(loopTree.getCondition())) {
                    scan(loopTree.getCondition(), p);
                }
                for (var update : loopTree.getUpdate()) {
                    if (containsCursor(update)) {
                        scan(update, p);
                    }
                }
                if (containsCursor(loopTree.getStatement())) {
                    scan(loopTree.getStatement(), p);
                }
                return null;
            }

            @Override
            public Void visitCatch(CatchTree catchTree, Void p) {
                if (!containsCursor(catchTree)) {
                    return null;
                }
                addVisibleVariable(catchTree.getParameter(), new TreePath(getCurrentPath(), catchTree.getParameter()));
                if (catchTree.getBlock() != null) {
                    scan(catchTree.getBlock(), p);
                }
                return null;
            }

            @Override
            public Void visitVariable(VariableTree variableTree, Void p) {
                if (variableTree.getInitializer() != null && containsCursor(variableTree.getInitializer())) {
                    scan(variableTree.getInitializer(), p);
                    return null;
                }
                // Cursor inside the declared type: the variable is not in scope yet. Typing
                // `query.set` above `query.setDate(...);` parses as the declaration
                // `query.set query.setDate(...)`, whose name would shadow the real `query`.
                var type = variableTree.getType();
                if (type != null) {
                    var typeEnd = positions.getEndPosition(root, type);
                    if (typeEnd >= 0 && cursor <= typeEnd) {
                        return null;
                    }
                }
                addVisibleVariable(variableTree, getCurrentPath());
                return null;
            }

            private void addVisibleVariable(VariableTree variableTree, TreePath path) {
                var start = startOf(variableTree);
                if (start < 0 || start >= cursor) {
                    return;
                }
                visibleVariables.add(new VisibleVariable(variableTree, path, depth(path), start));
            }
        }.scan(root, null);
        scopeSnapshot = new ScopeSnapshot(enclosingClass[0], java.util.List.copyOf(visibleVariables));
        return scopeSnapshot;
    }

    private String qualifiedClassName(CompilationUnitTree sourceRoot, TreePath classPath) {
        var classes = new ArrayList<String>();
        for (var cursorPath = classPath; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            if (cursorPath.getLeaf() instanceof ClassTree classTree) {
                classes.add(classTree.getSimpleName().toString());
            }
        }
        Collections.reverse(classes);
        var packageName = sourceRoot.getPackageName() == null ? "" : sourceRoot.getPackageName().toString();
        return packageName.isEmpty() ? String.join(".", classes) : packageName + "." + String.join(".", classes);
    }

    private boolean containsCursor(Tree tree) {
        var start = startOf(tree);
        var end = endOf(tree);
        if (start < 0 || end < 0) {
            return false;
        }
        return start <= cursor && cursor <= end;
    }

    private long startOf(Tree tree) {
        return positions.getStartPosition(root, tree);
    }

    private long endOf(Tree tree) {
        return positions.getEndPosition(root, tree);
    }

    private int depth(TreePath path) {
        var depth = 0;
        for (var cursorPath = path; cursorPath != null; cursorPath = cursorPath.getParentPath()) {
            depth++;
        }
        return depth;
    }

    private static TypeResolution simpleResolution(String qualifiedType) {
        if (qualifiedType == null || qualifiedType.isBlank()) {
            return new TypeResolution("", false, false);
        }
        var array = qualifiedType.endsWith("[]");
        return new TypeResolution(
                array ? qualifiedType.substring(0, qualifiedType.length() - 2) : qualifiedType, false, array);
    }

    private static String typeName(TypeResolution resolution) {
        if (resolution == null || resolution.qualifiedType() == null) {
            return null;
        }
        return resolution.arrayType() ? resolution.qualifiedType() + "[]" : resolution.qualifiedType();
    }

    private Optional<VisibleVariable> findVisibleVariable(String targetName) {
        VisibleVariable best = null;
        for (var candidate : scopeSnapshot().visibleVariables()) {
            if (!candidate.tree().getName().contentEquals(targetName)) {
                continue;
            }
            if (best == null
                    || candidate.depth() > best.depth()
                    || (candidate.depth() == best.depth() && candidate.start() > best.start())) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}
