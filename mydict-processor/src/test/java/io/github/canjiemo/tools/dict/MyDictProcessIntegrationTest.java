package io.github.canjiemo.tools.dict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private CompilationResult compile(Path tempDir, String className, String source) throws Exception {
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path sourceFile = tempDir.resolve(className + ".java");
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

    private String normalizeOutput(String output) {
        return output.lines()
                .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                .filter(line -> !line.startsWith("NOTE: Picked up JDK_JAVA_OPTIONS:"))
                .filter(line -> !line.startsWith("Picked up _JAVA_OPTIONS:"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

    private record CompilationResult(int exitCode, String output, Path classesDir) {
        Class<?> loadClass(String className) throws IOException, ClassNotFoundException {
            assertThat(output).isEmpty();
            URL[] urls = {classesDir.toUri().toURL()};
            try (URLClassLoader classLoader = new URLClassLoader(urls, MyDictProcessIntegrationTest.class.getClassLoader())) {
                return classLoader.loadClass(className);
            }
        }
    }
}
