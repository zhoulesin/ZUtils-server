package com.zutils.server.service;

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Service
public class KotlinSandboxService {

    private static final Logger log = LoggerFactory.getLogger(KotlinSandboxService.class);
    private static final long TIMEOUT_SECONDS = 15;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kotlin-sandbox");
        t.setDaemon(true);
        return t;
    });

    public TestResult compileAndRun(String userCode, Map<String, Object> testArgs) {
        long start = System.nanoTime();
        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("zutils-kt-");

            Path sourceFile = tempDir.resolve("TestRunner.kt");
            Path outputDir = tempDir.resolve("out");
            Files.createDirectories(outputDir);

            String wrappedCode = wrapCode(userCode, testArgs);
            Files.writeString(sourceFile, wrappedCode, StandardCharsets.UTF_8);

            String javaBin = findJavaBin();

            String stdlibJar = findStdlibJar();
            if (stdlibJar.isEmpty()) {
                return TestResult.failure("Cannot find kotlin-stdlib.jar");
            }

            // Step 1: Compile — output to directory, no -include-runtime
            // Kotlin's -include-runtime tries to locate stdlib by path relative to compiler jar,
            // which fails for kotlin-compiler-embeddable (stdlib is a separate Maven artifact).
            // Instead, compile to .class files and add stdlib at runtime.
            String jvmCp = getJvmClasspath();
            String[] compileCmd = {
                javaBin, "-cp", jvmCp,
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                sourceFile.toAbsolutePath().toString(),
                "-d", outputDir.toAbsolutePath().toString(),
                "-classpath", stdlibJar,
                "-no-reflect"
            };

            Process compileProc = Runtime.getRuntime().exec(compileCmd);
            String compileErr = readStream(compileProc.getErrorStream());
            int compileExit = compileProc.waitFor(30, TimeUnit.SECONDS) ? compileProc.exitValue() : -1;

            if (compileExit != 0) {
                log.warn("Kotlin compilation failed:\n{}", compileErr);
                return TestResult.failure("Compilation failed:\n" + compileErr);
            }

            // Step 2: Run — classpath = compiled classes + kotlin-stdlib
            String runCp = outputDir.toAbsolutePath() + File.pathSeparator + stdlibJar;

            String result = executor.submit(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                        javaBin, "-cp", runCp, "TestRunnerKt"
                    );
                    pb.redirectErrorStream(true);
                    Process runProc = pb.start();

                    boolean finished = runProc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (!finished) {
                        runProc.destroyForcibly();
                        throw new RuntimeException("Execution timed out (> " + TIMEOUT_SECONDS + "s)");
                    }

                    String output = readStream(runProc.getInputStream());
                    if (runProc.exitValue() != 0) {
                        throw new RuntimeException("Runtime error (exit " + runProc.exitValue() + "):\n" + output);
                    }
                    return output.trim();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Execution was interrupted");
                }
            }).get(TIMEOUT_SECONDS + 5, TimeUnit.SECONDS);

            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return TestResult.success(result, durationMs);

        } catch (TimeoutException e) {
            return TestResult.failure("Execution timed out (> " + TIMEOUT_SECONDS + "s)");
        } catch (Exception e) {
            log.error("Sandbox execution error", e);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return TestResult.failure(e.getMessage() != null ? e.getMessage() : "Unknown error");
        } finally {
            if (tempDir != null) deleteRecursively(tempDir.toFile());
        }
    }

    private String wrapCode(String userCode, Map<String, Object> args) {
        String argsMap = args.entrySet().stream()
                .map(e -> "\"" + escape(e.getKey()) + "\" to " + toKotlinLiteral(e.getValue()))
                .collect(Collectors.joining(",\n"));

        return """
                fun run(args: Map<String, Any?>): Any? {
                %s
                }

                fun main() {
                    val testArgs = mapOf(
                %s
                    )
                    val result = run(testArgs)
                    println(result?.toString() ?: "null")
                }
                """.formatted(
                indent(userCode, 4),
                indent(argsMap, 8)
        );
    }

    private String findJavaBin() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) javaHome = "/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home";
        return javaHome + "/bin/java";
    }

    private String findStdlibJar() {
        // Find kotlin-stdlib.jar from classpath or Maven repo
        String cp = System.getProperty("java.class.path");
        if (cp != null) {
            for (String entry : cp.split(File.pathSeparator)) {
                if (entry.endsWith(".jar") && entry.contains("kotlin-stdlib")) {
                    return entry;
                }
            }
        }
        // Fallback: scan Maven local repo
        String m2 = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        try {
            return Files.walk(Path.of(m2))
                    .filter(p -> p.toString().contains("kotlin-stdlib"))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("sources"))
                    .filter(p -> !p.toString().contains("javadoc"))
                    .findFirst()
                    .map(Path::toString)
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private String getJvmClasspath() {
        // Use full server classpath so the compiler JVM has all dependencies
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty()) return cp;
        // Fallback: scan Maven local repo for everything
        String m2 = Path.of(System.getProperty("user.home"), ".m2", "repository").toString();
        try {
            return Files.walk(Path.of(m2))
                    .filter(p -> p.toString().endsWith(".jar"))
                    .filter(p -> !p.toString().contains("sources"))
                    .filter(p -> !p.toString().contains("javadoc"))
                    .map(Path::toString)
                    .collect(Collectors.joining(File.pathSeparator));
        } catch (Exception e) {
            return "";
        }
    }

    private String readStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String text = reader.lines().collect(Collectors.joining("\n"));
        reader.close();
        return text;
    }

    private String toKotlinLiteral(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return "\"" + escape(s) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            String entries = m.entrySet().stream()
                    .map(e -> "\"" + escape(String.valueOf(e.getKey())) + "\" to " + toKotlinLiteral(e.getValue()))
                    .collect(Collectors.joining(", "));
            return "mapOf(" + entries + ")";
        }
        if (value instanceof Collection<?> c) {
            String items = c.stream().map(this::toKotlinLiteral).collect(Collectors.joining(", "));
            return "listOf(" + items + ")";
        }
        return "\"" + escape(value.toString()) + "\"";
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String indent(String code, int spaces) {
        String prefix = " ".repeat(spaces);
        return Arrays.stream(code.split("\n"))
                .map(line -> prefix + line)
                .collect(Collectors.joining("\n"));
    }

    private void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }

    public static class TestResult {
        private final boolean success;
        private final String output;
        private final String error;
        private final long durationMs;

        private TestResult(boolean success, String output, String error, long durationMs) {
            this.success = success;
            this.output = output;
            this.error = error;
            this.durationMs = durationMs;
        }

        public static TestResult success(String output, long durationMs) {
            return new TestResult(true, output, null, durationMs);
        }

        public static TestResult failure(String error) {
            return new TestResult(false, null, error, 0);
        }

        public boolean isSuccess() { return success; }
        public String getOutput() { return output; }
        public String getError() { return error; }
        public long getDurationMs() { return durationMs; }
    }
}
