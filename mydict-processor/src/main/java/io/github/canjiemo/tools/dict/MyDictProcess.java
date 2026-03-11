package io.github.canjiemo.tools.dict;

import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.*;
import io.github.canjiemo.tools.dict.entity.Var;
import io.github.canjiemo.tools.dict.entity.VarType;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@SupportedAnnotationTypes({"io.github.canjiemo.tools.dict.MyDict"})
public class MyDictProcess extends AbstractProcessor {

    private record ResolvedDescAnnotation(String fullAnnotationName, List<JCTree.JCExpression> arguments) {
    }

    private JavacTrees trees;
    private TreeMaker treeMaker;
    private Names names;
    private JavacElements elementUtils;
    private Messager messager;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element element : roundEnv.getElementsAnnotatedWith(MyDict.class)) {
            VariableElement variableElement = (VariableElement) element;
            try {
                JCTree.JCVariableDecl jcVariableDecl = (JCTree.JCVariableDecl) trees.getTree(variableElement);
                JCTree.JCClassDecl jcClassDecl = (JCTree.JCClassDecl) trees.getTree(variableElement.getEnclosingElement());
                MyDict annotation = variableElement.getAnnotation(MyDict.class);
                String dictType = resolveDictType(variableElement, annotation);
                if (dictType == null) {
                    continue;
                }
                java.util.List<ResolvedDescAnnotation> descFieldAnnotations = resolveDescFieldAnnotations(variableElement, annotation);
                if (descFieldAnnotations == null) {
                    continue;
                }
                if (isVariableExist(jcClassDecl, jcVariableDecl, annotation)) {
                    continue;
                }

                JCTree.JCVariableDecl dictVariableDecl = makeDictDescFieldDecl(jcVariableDecl, annotation, descFieldAnnotations);
                jcClassDecl.defs = jcClassDecl.defs.append(dictVariableDecl);

                Name getterName = getNewMethodName(0, jcVariableDecl.getName(), annotation);
                if (!isMethodExist(jcClassDecl, getterName, 0)) {
                    jcClassDecl.defs = jcClassDecl.defs.append(makeGetterMethodDecl(jcClassDecl, jcVariableDecl, annotation, dictType));
                }

                Name setterName = getNewMethodName(1, jcVariableDecl.getName(), annotation);
                if (!isMethodExist(jcClassDecl, setterName, 1)) {
                    jcClassDecl.defs = jcClassDecl.defs.append(makeSetterMethod(jcVariableDecl, annotation));
                }
            } catch (Exception e) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Failed to generate MyDict members for field '" + variableElement.getSimpleName() + "': " + e.getMessage(),
                        variableElement
                );
            }
        }
        return true;
    }

    private String resolveDictType(VariableElement variableElement, MyDict annotation) {
        String dictType = annotation.type() == null ? "" : annotation.type().trim();
        if (dictType.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@MyDict requires a dictionary type, for example @MyDict(type = \"user_status\").",
                    variableElement
            );
            return null;
        }
        return dictType;
    }

    private java.util.List<ResolvedDescAnnotation> resolveDescFieldAnnotations(VariableElement variableElement, MyDict annotation) {
        FieldAnnotation[] descFieldAnnotations = annotation.descFieldAnnotations() == null
                ? new FieldAnnotation[0]
                : annotation.descFieldAnnotations();

        java.util.List<ResolvedDescAnnotation> resolvedAnnotations = new ArrayList<>();
        for (FieldAnnotation descFieldAnnotation : descFieldAnnotations) {
            ResolvedDescAnnotation resolvedAnnotation = resolveDescFieldAnnotation(variableElement, descFieldAnnotation);
            if (resolvedAnnotation == null) {
                return null;
            }
            resolvedAnnotations.add(resolvedAnnotation);
        }
        return resolvedAnnotations;
    }

    private ResolvedDescAnnotation resolveDescFieldAnnotation(VariableElement variableElement, FieldAnnotation annotation) {
        String fullAnnotationName = annotation.fullAnnotationName() == null ? "" : annotation.fullAnnotationName().trim();
        if (fullAnnotationName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "descFieldAnnotations requires fullAnnotationName.",
                    variableElement
            );
            return null;
        }

        TypeElement annotationType = elementUtils.getTypeElement(fullAnnotationName);
        if (annotationType == null) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Cannot resolve desc field annotation type '" + fullAnnotationName + "'.",
                    variableElement
            );
            return null;
        }
        if (annotationType.getKind() != ElementKind.ANNOTATION_TYPE) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "'" + fullAnnotationName + "' is not an annotation type.",
                    variableElement
            );
            return null;
        }

        Map<String, ExecutableElement> members = getAnnotationMembers(annotationType);
        Set<String> assignedMembers = new HashSet<>();
        ListBuffer<JCTree.JCExpression> arguments = new ListBuffer<>();
        for (Var var : annotation.vars()) {
            String varName = var.varName() == null ? "" : var.varName().trim();
            if (varName.isEmpty()) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "descFieldAnnotations on '" + fullAnnotationName + "' contains an empty varName.",
                        variableElement
                );
                return null;
            }
            if (!assignedMembers.add(varName)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Duplicate annotation member '" + varName + "' in descFieldAnnotations for '" + fullAnnotationName + "'.",
                        variableElement
                );
                return null;
            }

            ExecutableElement member = members.get(varName);
            if (member == null) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Annotation '" + fullAnnotationName + "' does not declare member '" + varName + "'.",
                        variableElement
                );
                return null;
            }

            JCTree.JCExpression valueExpression = buildAnnotationMemberValue(variableElement, fullAnnotationName, member, var);
            if (valueExpression == null) {
                return null;
            }
            arguments.append(treeMaker.Assign(treeMaker.Ident(names.fromString(varName)), valueExpression));
        }

        for (ExecutableElement member : members.values()) {
            AnnotationValue defaultValue = member.getDefaultValue();
            String memberName = member.getSimpleName().toString();
            if (defaultValue == null && !assignedMembers.contains(memberName)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Annotation '" + fullAnnotationName + "' requires member '" + memberName + "'.",
                        variableElement
                );
                return null;
            }
        }

        return new ResolvedDescAnnotation(fullAnnotationName, arguments.toList());
    }

    private Map<String, ExecutableElement> getAnnotationMembers(TypeElement annotationType) {
        Map<String, ExecutableElement> members = new HashMap<>();
        for (Element enclosedElement : annotationType.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.METHOD) {
                members.put(enclosedElement.getSimpleName().toString(), (ExecutableElement) enclosedElement);
            }
        }
        return members;
    }

    private boolean isVariableExist(JCTree.JCClassDecl jcClassDecl, JCTree.JCVariableDecl jcVariableDecl, MyDict annotation) {
        Name dictVarName = getNewDictVarName(jcVariableDecl.getName(), annotation);
        return jcClassDecl.defs.stream().anyMatch(x -> {
            if (x.getTree() instanceof JCTree.JCVariableDecl tree) {
                return tree.getName().toString().equals(dictVarName.toString());
            }
            return false;
        });
    }

    private JCTree.JCVariableDecl makeDictDescFieldDecl(JCTree.JCVariableDecl jcVariableDecl, MyDict dict, java.util.List<ResolvedDescAnnotation> descFieldAnnotations) {
        treeMaker.pos = jcVariableDecl.pos;
        ListBuffer<JCTree.JCAnnotation> annotationsList = new ListBuffer<>();
        try {
            Class.forName("com.baomidou.mybatisplus.annotation.TableField");
            JCTree.JCExpression attr1 = treeMaker.Assign(treeMaker.Ident(names.fromString("exist")),
                    treeMaker.Literal(false));
            JCTree.JCAnnotation jcAnnotation = treeMaker.Annotation(memberAccess("com.baomidou.mybatisplus.annotation.TableField"),
                    List.of(attr1));
            annotationsList.append(jcAnnotation);
        } catch (Throwable e) {
            // MyBatis-Plus 不存在，跳过添加 @TableField 注解
        }
        for (ResolvedDescAnnotation annotation : descFieldAnnotations) {
            annotationsList.append(treeMaker.Annotation(memberAccess(annotation.fullAnnotationName()), annotation.arguments()));
        }
        long generatedFieldFlags = jcVariableDecl.getModifiers().flags & ~Flags.FINAL;
        return treeMaker.VarDef(treeMaker.Modifiers(generatedFieldFlags, annotationsList.toList()), getNewDictVarName(jcVariableDecl.getName(), dict), memberAccess("java.lang.String"), null);
    }

    private JCTree.JCExpression buildAnnotationMemberValue(VariableElement variableElement, String annotationName, ExecutableElement member, Var var) {
        TypeMirror memberType = member.getReturnType();
        boolean hasScalarValue = !Var.UNSET.equals(var.varValue());
        boolean hasArrayValues = var.varValues() != null && var.varValues().length > 0;

        if (memberType.getKind() == TypeKind.ARRAY) {
            if (hasScalarValue && hasArrayValues) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' cannot set both varValue and varValues.",
                        variableElement
                );
                return null;
            }

            ArrayType arrayType = (ArrayType) memberType;
            java.util.List<String> rawValues = new ArrayList<>();
            if (hasArrayValues) {
                for (String rawValue : var.varValues()) {
                    rawValues.add(rawValue);
                }
            } else if (hasScalarValue) {
                rawValues.add(var.varValue());
            }

            ListBuffer<JCTree.JCExpression> expressions = new ListBuffer<>();
            for (String rawValue : rawValues) {
                JCTree.JCExpression expression = buildScalarAnnotationValueExpression(
                        variableElement, annotationName, member, arrayType.getComponentType(), var.varType(), rawValue
                );
                if (expression == null) {
                    return null;
                }
                expressions.append(expression);
            }
            return treeMaker.NewArray(null, List.nil(), expressions.toList());
        }

        if (hasArrayValues) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' is not an array; use varValue instead of varValues.",
                    variableElement
            );
            return null;
        }
        if (!hasScalarValue) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' requires varValue.",
                    variableElement
            );
            return null;
        }

        return buildScalarAnnotationValueExpression(variableElement, annotationName, member, memberType, var.varType(), var.varValue());
    }

    private JCTree.JCExpression buildScalarAnnotationValueExpression(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            TypeMirror expectedType,
            VarType actualType,
            String rawValue
    ) {
        try {
            return switch (expectedType.getKind()) {
                case BOOLEAN -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.BOOLEAN);
                    yield treeMaker.Literal(parseBoolean(rawValue));
                }
                case BYTE -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.BYTE);
                    yield treeMaker.Literal(TypeTag.BYTE, Integer.valueOf(Byte.parseByte(rawValue.trim())));
                }
                case SHORT -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.SHORT);
                    yield treeMaker.Literal(TypeTag.SHORT, Integer.valueOf(Short.parseShort(rawValue.trim())));
                }
                case INT -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.INT);
                    yield treeMaker.Literal(Integer.parseInt(rawValue.trim()));
                }
                case LONG -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.LONG);
                    yield treeMaker.Literal(Long.parseLong(rawValue.trim()));
                }
                case FLOAT -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.FLOAT);
                    yield treeMaker.Literal(Float.parseFloat(rawValue.trim()));
                }
                case DOUBLE -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.DOUBLE);
                    yield treeMaker.Literal(Double.parseDouble(rawValue.trim()));
                }
                case CHAR -> {
                    ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.CHAR);
                    yield treeMaker.Literal(TypeTag.CHAR, Integer.valueOf(parseChar(rawValue)));
                }
                case DECLARED -> buildDeclaredAnnotationValueExpression(
                        variableElement, annotationName, member, (DeclaredType) expectedType, actualType, rawValue
                );
                default -> unsupportedAnnotationMemberType(variableElement, annotationName, member, expectedType);
            };
        } catch (IllegalArgumentException ex) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Invalid value for annotation '" + annotationName + "' member '" + member.getSimpleName() + "': " + ex.getMessage(),
                    variableElement
            );
            return null;
        }
    }

    private JCTree.JCExpression buildDeclaredAnnotationValueExpression(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            DeclaredType expectedType,
            VarType actualType,
            String rawValue
    ) {
        Element element = expectedType.asElement();
        if (!(element instanceof TypeElement typeElement)) {
            return unsupportedAnnotationMemberType(variableElement, annotationName, member, expectedType);
        }

        String qualifiedName = typeElement.getQualifiedName().toString();
        if (String.class.getCanonicalName().equals(qualifiedName)) {
            ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.STRING);
            return treeMaker.Literal(rawValue);
        }
        if (Class.class.getCanonicalName().equals(qualifiedName)) {
            ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.CLASS);
            TypeMirror classLiteralType = resolveClassLiteralType(variableElement, annotationName, member, rawValue);
            if (classLiteralType == null) {
                return null;
            }
            return treeMaker.ClassLiteral((Type) classLiteralType);
        }
        if (typeElement.getKind() == ElementKind.ENUM) {
            ensureVarType(variableElement, annotationName, member, expectedType, actualType, VarType.ENUM);
            String enumConstantName = resolveEnumConstantName(variableElement, annotationName, member, typeElement, rawValue);
            if (enumConstantName == null) {
                return null;
            }
            return memberAccess(typeElement.getQualifiedName() + "." + enumConstantName);
        }

        return unsupportedAnnotationMemberType(variableElement, annotationName, member, expectedType);
    }

    private JCTree.JCExpression unsupportedAnnotationMemberType(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            TypeMirror expectedType
    ) {
        messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' has unsupported type '" + expectedType + "'.",
                variableElement
        );
        return null;
    }

    private void ensureVarType(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            TypeMirror expectedType,
            VarType actualType,
            VarType expectedVarType
    ) {
        if (actualType != expectedVarType) {
            throw new IllegalArgumentException(
                    "member '" + member.getSimpleName() + "' expects type '" + expectedType + "', but received VarType." + actualType
            );
        }
    }

    private boolean parseBoolean(String rawValue) {
        String normalized = rawValue.trim();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        throw new IllegalArgumentException("expected boolean literal true or false");
    }

    private char parseChar(String rawValue) {
        if (rawValue.length() == 1) {
            return rawValue.charAt(0);
        }
        return switch (rawValue) {
            case "\\b" -> '\b';
            case "\\t" -> '\t';
            case "\\n" -> '\n';
            case "\\f" -> '\f';
            case "\\r" -> '\r';
            case "\\\"" -> '\"';
            case "\\'" -> '\'';
            case "\\\\" -> '\\';
            default -> {
                if (rawValue.startsWith("\\u") && rawValue.length() == 6) {
                    yield (char) Integer.parseInt(rawValue.substring(2), 16);
                }
                throw new IllegalArgumentException("expected a single character or supported escape sequence");
            }
        };
    }

    private TypeMirror resolveClassLiteralType(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            String rawValue
    ) {
        String className = rawValue.trim();
        if (className.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' requires a class name.",
                    variableElement
            );
            return null;
        }

        TypeMirror primitiveType = switch (className) {
            case "boolean" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.BOOLEAN);
            case "byte" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.BYTE);
            case "short" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.SHORT);
            case "int" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.INT);
            case "long" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.LONG);
            case "float" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.FLOAT);
            case "double" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.DOUBLE);
            case "char" -> processingEnv.getTypeUtils().getPrimitiveType(TypeKind.CHAR);
            case "void" -> processingEnv.getTypeUtils().getNoType(TypeKind.VOID);
            default -> null;
        };
        if (primitiveType != null) {
            return primitiveType;
        }

        TypeElement typeElement = elementUtils.getTypeElement(className);
        if (typeElement == null) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Cannot resolve class literal '" + className + "' for annotation '" + annotationName + "' member '" + member.getSimpleName() + "'.",
                    variableElement
            );
            return null;
        }
        return typeElement.asType();
    }

    private String resolveEnumConstantName(
            VariableElement variableElement,
            String annotationName,
            ExecutableElement member,
            TypeElement enumType,
            String rawValue
    ) {
        String normalized = rawValue.trim();
        if (normalized.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "Annotation '" + annotationName + "' member '" + member.getSimpleName() + "' requires an enum constant name.",
                    variableElement
            );
            return null;
        }

        String enumTypeName = enumType.getQualifiedName().toString();
        String constantName = normalized;
        if (normalized.contains(".")) {
            String prefix = enumTypeName + ".";
            if (!normalized.startsWith(prefix)) {
                messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "Enum member '" + member.getSimpleName() + "' must use constant names from '" + enumTypeName + "'.",
                        variableElement
                );
                return null;
            }
            constantName = normalized.substring(prefix.length());
        }

        for (Element enclosedElement : enumType.getEnclosedElements()) {
            if (enclosedElement.getKind() == ElementKind.ENUM_CONSTANT
                    && enclosedElement.getSimpleName().contentEquals(constantName)) {
                return constantName;
            }
        }

        messager.printMessage(
                Diagnostic.Kind.ERROR,
                "Enum '" + enumTypeName + "' does not declare constant '" + constantName + "'.",
                variableElement
        );
        return null;
    }

    private JCTree.JCExpression memberAccess(String components) {
        String[] componentArray = components.split("\\.");
        JCTree.JCExpression expr = treeMaker.Ident(names.fromString(componentArray[0]));
        for (int i = 1; i < componentArray.length; i++) {
            expr = treeMaker.Select(expr, names.fromString(componentArray[i]));
        }
        return expr;
    }

    /**
     * 生成 getter/setter 方法名，自动处理驼峰命名和蛇形命名
     */
    private Name getNewMethodName(int methodType, Name name, MyDict annotation) {
        name = getNewDictVarName(name, annotation);
        String s = name.toString();
        String pref = methodType == 0 ? "get" : "set";
        return names.fromString(pref + s.substring(0, 1).toUpperCase() + s.substring(1));
    }

    private boolean isMethodExist(JCTree.JCClassDecl jcClassDecl, Name methodName, int parameterCount) {
        return jcClassDecl.defs.stream().anyMatch(def -> {
            if (!(def instanceof JCTree.JCMethodDecl methodDecl)) {
                return false;
            }
            return methodDecl.getName().contentEquals(methodName) && methodDecl.params.size() == parameterCount;
        });
    }

    private JCTree.JCExpression readOriginalFieldValue(JCTree.JCClassDecl jcClassDecl, JCTree.JCVariableDecl jcVariableDecl) {
        Name getterName = getterMethodName(jcVariableDecl);
        if (isMethodExist(jcClassDecl, getterName, 0)) {
            return treeMaker.Apply(
                    List.nil(),
                    treeMaker.Select(memberAccess("this"), getterName),
                    List.nil()
            );
        }
        return treeMaker.Select(treeMaker.Ident(names.fromString("this")), jcVariableDecl.getName());
    }

    private JCTree.JCMethodDecl makeGetterMethodDecl(JCTree.JCClassDecl jcClassDecl, JCTree.JCVariableDecl jcVariableDecl, MyDict annotation, String dictType) {
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        JCTree.JCVariableDecl descStr = treeMaker.VarDef(
                treeMaker.Modifiers(0), names.fromString("descStr"), memberAccess("java.lang.String"), treeMaker.Apply(
                        List.<JCTree.JCExpression>nil(),
                        treeMaker.Select(memberAccess("io.github.canjiemo.tools.dict.MyDictHelper"),
                                elementUtils.getName("getDesc")),
                        List.<JCTree.JCExpression>of(
                                treeMaker.Literal(dictType),
                                readOriginalFieldValue(jcClassDecl, jcVariableDecl)
                        )
                ));
        statements.append(descStr);

        JCTree.JCBinary notNull = treeMaker.Binary(
                JCTree.Tag.NE,
                treeMaker.Ident(names.fromString("descStr")),
                treeMaker.Literal(TypeTag.BOT, null)
        );

        JCTree.JCMethodInvocation isEmptyCall = treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                treeMaker.Select(treeMaker.Ident(names.fromString("descStr")), elementUtils.getName("isEmpty")),
                com.sun.tools.javac.util.List.nil()
        );

        JCTree.JCUnary notEmpty = treeMaker.Unary(JCTree.Tag.NOT, isEmptyCall);
        JCTree.JCBinary condition = treeMaker.Binary(JCTree.Tag.AND, notNull, notEmpty);

        JCTree.JCStatement ifTrue = treeMaker.Return(treeMaker.Ident(names.fromString("descStr")));
        JCTree.JCStatement ifFalse = treeMaker.Return(treeMaker.Literal(annotation.defaultDesc()));

        statements.append(treeMaker.If(condition, ifTrue, ifFalse));
        JCTree.JCBlock body = treeMaker.Block(0, statements.toList());
        return treeMaker.MethodDef(treeMaker.Modifiers(Flags.PUBLIC), getNewMethodName(0, jcVariableDecl.getName(), annotation), memberAccess("java.lang.String"), List.nil(), List.nil(), List.nil(), body, null);
    }

    /**
     * 根据原字段名的命名风格，生成对应的描述字段名
     *
     * 命名规则（优先级从高到低）：
     * 1. 包含下划线 → 蛇形（user_status → user_status_desc / SEX_TYPE → SEX_TYPE_DESC）
     * 2. 大小写混合 → 驼峰（userName → userNameDesc）
     * 3. 全小写，根据 camelCase 开关决定（type → typeDesc 或 type_desc）
     */
    private Name getNewDictVarName(Name name, MyDict annotation) {
        String originalName = name.toString();

        if (originalName.contains("_")) {
            if (originalName.equals(originalName.toUpperCase())) {
                return names.fromString(originalName + "_DESC");
            } else {
                return names.fromString(originalName + "_desc");
            }
        }

        if (hasMixedCase(originalName)) {
            return names.fromString(originalName + "Desc");
        }

        if (annotation.camelCase()) {
            return names.fromString(originalName + "Desc");
        } else {
            return names.fromString(originalName + "_desc");
        }
    }

    private boolean hasMixedCase(String str) {
        boolean hasLower = false;
        boolean hasUpper = false;
        for (char c : str.toCharArray()) {
            if (Character.isLowerCase(c)) hasLower = true;
            if (Character.isUpperCase(c)) hasUpper = true;
            if (hasLower && hasUpper) return true;
        }
        return false;
    }

    private Name getterMethodName(JCTree.JCVariableDecl jcVariableDecl) {
        String s = jcVariableDecl.getName().toString();
        return names.fromString("get" + s.substring(0, 1).toUpperCase() + s.substring(1));
    }

    private JCTree.JCMethodDecl makeSetterMethod(JCTree.JCVariableDecl jcVariableDecl, MyDict annotation) {
        JCTree.JCModifiers jcModifiers = treeMaker.Modifiers(Flags.PUBLIC);
        JCTree.JCExpression returnType = treeMaker.TypeIdent(TypeTag.VOID);
        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER), getNewDictVarName(jcVariableDecl.name, annotation), memberAccess("java.lang.String"), null);
        param.pos = jcVariableDecl.pos;
        List<JCTree.JCVariableDecl> parameters = List.of(param);
        JCTree.JCStatement jcStatement = treeMaker.Exec(treeMaker.Assign(
                treeMaker.Select(treeMaker.Ident(names.fromString("this")), getNewDictVarName(jcVariableDecl.name, annotation)),
                treeMaker.Ident(getNewDictVarName(jcVariableDecl.name, annotation))));
        JCTree.JCBlock jcBlock = treeMaker.Block(0, List.of(jcStatement));
        return treeMaker.MethodDef(jcModifiers, getNewMethodName(1, jcVariableDecl.getName(), annotation), returnType, List.nil(), parameters, List.nil(), jcBlock, null);
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        ProcessingEnvironment unwrapped = unwrapProcessingEnvironment(processingEnv);
        this.trees = JavacTrees.instance(unwrapped);
        Context context = ((JavacProcessingEnvironment) unwrapped).getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
        this.elementUtils = (JavacElements) processingEnv.getElementUtils();
    }

    /**
     * 解包 IntelliJ IDEA 的 ProcessingEnvironment Proxy
     *
     * IDEA 的增量编译环境会将 ProcessingEnvironment 包装成 Proxy，
     * 需要解包才能访问 javac 的内部 API（如 Tree API）。
     */
    private ProcessingEnvironment unwrapProcessingEnvironment(ProcessingEnvironment processingEnv) {
        try {
            Class<?> apiWrappers = processingEnv.getClass().getClassLoader()
                    .loadClass("org.jetbrains.jps.javac.APIWrappers");
            java.lang.reflect.Method unwrapMethod = apiWrappers.getDeclaredMethod("unwrap", Class.class, Object.class);
            ProcessingEnvironment unwrapped = (ProcessingEnvironment) unwrapMethod.invoke(
                    null, ProcessingEnvironment.class, processingEnv);
            if (unwrapped != null) {
                return unwrapped;
            }
        } catch (Throwable ignored) {
        }
        return processingEnv;
    }
}
