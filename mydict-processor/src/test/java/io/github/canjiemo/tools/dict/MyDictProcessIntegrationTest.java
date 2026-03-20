package io.github.canjiemo.tools.dict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.canjiemo.tools.dict.MyDictCacheProperties;
import io.github.canjiemo.tools.dict.MyDictHelper;

import static org.assertj.core.api.Assertions.assertThat;

class MyDictProcessIntegrationTest {

    private static final List<String> JAVA_TOOL_ENV_VARS = List.of(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS"
    );

    private static final List<String> JAVAC_EXPORTS = List.of(
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED"
    );

    @Test
    void compilesWhenSourceFieldHasNoGetter(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "WithoutGetter", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class WithoutGetter {
                    @MyDict(type = "status_dict")
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("WithoutGetter");
        assertThat(compiledClass.getDeclaredField("statusDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getStatusDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("setStatusDesc", String.class)).isNotNull();
    }

    @Test
    void compilesWhenAnnotatedFieldIsFinal(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "FinalField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class FinalField {
                    @MyDict(type = "status_dict")
                    private final Integer status = 1;

                    public Integer getStatus() {
                        return status;
                    }
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("FinalField");
        Field generatedField = compiledClass.getDeclaredField("statusDesc");
        assertThat(Modifier.isFinal(generatedField.getModifiers())).isFalse();
    }

    @Test
    void keepsManualDescAccessorsWithoutGeneratingDuplicates(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "ManualDescMethods", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class ManualDescMethods {
                    @MyDict(type = "status_dict")
                    private Integer status = 1;

                    public Integer getStatus() {
                        return status;
                    }

                    public String getStatusDesc() {
                        return "manual";
                    }

                    public void setStatusDesc(String statusDesc) {
                    }
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("ManualDescMethods");
        assertThat(countMethods(compiledClass, "getStatusDesc")).isEqualTo(1);
        assertThat(countMethods(compiledClass, "setStatusDesc")).isEqualTo(1);
    }

    @Test
    void generatesCamelCaseFalseFieldAndMethodNames(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "CamelCaseFalse", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class CamelCaseFalse {
                    @MyDict(type = "status_dict", camelCase = false)
                    private String status;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("CamelCaseFalse");
        assertThat(compiledClass.getDeclaredField("status_desc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getStatus_desc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("setStatus_desc", String.class)).isNotNull();
    }

    // ── 命名规则 ─────────────────────────────────────────────────────────────

    @Test
    void generatesSnakeCaseDescForSnakeCaseField(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "SnakeCaseField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class SnakeCaseField {
                    @MyDict(type = "status_dict")
                    private Integer user_status;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("SnakeCaseField");
        assertThat(compiledClass.getDeclaredField("user_status_desc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getUser_status_desc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("setUser_status_desc", String.class)).isNotNull();
    }

    @Test
    void generatesAllCapsDescForAllCapsSnakeCaseField(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "AllCapsField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class AllCapsField {
                    @MyDict(type = "sex_type_dict")
                    private Integer SEX_TYPE;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("AllCapsField");
        assertThat(compiledClass.getDeclaredField("SEX_TYPE_DESC")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getSEX_TYPE_DESC")).isNotNull();
    }

    @Test
    void generatesCamelCaseDescForMixedCaseFieldIgnoringSwitch(@TempDir Path tempDir) throws Exception {
        // 大小写混合字段（userName）不受 camelCase 开关影响，始终生成驼峰
        CompilationResult result = compile(tempDir, "MixedCaseField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class MixedCaseField {
                    @MyDict(type = "name_dict", camelCase = false)
                    private String userName;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("MixedCaseField");
        assertThat(compiledClass.getDeclaredField("userNameDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getUserNameDesc")).isNotNull();
    }

    // ── 多字段 ───────────────────────────────────────────────────────────────

    @Test
    void compilesWithMultipleAnnotatedFieldsOnSameClass(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "MultiField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class MultiField {
                    @MyDict(type = "status_dict")
                    private Integer status;

                    @MyDict(type = "type_dict")
                    private String type;

                    @MyDict(type = "gender_dict")
                    private Integer gender;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("MultiField");
        assertThat(compiledClass.getDeclaredField("statusDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredField("typeDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredField("genderDesc")).isNotNull();
    }

    @Test
    void doesNotGenerateDescFieldWhenAlreadyDeclaredManually(@TempDir Path tempDir) throws Exception {
        // 手动定义了 statusDesc 字段时，不应重复生成
        CompilationResult result = compile(tempDir, "ManualDescField", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class ManualDescField {
                    @MyDict(type = "status_dict")
                    private Integer status;

                    private String statusDesc = "手动定义";
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("ManualDescField");
        // 只有一个 statusDesc 字段
        long count = java.util.Arrays.stream(compiledClass.getDeclaredFields())
                .filter(f -> f.getName().equals("statusDesc"))
                .count();
        assertThat(count).isEqualTo(1);
    }

    // ── 运行时 getter 行为 ───────────────────────────────────────────────────

    @Test
    void generatedGetterReturnsDescFromDictLookup(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "RuntimeLookup", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class RuntimeLookup {
                    @MyDict(type = "status", defaultDesc = "未知")
                    private String status;

                    public String getStatus() { return status; }
                    public void setStatus(String v) { this.status = v; }
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("RuntimeLookup");

        MyDictCacheProperties props = new MyDictCacheProperties();
        props.setEnabled(false);
        new MyDictHelper((type, value) -> "active".equals(value) ? "已激活" : null, props);

        Object instance = compiledClass.getDeclaredConstructor().newInstance();
        Method setter = compiledClass.getMethod("setStatus", String.class);
        Method getter = compiledClass.getDeclaredMethod("getStatusDesc");

        // 字典有值 → 返回字典值
        setter.invoke(instance, "active");
        assertThat(getter.invoke(instance)).isEqualTo("已激活");

        // 字典无值 → 返回 defaultDesc
        setter.invoke(instance, "unknown");
        assertThat(getter.invoke(instance)).isEqualTo("未知");

        // 字段为 null → 也返回 defaultDesc
        setter.invoke(instance, (Object) null);
        assertThat(getter.invoke(instance)).isEqualTo("未知");
    }

    @Test
    void generatedGetterUsesExistingGetterToReadField(@TempDir Path tempDir) throws Exception {
        // 原字段有 getter 时，生成的 getter 应通过 getter 读值而非直接访问字段
        CompilationResult result = compile(tempDir, "WithGetter", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class WithGetter {
                    @MyDict(type = "status")
                    private Integer status = 1;

                    public Integer getStatus() {
                        return status * 10; // getter 返回放大后的值，验证是走 getter 还是直接访问字段
                    }
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("WithGetter");

        MyDictCacheProperties props = new MyDictCacheProperties();
        props.setEnabled(false);
        new MyDictHelper((type, value) -> "got:" + value, props);

        Object instance = compiledClass.getDeclaredConstructor().newInstance();
        Method getter = compiledClass.getDeclaredMethod("getStatusDesc");

        // status=1，getter 返回 10；若走 getter，dict 查的是 "10"，否则查 "1"
        assertThat(getter.invoke(instance)).isEqualTo("got:10");
    }

    // ── 错误场景 ─────────────────────────────────────────────────────────────

    @Test
    void failsWhenVarValueAndVarValuesAreBothSet(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.BothVarValueAndVarValues", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class BothVarValueAndVarValues {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String[] tags() default {};
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.BothVarValueAndVarValues.SampleMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "tags",
                                         varValue = "single", varValues = {"a", "b"})
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("cannot set both varValue and varValues");
    }

    @Test
    void failsWhenDuplicateVarNameInDescAnnotation(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.DuplicateVarName", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class DuplicateVarName {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String value() default "";
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.DuplicateVarName.SampleMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "value", varValue = "first"),
                                    @Var(varType = VarType.STRING, varName = "value", varValue = "second")
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Duplicate annotation member 'value'");
    }

    @Test
    void failsWhenFullAnnotationNameIsNotAnAnnotationType(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.NotAnAnnotation", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;

                public class NotAnAnnotation {
                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(fullAnnotationName = "java.lang.String", vars = {})
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("is not an annotation type");
    }

    @Test
    void failsWhenDictTypeIsMissing(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "MissingDictType", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class MissingDictType {
                    @MyDict(type = "")
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("@MyDict requires a dictionary type");
    }

    @Test
    void compilesWhenUsingDescFieldAnnotationsWithExpandedTypes(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.GeneratedFieldAnnotations", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class GeneratedFieldAnnotations {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String description();
                        boolean hidden() default false;
                        Class<?> implementation() default Void.class;
                        SampleMode mode() default SampleMode.FIRST;
                        String[] tags() default {};
                        Class<?>[] groups() default {};
                        SampleMode[] modes() default {};
                    }

                    public enum SampleMode {
                        FIRST,
                        SECOND
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.GeneratedFieldAnnotations.SampleMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "description", varValue = "商品类型描述"),
                                    @Var(varType = VarType.BOOLEAN, varName = "hidden", varValue = "true"),
                                    @Var(varType = VarType.CLASS, varName = "implementation", varValue = "java.lang.String"),
                                    @Var(varType = VarType.ENUM, varName = "mode", varValue = "SECOND"),
                                    @Var(varType = VarType.STRING, varName = "tags", varValues = {"core", "dict"}),
                                    @Var(varType = VarType.CLASS, varName = "groups", varValues = {"java.lang.String", "java.lang.Integer"}),
                                    @Var(varType = VarType.ENUM, varName = "modes", varValues = {"FIRST", "SECOND"})
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("sample.GeneratedFieldAnnotations");
        Field generatedField = compiledClass.getDeclaredField("statusDesc");
        Annotation annotation = generatedField.getDeclaredAnnotations()[0];
        Class<?> annotationType = annotation.annotationType();
        assertThat(annotation).isNotNull();
        assertThat(readAnnotationValue(annotationType, annotation, "description")).isEqualTo("商品类型描述");
        assertThat(readAnnotationValue(annotationType, annotation, "hidden")).isEqualTo(true);
        assertThat(readAnnotationValue(annotationType, annotation, "implementation")).isEqualTo(String.class);
        assertThat(readAnnotationValue(annotationType, annotation, "mode").toString()).isEqualTo("SECOND");
        assertThat(Arrays.asList((String[]) readAnnotationValue(annotationType, annotation, "tags")))
                .containsExactly("core", "dict");
        assertThat(Arrays.asList((Class<?>[]) readAnnotationValue(annotationType, annotation, "groups")))
                .containsExactly(String.class, Integer.class);
        assertThat(Arrays.stream((Object[]) readAnnotationValue(annotationType, annotation, "modes"))
                .map(Object::toString)
                .toList())
                .containsExactly("FIRST", "SECOND");
    }

    @Test
    void failsWhenDescAnnotationTypeDoesNotExist(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.MissingDescAnnotationType", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;

                public class MissingDescAnnotationType {
                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(fullAnnotationName = "sample.DoesNotExist", vars = {})
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Cannot resolve desc field annotation type 'sample.DoesNotExist'");
    }

    @Test
    void failsWhenDescAnnotationMemberDoesNotExist(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.InvalidDescAnnotationMember", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class InvalidDescAnnotationMember {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String value();
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.InvalidDescAnnotationMember.SampleMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "missing", varValue = "oops")
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("does not declare member 'missing'");
    }

    @Test
    void failsWhenRequiredDescAnnotationMemberIsMissing(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.RequiredDescAnnotationMemberMissing", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class RequiredDescAnnotationMemberMissing {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String description();
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.RequiredDescAnnotationMemberMissing.SampleMeta",
                                vars = {}
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("requires member 'description'");
    }

    @Test
    void failsWhenDescAnnotationMemberTypeDoesNotMatch(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.DescAnnotationTypeMismatch", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class DescAnnotationTypeMismatch {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        Class<?> implementation();
                    }

                    @MyDict(
                        type = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.DescAnnotationTypeMismatch.SampleMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "implementation", varValue = "java.lang.String")
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("expects type 'java.lang.Class<?>', but received VarType.STRING");
    }

    private CompilationResult compile(Path tempDir, String className, String source) throws Exception {
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path sourceFile = tempDir.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("-processorpath");
        command.add(System.getProperty("java.class.path"));
        command.add("-d");
        command.add(classesDir.toString());
        for (String export : JAVAC_EXPORTS) {
            command.add("-J" + export);
        }
        command.add(sourceFile.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        JAVA_TOOL_ENV_VARS.forEach(processBuilder.environment()::remove);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new CompilationResult(exitCode, normalizeOutput(output), classesDir);
    }

    private long countMethods(Class<?> type, String methodName) {
        long count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                count++;
            }
        }
        return count;
    }

    private Object readAnnotationValue(Class<?> annotationType, Annotation annotation, String memberName) throws Exception {
        Method method = annotationType.getDeclaredMethod(memberName);
        method.setAccessible(true);
        return method.invoke(annotation);
    }

    private String normalizeOutput(String output) {
        return output.lines()
                .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                .filter(line -> !line.startsWith("NOTE: Picked up JDK_JAVA_OPTIONS:"))
                .filter(line -> !line.startsWith("Picked up _JAVA_OPTIONS:"))
                .filter(line -> !line.startsWith("Note: "))
                .filter(line -> !line.startsWith("注: "))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private record CompilationResult(int exitCode, String output, Path classesDir) {
        Class<?> loadClass(String className) throws IOException, ClassNotFoundException {
            assertThat(output).isEmpty();
            URL[] urls = {classesDir.toUri().toURL()};
            URLClassLoader classLoader = new URLClassLoader(urls, MyDictProcessIntegrationTest.class.getClassLoader());
            return classLoader.loadClass(className);
        }
    }
}
