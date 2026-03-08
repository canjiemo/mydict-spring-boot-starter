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
                    @MyDict(name = "status_dict")
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
                    @MyDict(name = "status_dict")
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
                    @MyDict(name = "status_dict")
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
    void compilesWhenUsingValueShortcut(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "ValueShortcut", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class ValueShortcut {
                    @MyDict("status_dict")
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("ValueShortcut");
        assertThat(compiledClass.getDeclaredField("statusDesc")).isNotNull();
        assertThat(compiledClass.getDeclaredMethod("getStatusDesc")).isNotNull();
    }

    @Test
    void failsWhenDictNameIsMissing(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "MissingDictName", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class MissingDictName {
                    @MyDict
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("@MyDict requires a dictionary name");
    }

    @Test
    void failsWhenValueAndNameConflict(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "ConflictingDictName", """
                import io.github.canjiemo.tools.dict.MyDict;

                public class ConflictingDictName {
                    @MyDict(value = "status_dict", name = "user_status")
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("@MyDict value and name must match");
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
                        value = "status_dict",
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
    void compilesWhenUsingDeprecatedFieldAnnotationsAlias(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.LegacyFieldAnnotations", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class LegacyFieldAnnotations {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface LegacyMeta {
                        String value();
                    }

                    @MyDict(
                        value = "status_dict",
                        fieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.LegacyFieldAnnotations.LegacyMeta",
                                vars = {
                                    @Var(varType = VarType.STRING, varName = "value", varValue = "legacy")
                                }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isZero();
        Class<?> compiledClass = result.loadClass("sample.LegacyFieldAnnotations");
        Field generatedField = compiledClass.getDeclaredField("statusDesc");
        Annotation annotation = generatedField.getDeclaredAnnotations()[0];
        Class<?> annotationType = annotation.annotationType();
        assertThat(annotation).isNotNull();
        assertThat(readAnnotationValue(annotationType, annotation, "value")).isEqualTo("legacy");
    }

    @Test
    void failsWhenUsingNewAndLegacyAnnotationPropertiesTogether(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.ConflictingDescAnnotations", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;
                import io.github.canjiemo.tools.dict.entity.Var;
                import io.github.canjiemo.tools.dict.entity.VarType;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                public class ConflictingDescAnnotations {
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.FIELD)
                    public @interface SampleMeta {
                        String value();
                    }

                    @MyDict(
                        value = "status_dict",
                        descFieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.ConflictingDescAnnotations.SampleMeta",
                                vars = { @Var(varType = VarType.STRING, varName = "value", varValue = "new") }
                            )
                        },
                        fieldAnnotations = {
                            @FieldAnnotation(
                                fullAnnotationName = "sample.ConflictingDescAnnotations.SampleMeta",
                                vars = { @Var(varType = VarType.STRING, varName = "value", varValue = "old") }
                            )
                        }
                    )
                    private Integer status = 1;
                }
                """);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("cannot use descFieldAnnotations and deprecated fieldAnnotations at the same time");
    }

    @Test
    void failsWhenDescAnnotationTypeDoesNotExist(@TempDir Path tempDir) throws Exception {
        CompilationResult result = compile(tempDir, "sample.MissingDescAnnotationType", """
                package sample;

                import io.github.canjiemo.tools.dict.FieldAnnotation;
                import io.github.canjiemo.tools.dict.MyDict;

                public class MissingDescAnnotationType {
                    @MyDict(
                        value = "status_dict",
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
                        value = "status_dict",
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
                        value = "status_dict",
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
                        value = "status_dict",
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
