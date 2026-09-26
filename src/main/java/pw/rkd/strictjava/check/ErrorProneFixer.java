// strictjava: capability
package pw.rkd.strictjava.check;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ErrorProneFixer {
    private final Path project;
    private final String classpath;
    private final String analyzerPath;

    ErrorProneFixer(Path project, String classpath, String analyzerPath) {
        this.project = project.toAbsolutePath().normalize();
        this.classpath = classpath;
        this.analyzerPath = analyzerPath;
    }

    FixProposal propose(String checkName) throws IOException, InterruptedException {
        Snapshot original = snapshot();
        Map<Path, String> first = patchedSnapshot(checkName, original);
        Map<Path, String> second = patchedSnapshot(checkName, original);
        if (!first.equals(second)) {
            throw new IOException("non-deterministic Error Prone fix output for " + checkName);
        }

        Map<Path, String> replacements = new LinkedHashMap<>();
        for (Path relative : original.contents().keySet()) {
            String before = original.contents().get(relative);
            String after = first.get(relative);
            if (!before.equals(after)) {
                replacements.put(relative, after);
            }
        }
        return new FixProposal(project, original.contents(), replacements);
    }

    private Snapshot snapshot() throws IOException {
        List<Path> sources = SourceFiles.find(project);
        Map<Path, String> contents = new LinkedHashMap<>();
        for (Path source : sources) {
            Path relative = relative(source);
            contents.put(relative, Files.readString(source, StandardCharsets.UTF_8));
        }
        return new Snapshot(contents);
    }

    private Map<Path, String> patchedSnapshot(String checkName, Snapshot original)
            throws IOException, InterruptedException {
        Path workspace = Files.createTempDirectory("strictjava-fix-");
        try {
            List<Path> sources = new ArrayList<>();
            for (Map.Entry<Path, String> entry : original.contents().entrySet()) {
                Path target = workspace.resolve(entry.getKey()).normalize();
                if (!target.startsWith(workspace)) {
                    throw new IOException("source path escapes temporary fix workspace: " + entry.getKey());
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                sources.add(target);
            }
            sources.sort(Comparator.comparing(Path::toString));

            Path classes = workspace.resolve(".strictjava-classes");
            Files.createDirectories(classes);

            List<String> command = new ArrayList<>();
            command.add(javacExecutable().toString());
            addJavacExports(command);
            command.add("-XDrawDiagnostics");
            command.add("-Xmaxerrs");
            command.add("10000");
            command.add("-Xmaxwarns");
            command.add("10000");
            command.add("-proc:none");
            command.add("-XDcompilePolicy=simple");
            command.add("--should-stop=ifError=FLOW");
            command.add("-processorpath");
            command.add(analyzerPath);
            command.add(errorProneFlags(checkName));
            command.add("-d");
            command.add(classes.toString());

            String effectiveClasspath = effectiveClasspath();
            if (!effectiveClasspath.isBlank()) {
                command.add("-classpath");
                command.add(effectiveClasspath);
            }
            for (Path source : sources) {
                command.add(source.toString());
            }

            Process process = new ProcessBuilder(command)
                    .directory(workspace.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();

            Map<Path, String> after = new LinkedHashMap<>();
            for (Path relative : original.contents().keySet()) {
                Path source = workspace.resolve(relative).normalize();
                if (!source.startsWith(workspace) || !Files.isRegularFile(source)) {
                    throw new IOException("Error Prone changed source-file set while fixing " + checkName);
                }
                after.put(relative, Files.readString(source, StandardCharsets.UTF_8));
            }
            try (var paths = Files.walk(workspace)) {
                long javaFiles = paths.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .count();
                if (javaFiles != original.contents().size()) {
                    throw new IOException("Error Prone changed source-file set while fixing " + checkName);
                }
            }

            if (status != 0 && after.equals(original.contents()) && looksOperational(output)) {
                throw new IOException("Error Prone fix invocation failed: " + concise(output));
            }
            return after;
        } finally {
            deleteRecursively(workspace);
        }
    }

    private String errorProneFlags(String checkName) {
        return "-Xplugin:ErrorProne "
                + "-XepDisableAllChecks "
                + "-Xep:" + checkName + ":ERROR "
                + "-XepPatchChecks:" + checkName + " "
                + "-XepPatchLocation:IN_PLACE";
    }

    private String effectiveClasspath() {
        if (classpath == null || classpath.isBlank()) {
            return analyzerPath;
        }
        return analyzerPath + File.pathSeparator + classpath;
    }

    private Path relative(Path source) throws IOException {
        if (Files.isRegularFile(project)) {
            return Path.of(project.getFileName().toString());
        }
        if (!source.startsWith(project)) {
            throw new IOException("source is outside project root: " + source);
        }
        return project.relativize(source);
    }

    private Path javacExecutable() throws IOException {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "javac.exe"
                : "javac";
        Path javac = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(javac)) {
            throw new IOException("javac not found below java.home: " + javac);
        }
        return javac;
    }

    private void addJavacExports(List<String> command) {
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");
        command.add("-J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED");
        command.add("-J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED");
        command.add("-J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED");
    }

    private boolean looksOperational(String output) {
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("exception")
                || lower.contains("invalid flag")
                || lower.contains("invalid command")
                || lower.contains("could not find")
                || lower.contains("not found");
    }

    private String concise(String output) {
        String joined = output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .limit(3)
                .reduce((left, right) -> left + " | " + right)
                .orElse("unknown error");
        return joined.length() <= 500 ? joined : joined.substring(0, 500);
    }

    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temporary cleanup failure must not hide a fix result.
        }
    }

    record FixProposal(Path project, Map<Path, String> originals, Map<Path, String> replacements) {
        FixProposal {
            originals = Map.copyOf(originals);
            replacements = Map.copyOf(replacements);
        }

        boolean isEmpty() {
            return replacements.isEmpty();
        }

        List<String> changedFiles() {
            return replacements.keySet().stream()
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }

        void apply() throws IOException {
            verifyOriginals();
            List<Path> written = new ArrayList<>();
            try {
                for (Path relative : replacements.keySet().stream().sorted().toList()) {
                    write(relative, replacements.get(relative));
                    written.add(relative);
                }
            } catch (IOException exception) {
                IOException rollbackFailure = null;
                for (Path relative : written.reversed()) {
                    try {
                        write(relative, originals.get(relative));
                    } catch (IOException rollbackException) {
                        if (rollbackFailure == null) {
                            rollbackFailure = rollbackException;
                        } else {
                            rollbackFailure.addSuppressed(rollbackException);
                        }
                    }
                }
                if (rollbackFailure != null) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        }

        void rollback() throws IOException {
            for (Path relative : replacements.keySet().stream().sorted().toList()) {
                write(relative, originals.get(relative));
            }
        }

        private void verifyOriginals() throws IOException {
            for (Path relative : replacements.keySet()) {
                Path target = target(relative);
                String current = Files.readString(target, StandardCharsets.UTF_8);
                if (!current.equals(originals.get(relative))) {
                    throw new IOException("source changed while fix was being prepared: " + relative);
                }
            }
        }

        private void write(Path relative, String content) throws IOException {
            Path target = target(relative);
            Path temporary = Files.createTempFile(target.getParent(), ".strictjava-", ".tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        private Path target(Path relative) throws IOException {
            Path target;
            if (Files.isRegularFile(project)) {
                target = project;
            } else {
                target = project.resolve(relative).normalize();
                if (!target.startsWith(project)) {
                    throw new IOException("fix path escapes project root: " + relative);
                }
            }
            if (!Files.isRegularFile(target)) {
                throw new IOException("fix target is not an existing source file: " + target);
            }
            return target;
        }
    }

    private record Snapshot(Map<Path, String> contents) {
        Snapshot {
            contents = Map.copyOf(contents);
        }
    }
}
