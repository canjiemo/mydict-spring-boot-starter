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
import javax.lang.model.type.NoType;
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

        elementUtils = (JavacElements) processingEnv.getElementUtils();

        for (Element element : roundEnv.getElementsAnnotatedWith(MyDict.class)) {
            VariableElement variableElement = (VariableElement) element;
            try {
                JCTree.JCVariableDecl jcVariableDecl = (JCTree.JCVariableDecl) trees.getTree(variableElement);
                JCTree.JCClassDecl jcClassDecl = (JCTree.JCClassDecl) trees.getTree(variableElement.getEnclosingElement());
                MyDict annotation = variableElement.getAnnotation(MyDict.class);
                String dictName = resolveDictName(variableElement, annotation);
                if (dictName == null) {
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
                    jcClassDecl.defs = jcClassDecl.defs.append(makeGetterMethodDecl(jcClassDecl, jcVariableDecl, annotation, dictName));
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

    private String resolveDictName(VariableElement variableElement, MyDict annotation) {
        String value = annotation.value() == null ? "" : annotation.value().trim();
        String name = annotation.name() == null ? "" : annotation.name().trim();

        if (!value.isEmpty() && !name.isEmpty() && !value.equals(name)) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@MyDict value and name must match when both are set.",
                    variableElement
            );
            return null;
        }

        String dictName = !name.isEmpty() ? name : value;
        if (dictName.isEmpty()) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@MyDict requires a dictionary name, for example @MyDict(\"status_dict\") or @MyDict(name = \"status_dict\").",
                    variableElement
            );
            return null;
        }

        return dictName;
    }

    private java.util.List<ResolvedDescAnnotation> resolveDescFieldAnnotations(VariableElement variableElement, MyDict annotation) {
        FieldAnnotation[] descFieldAnnotations = annotation.descFieldAnnotations() == null
                ? new FieldAnnotation[0]
                : annotation.descFieldAnnotations();
        FieldAnnotation[] legacyFieldAnnotations = annotation.fieldAnnotations() == null
                ? new FieldAnnotation[0]
                : annotation.fieldAnnotations();

        if (descFieldAnnotations.length > 0 && legacyFieldAnnotations.length > 0) {
            messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@MyDict cannot use descFieldAnnotations and deprecated fieldAnnotations at the same time.",
                    variableElement
            );
            return null;
        }

        FieldAnnotation[] effectiveAnnotations = descFieldAnnotations.length > 0
                ? descFieldAnnotations
                : legacyFieldAnnotations;

        java.util.List<ResolvedDescAnnotation> resolvedAnnotations = new ArrayList<>();
        for (FieldAnnotation descFieldAnnotation : effectiveAnnotations) {
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

    private boolean isVariableExist(JCTree.JCClassDecl jcClassDecl, JCTree.JCVariableDecl jcVariableDecl, MyDict annotation){
        Name dictVarName = getNewDictVarName(jcVariableDecl.getName(), annotation);
        return jcClassDecl.defs.stream().filter(x -> {
            if (x.getTree() instanceof JCTree.JCVariableDecl) {
                JCTree.JCVariableDecl tree = (JCTree.JCVariableDecl) x.getTree();
                return tree.getName().toString().equals(dictVarName.toString());
            }else {
                return false;
            }
        }).findAny().isPresent();
    }

    private JCTree.JCVariableDecl makeDictDescFieldDecl(JCTree.JCVariableDecl jcVariableDecl,MyDict dict, java.util.List<ResolvedDescAnnotation> descFieldAnnotations) {
        treeMaker.pos = jcVariableDecl.pos;
        ListBuffer<JCTree.JCAnnotation> annotationsList = new ListBuffer<>();
        try {
            // 检查MyBatis-Plus是否存在于classpath
            Class.forName("com.baomidou.mybatisplus.annotation.TableField");
            JCTree.JCExpression attr1 = treeMaker.Assign(treeMaker.Ident(names.fromString("exist")),
                    treeMaker.Literal(false));
            JCTree.JCAnnotation jcAnnotation = treeMaker.Annotation(memberAccess("com.baomidou.mybatisplus.annotation.TableField"),
                    List.of(attr1));
            annotationsList.append(jcAnnotation);
        }catch (Throwable e){
            // MyBatis-Plus不存在，跳过添加@TableField注解
        }
        for (ResolvedDescAnnotation annotation : descFieldAnnotations) {
            annotationsList.append(treeMaker.Annotation(memberAccess(annotation.fullAnnotationName()), annotation.arguments()));
        }
        long generatedFieldFlags = jcVariableDecl.getModifiers().flags & ~Flags.FINAL;
        return treeMaker.VarDef(treeMaker.Modifiers(generatedFieldFlags,annotationsList.toList()),getNewDictVarName(jcVariableDecl.getName(), dict),memberAccess("java.lang.String"), null);
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
                        variableElement,
                        annotationName,
                        member,
                        arrayType.getComponentType(),
                        var.varType(),
                        rawValue
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

        return buildScalarAnnotationValueExpression(
                variableElement,
                annotationName,
                member,
                memberType,
                var.varType(),
                var.varValue()
        );
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
                        variableElement,
                        annotationName,
                        member,
                        (DeclaredType) expectedType,
                        actualType,
                        rawValue
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

    private JCTree.JCExpression typeTree(TypeMirror typeMirror) {
        return switch (typeMirror.getKind()) {
            case BOOLEAN -> treeMaker.TypeIdent(TypeTag.BOOLEAN);
            case BYTE -> treeMaker.TypeIdent(TypeTag.BYTE);
            case SHORT -> treeMaker.TypeIdent(TypeTag.SHORT);
            case INT -> treeMaker.TypeIdent(TypeTag.INT);
            case LONG -> treeMaker.TypeIdent(TypeTag.LONG);
            case FLOAT -> treeMaker.TypeIdent(TypeTag.FLOAT);
            case DOUBLE -> treeMaker.TypeIdent(TypeTag.DOUBLE);
            case CHAR -> treeMaker.TypeIdent(TypeTag.CHAR);
            case VOID -> treeMaker.TypeIdent(TypeTag.VOID);
            case DECLARED -> {
                DeclaredType declaredType = (DeclaredType) typeMirror;
                TypeElement typeElement = (TypeElement) declaredType.asElement();
                yield memberAccess(typeElement.getQualifiedName().toString());
            }
            default -> memberAccess(typeMirror.toString());
        };
    }

    private JCTree.JCExpression memberAccess(String components){
        String[] componentArray = components.split("\\.");
        JCTree.JCExpression expr = treeMaker.Ident(getNameFromString(componentArray[0]));
        for (int i=1;i<componentArray.length;i++){
            expr = treeMaker.Select(expr,getNameFromString(componentArray[i]));
        }
        return expr;
    }

    /**
     * 生成 getter/setter 方法名
     * 自动处理驼峰命名和蛇形命名
     *
     * 示例：
     * - sexTypeDesc -> getSexTypeDesc / setSexTypeDesc
     * - sex_type_desc -> getSex_type_desc / setSex_type_desc
     */
    private Name getNewMethodName(int methodType, Name name, MyDict annotation) {
        name = getNewDictVarName(name, annotation);
        String s = name.toString();
        String pref = methodType==0?"get":"set";

        // 如果是蛇形命名，保持原样（只在前面加 get/set）
        if (s.contains("_")) {
            return names.fromString(pref + s.substring(0, 1).toUpperCase() + s.substring(1));
        }

        // 驼峰命名：首字母大写
        return names.fromString(pref + s.substring(0, 1).toUpperCase() + s.substring(1, name.length()));
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

    private JCTree.JCMethodDecl makeGetterMethodDecl(JCTree.JCClassDecl jcClassDecl, JCTree.JCVariableDecl jcVariableDecl,MyDict annotation, String dictName) {
        ListBuffer<JCTree.JCStatement> statements = new ListBuffer<>();

        JCTree.JCVariableDecl descStr = treeMaker.VarDef(
                treeMaker.Modifiers(0), names.fromString("descStr"), memberAccess("java.lang.String"),treeMaker.Apply(
                        List.<JCTree.JCExpression>nil(),
                        treeMaker.Select(memberAccess("io.github.canjiemo.tools.dict.MyDictHelper"),
                                elementUtils.getName("getDesc")),
                        List.<JCTree.JCExpression>of(
                                treeMaker.Literal(dictName),
                                readOriginalFieldValue(jcClassDecl, jcVariableDecl)
                        )
                ));
        statements.append(descStr);

        // Check if descStr is not null and not empty: descStr != null && !descStr.isEmpty()
        JCTree.JCBinary notNull = treeMaker.Binary(
                JCTree.Tag.NE,
                treeMaker.Ident(names.fromString("descStr")),
                treeMaker.Literal(TypeTag.BOT, null)
        );

        JCTree.JCMethodInvocation isEmptyCall = treeMaker.Apply(
                com.sun.tools.javac.util.List.nil(),
                treeMaker.Select(treeMaker.Ident(names.fromString("descStr")),
                        elementUtils.getName("isEmpty")),
                com.sun.tools.javac.util.List.nil()
        );

        JCTree.JCUnary notEmpty = treeMaker.Unary(JCTree.Tag.NOT, isEmptyCall);

        JCTree.JCBinary condition = treeMaker.Binary(
                JCTree.Tag.AND,
                notNull,
                notEmpty
        );

        JCTree.JCStatement ifTrue = treeMaker.Return(treeMaker.Ident(names.fromString("descStr")));
        JCTree.JCStatement ifFlase = treeMaker.Return(treeMaker.Literal(annotation.defaultDesc()));

        JCTree.JCIf anIf = treeMaker.If(
                condition,
                ifTrue,
                ifFlase
        );
        statements.append(anIf);
        JCTree.JCBlock body = treeMaker.Block(0, statements.toList());
        return treeMaker.MethodDef(treeMaker.Modifiers(Flags.PUBLIC), getNewMethodName(0,jcVariableDecl.getName(), annotation), memberAccess("java.lang.String"), List.nil(), List.nil(), List.nil(), body, null);
    }

    private Name getNameFromString(String s){
        return names.fromString(s);
    }

    private Name getVarName(Name name) {
        return names.fromString(name.toString());
    }


    /**
     * 根据原字段名的命名风格，生成对应的描述字段名
     * 支持驼峰命名和蛇形命名的自动识别
     *
     * 命名规则（优先级从高到低）：
     * 1. 如果字段名包含下划线，忽略注解开关，始终生成蛇形命名
     * 2. 如果字段名包含大小写混合，忽略注解开关，始终生成驼峰命名
     * 3. 如果字段名全小写无特征，则根据注解的 camelCase 开关决定
     *
     * 示例：
     * - user_status -> user_status_desc (自动识别蛇形)
     * - userName -> userNameDesc (自动识别驼峰)
     * - type + camelCase=true -> typeDesc (开关控制)
     * - type + camelCase=false -> type_desc (开关控制)
     */
    private Name getNewDictVarName(Name name, MyDict annotation) {
        String originalName = name.toString();

        // 优先级1：如果包含下划线，始终使用蛇形命名
        if (originalName.contains("_")) {
            // 判断原名是否全大写
            if (originalName.equals(originalName.toUpperCase())) {
                // 全大写蛇形：SEX_TYPE -> SEX_TYPE_DESC
                return names.fromString(originalName + "_DESC");
            } else {
                // 小写或混合蛇形：sex_type -> sex_type_desc
                return names.fromString(originalName + "_desc");
            }
        }

        // 优先级2：如果包含大小写混合（驼峰），使用驼峰命名
        if (hasMixedCase(originalName)) {
            return names.fromString(originalName + "Desc");
        }

        // 优先级3：全小写无特征，根据注解开关决定
        if (annotation.camelCase()) {
            // 使用驼峰：type -> typeDesc
            return names.fromString(originalName + "Desc");
        } else {
            // 使用蛇形：type -> type_desc
            return names.fromString(originalName + "_desc");
        }
    }

    /**
     * 判断字符串是否包含大小写混合（驼峰命名特征）
     */
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
        return names.fromString("get" + s.substring(0, 1).toUpperCase() + s.substring(1, jcVariableDecl.getName().length()));
    }

    private Name setterMethodName(JCTree.JCVariableDecl jcVariableDecl) {
        String s = jcVariableDecl.getName().toString();
        return names.fromString("set" + s.substring(0, 1).toUpperCase() + s.substring(1, jcVariableDecl.getName().length()));
    }

    private JCTree.JCMethodDecl makeSetterMethod(JCTree.JCVariableDecl jcVariableDecl, MyDict annotation){
        JCTree.JCModifiers jcModifiers = treeMaker.Modifiers(Flags.PUBLIC);
        JCTree.JCExpression retrunType = treeMaker.TypeIdent(TypeTag.VOID);
        List<JCTree.JCVariableDecl> parameters = List.nil();
        JCTree.JCVariableDecl param = treeMaker.VarDef(
                treeMaker.Modifiers(Flags.PARAMETER), getNewDictVarName(jcVariableDecl.name, annotation), memberAccess("java.lang.String"), null);
        param.pos = jcVariableDecl.pos;
        parameters = parameters.append(param);
        JCTree.JCStatement jcStatement = treeMaker.Exec(treeMaker.Assign(
                treeMaker.Select(treeMaker.Ident(names.fromString("this")), getNewDictVarName(jcVariableDecl.name, annotation)),
                treeMaker.Ident(getNewDictVarName(jcVariableDecl.name, annotation))));
        List<JCTree.JCStatement> jcStatementList = List.nil();
        jcStatementList = jcStatementList.append(jcStatement);
        JCTree.JCBlock jcBlock = treeMaker.Block(0, jcStatementList);
        List<JCTree.JCTypeParameter> methodGenericParams = List.nil();
        List<JCTree.JCExpression> throwsClauses = List.nil();
        JCTree.JCMethodDecl jcMethodDecl = treeMaker.MethodDef(jcModifiers, getNewMethodName(1,jcVariableDecl.getName(), annotation), retrunType, methodGenericParams, parameters, throwsClauses, jcBlock,  null);
        return jcMethodDecl;
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        // 解包 IDEA 的 Proxy，兼容增量编译
        ProcessingEnvironment unwrapped = unwrapProcessingEnvironment(processingEnv);
        this.trees = JavacTrees.instance(unwrapped);
        Context context = ((JavacProcessingEnvironment) unwrapped).getContext();
        this.treeMaker = TreeMaker.instance(context);
        this.names = Names.instance(context);
    }

    /**
     * 解包 IntelliJ IDEA 的 ProcessingEnvironment Proxy
     *
     * IDEA 的增量编译环境会将 ProcessingEnvironment 包装成 Proxy，
     * 需要解包才能访问 javac 的内部 API（如 Tree API）。
     *
     * 参考：
     * - https://github.com/mapstruct/mapstruct/issues/2215
     * - https://github.com/javalin/javalin-openapi/issues/141
     *
     * @param processingEnv 原始或被包装的 ProcessingEnvironment
     * @return 解包后的 ProcessingEnvironment
     */
    private ProcessingEnvironment unwrapProcessingEnvironment(ProcessingEnvironment processingEnv) {
        try {
            // 尝试使用 JetBrains 的 APIWrappers 解包
            Class<?> apiWrappers = processingEnv.getClass().getClassLoader()
                    .loadClass("org.jetbrains.jps.javac.APIWrappers");
            java.lang.reflect.Method unwrapMethod = apiWrappers.getDeclaredMethod("unwrap", Class.class, Object.class);
            ProcessingEnvironment unwrapped = (ProcessingEnvironment) unwrapMethod.invoke(
                    null, ProcessingEnvironment.class, processingEnv);

            if (unwrapped != null) {
                // 成功解包，返回真实的 ProcessingEnvironment
                return unwrapped;
            }
        } catch (Throwable ignored) {
            // 不在 IDEA 环境中，或者解包失败，使用原始对象
        }

        // 返回原始对象（标准 javac 环境）
        return processingEnv;
    }
}
