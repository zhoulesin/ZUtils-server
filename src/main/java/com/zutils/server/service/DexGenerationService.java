package com.zutils.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

@Service
public class DexGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DexGenerationService.class);
    private static final long TIMEOUT_SECONDS = 60;

    /**
     * Generate a DEX file from user's Kotlin run() function.
     * The generated DEX contains a simple class with a no-arg constructor
     * and a `run(Map<String, Any?>): Any?` method — no ZFunction dependency.
     * Android side uses an adapter to wrap it as ZFunction.
     */
    public DexResult generate(String userCode, String functionName, String className) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("zutils-dex-");
            Path sourceFile = tempDir.resolve(className + ".kt");
            Path classDir = tempDir.resolve("out");
            Path jarFile = tempDir.resolve("input.jar");
            Path dexOutDir = tempDir.resolve("dex_out");
            Files.createDirectories(classDir);
            Files.createDirectories(dexOutDir);

            String wrapper = buildWrapper(userCode, functionName, className);
            Files.writeString(sourceFile, wrapper, StandardCharsets.UTF_8);

            String jvmCp = getJvmClasspath();
            if (jvmCp.isEmpty()) {
                return DexResult.failure("Cannot build classpath");
            }

            String stdlibJar = findStdlibJar();
            if (stdlibJar.isEmpty()) {
                return DexResult.failure("Cannot find kotlin-stdlib.jar in classpath");
            }

            // Step 1: Compile Kotlin → .class files
            String[] compileCmd = {
                    findJavaBin(), "-cp", jvmCp,
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    sourceFile.toAbsolutePath().toString(),
                    "-d", classDir.toAbsolutePath().toString(),
                    "-classpath", stdlibJar,
                    "-no-reflect"
            };
            if (exec(compileCmd, "Compilation") != 0) {
                return DexResult.failure("Compilation failed");
            }

            // Step 2: .class files → JAR
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile.toFile()))) {
                Files.walk(classDir)
                        .filter(p -> p.toString().endsWith(".class"))
                        .forEach(p -> {
                            try {
                                String entryName = classDir.relativize(p).toString();
                                jos.putNextEntry(new JarEntry(entryName));
                                jos.write(Files.readAllBytes(p));
                                jos.closeEntry();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }

            // Step 3: JAR → DEX via d8 (output must be a directory)
            String d8 = findD8();
            if (d8 == null) {
                return DexResult.failure("d8 tool not found in Android SDK build-tools");
            }
            String[] d8Cmd = {
                    d8, "--release",
                    "--output", dexOutDir.toAbsolutePath().toString(),
                    "--min-api", "24",
                    jarFile.toAbsolutePath().toString()
            };
            if (exec(d8Cmd, "D8") != 0) {
                return DexResult.failure("DEX conversion failed");
            }

            Path dexFile = dexOutDir.resolve("classes.dex");
            if (!Files.exists(dexFile)) {
                return DexResult.failure("DEX output file not found");
            }
            byte[] dexBytes = Files.readAllBytes(dexFile);
            return DexResult.success(dexBytes, dexBytes.length);

        } catch (Exception e) {
            log.error("DEX generation error", e);
            return DexResult.failure(e.getMessage());
        } finally {
            if (tempDir != null) deleteRecursively(tempDir.toFile());
        }
    }

    private String buildWrapper(String userCode, String functionName, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.zutils.generated\n\n");
        sb.append("class ").append(className).append(" {\n");
        sb.append("    val functionName: String = \"").append(functionName).append("\"\n\n");
        sb.append("    fun handle(input: String): String {\n");
        sb.append("        val args = mutableMapOf<String, Any?>()\n");
        sb.append("        val ak = \"\\\"args\\\":\"\n");
        sb.append("        val si = input.indexOf(ak)\n");
        sb.append("        if (si >= 0) {\n");
        sb.append("            var s = input.substring(si + ak.length).trim()\n");
        sb.append("            if (s.startsWith(\"{\")) s = s.substring(1)\n");
        sb.append("            val ei = s.lastIndexOf('}')\n");
        sb.append("            if (ei >= 0) s = s.substring(0, ei)\n");
        sb.append("            while (true) {\n");
        sb.append("                s = s.trim()\n");
        sb.append("                if (!s.startsWith(\"\\\"\") || s.length < 3) break\n");
        sb.append("                val ke = s.indexOf('\"', 1)\n");
        sb.append("                if (ke < 0) break\n");
        sb.append("                val key = s.substring(1, ke)\n");
        sb.append("                s = s.substring(ke + 1).trim()\n");
        sb.append("                if (!s.startsWith(\":\")) break\n");
        sb.append("                s = s.substring(1).trim()\n");
        sb.append("                if (s.startsWith(\"\\\"\")) {\n");
        sb.append("                    val ve = s.indexOf('\"', 1)\n");
        sb.append("                    if (ve < 0) break\n");
        sb.append("                    args[key] = s.substring(1, ve)\n");
        sb.append("                    s = s.substring(ve + 1)\n");
        sb.append("                } else {\n");
        sb.append("                    val ce = s.indexOf(',')\n");
        sb.append("                    val be = s.indexOf('}')\n");
        sb.append("                    val e = when { ce >= 0 && be >= 0 -> minOf(ce, be); ce >= 0 -> ce; be >= 0 -> be; else -> s.length }\n");
        sb.append("                    val raw = s.substring(0, e).trim()\n");
        sb.append("                    args[key] = raw.toDoubleOrNull() ?: raw\n");
        sb.append("                    s = s.substring(e)\n");
        sb.append("                }\n");
        sb.append("                s = s.trimStart { it == ',' }\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        val result = run(args)\n");
        sb.append("        val r = result?.toString() ?: \"null\"\n");
        sb.append("        val jr = r.replace(\"\\\\\", \"\\\\\\\\\").replace(\"\\\"\", \"\\\\\\\"\")\n");
        sb.append("        return \"{\\\"result\\\":\\\"\" + jr + \"\\\",\\\"type\\\":\\\"TEXT\\\"}\"\n");
        sb.append("    }\n\n");
        sb.append("    private fun run(args: Map<String, Any?>): Any? {\n");
        sb.append(indent(userCode, 8)).append("\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private String findStdlibJar() {
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

    private String findJavaBin() {
        String home = System.getProperty("java.home");
        return home + "/bin/java";
    }

    private String getJvmClasspath() {
        // Use full server classpath so subprocess has kotlin-stdlib etc.
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isEmpty()) return cp;
        // Fallback: scan Maven local repo
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

    private String findD8() {
        List<String> bases = new ArrayList<>();
        addIfSet(bases, System.getenv("ANDROID_HOME"));
        addIfSet(bases, System.getProperty("ANDROID_HOME"));
        bases.add(System.getProperty("user.home") + "/Library/Android/sdk");
        bases.add("/Users/zhouxin/Library/Android/sdk");
        List<String> versions = List.of("37.0.0", "36.1.0", "36.0.0", "35.0.0");
        for (String base : bases) {
            if (base == null || base.isEmpty()) continue;
            for (String v : versions) {
                Path p = Path.of(base, "build-tools", v, "d8");
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private void addIfSet(List<String> list, String value) {
        if (value != null && !value.isEmpty()) list.add(value);
    }

    private int exec(String[] cmd, String label) {
        try {
            log.info("{}: {}", label, String.join(" ", cmd));
            Process p = Runtime.getRuntime().exec(cmd);
            String err = readStream(p.getErrorStream());
            String out = readStream(p.getInputStream());
            boolean ok = p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                log.warn("{} timed out", label);
                return -1;
            }
            if (p.exitValue() != 0) {
                log.warn("{} failed ({}):\n{}", label, p.exitValue(), err);
            }
            return p.exitValue();
        } catch (Exception e) {
            log.error("{} error", label, e);
            return -1;
        }
    }

    private String readStream(InputStream is) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
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

    public static class DexResult {
        private final boolean success;
        private final byte[] dexBytes;
        private final int size;
        private final String error;

        private DexResult(boolean success, byte[] dexBytes, int size, String error) {
            this.success = success;
            this.dexBytes = dexBytes;
            this.size = size;
            this.error = error;
        }

        public static DexResult success(byte[] dexBytes, int size) {
            return new DexResult(true, dexBytes, size, null);
        }
        public static DexResult failure(String error) {
            return new DexResult(false, null, 0, error);
        }

        public boolean isSuccess() { return success; }
        public byte[] getDexBytes() { return dexBytes; }
        public int getSize() { return size; }
        public String getError() { return error; }
    }
}
