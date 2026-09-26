// strictjava: capability
package pw.rkd.strictjava.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pw.rkd.strictjava.model.SourcePosition;
import pw.rkd.strictjava.model.SourceSpan;
import pw.rkd.strictjava.model.StrictDiagnostic;

final class AnalyzerRunner {
    private static final String DRAW_DIAGNOSTIC_REGEX = "^(.*):(\\d+):(\\d+): ([^:]+): (.*)$";
    private static final String CHECK_NAME_REGEX = "\\[([A-Za-z][A-Za-z0-9_]*)]";
    private static final String ERROR_PRONE_FLAGS =
            "-Xplugin:ErrorProne "
                    + "-XepDisableAllChecks "
                    + "-Xep:NullAway:ERROR "
                    + "-Xep:RequireExplicitNullMarking:ERROR "
                    + "-Xep:JSpecifyUnrecognizedAnnotationLocation:ERROR "
                    + "-Xep:ReturnValueIgnored:ERROR "
                    + "-Xep:FutureReturnValueIgnored:ERROR "
                    + "-Xep:MustBeClosedChecker:ERROR "
                    + "-Xep:StreamResourceLeak:ERROR "
                    + "-XepOpt:NullAway:OnlyNullMarked=true "
                    + "-XepOpt:NullAway:JSpecifyMode=true "
                    + "-XepOpt:NullAway:CheckOptionalEmptiness=true";

    private final Path project;
    private final String classpath;
    private final String analyzerPath;
    private final Map<Path, String> sourceText;

    AnalyzerRunner(Path project, String classpath, String analyzerPath, Map<Path, String> sourceText) {
        this.project = project;
        this.classpath = classpath;
        this.analyzerPath = analyzerPath;
        this.sourceText = sourceText;
    }

    List<StrictDiagnostic> run(List<Path> sources) throws IOException, InterruptedException {
        Path output = Files.createTempDirectory("strictjava-analyzer-classes-");
        try {
            List<String> command = new ArrayList<>();
            command.add(javacExecutable().toString());
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
            command.add("-XDrawDiagnostics");
            command.add("-Xmaxerrs");
            command.add("10000");
            command.add("-Xmaxwarns");
            command.add("10000");
            command.add("-proc:none");
            command.add("-XDcompilePolicy=simple");
            command.add("-XDaddTypeAnnotationsToSymbol=true");
            command.add("--should-stop=ifError=FLOW");
            command.add("-processorpath");
            command.add(analyzerPath);
            command.add(ERROR_PRONE_FLAGS);
            command.add("-d");
            command.add(output.toString());
            if (classpath != null && !classpath.isBlank()) {
                command.add("-classpath");
                command.add(classpath);
            }
            for (Path source : sources) {
                command.add(source.toString());
            }

            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String outputText = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            List<StrictDiagnostic> diagnostics = parse(outputText);
            if (status != 0 && diagnostics.isEmpty()) {
                throw new IOException("analyzer invocation failed: " + concise(outputText));
            }
            return diagnostics;
        } finally {
            deleteRecursively(output);
        }
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

    private List<StrictDiagnostic> parse(String output) {
        List<StrictDiagnostic> diagnostics = new ArrayList<>();
        for (String line : output.lines().toList()) {
            Matcher diagnostic = Pattern.compile(DRAW_DIAGNOSTIC_REGEX).matcher(line);
            if (!diagnostic.matches()) {
                continue;
            }
            String javacCode = diagnostic.group(4);
            if (!javacCode.contains("error.prone")) {
                continue;
            }
            String rawMessage = diagnostic.group(5);
            Matcher check = Pattern.compile(CHECK_NAME_REGEX).matcher(rawMessage);
            if (!check.find()) {
                continue;
            }
            String checkName = check.group(1);
            String message = rawMessage.substring(check.end()).stripLeading();
            if (message.startsWith(":")) {
                message = message.substring(1).stripLeading();
            }
            Path file = Path.of(diagnostic.group(1)).toAbsolutePath().normalize();
            long lineNumber = Long.parseLong(diagnostic.group(2));
            long column = Long.parseLong(diagnostic.group(3));
            String text = sourceText.getOrDefault(file, "");
            long offset = offset(text, lineNumber, column);
            String source = isNullAway(checkName) ? "nullaway" : "errorprone";
            String code = source + "::" + checkName;
            diagnostics.add(new StrictDiagnostic(
                    "error",
                    source,
                    code,
                    message,
                    new SourceSpan(
                            relative(file),
                            new SourcePosition(lineNumber, column, offset),
                            new SourcePosition(lineNumber, column, offset),
                            snippet(text, lineNumber))));
        }
        return diagnostics;
    }

    private boolean isNullAway(String checkName) {
        return checkName.equals("NullAway")
                || checkName.equals("RequireExplicitNullMarking")
                || checkName.equals("JSpecifyUnrecognizedAnnotationLocation");
    }

    private long offset(String text, long line, long column) {
        long currentLine = 1;
        long currentColumn = 1;
        for (int i = 0; i < text.length(); i++) {
            if (currentLine == line && currentColumn == column) {
                return i;
            }
            if (text.charAt(i) == '\n') {
                currentLine++;
                currentColumn = 1;
            } else {
                currentColumn++;
            }
        }
        return text.length();
    }

    private String snippet(String text, long line) {
        if (text.isEmpty() || line < 1) {
            return "";
        }
        long current = 1;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                if (current == line) {
                    return text.substring(start, i);
                }
                current++;
                start = i + 1;
            }
        }
        return "";
    }

    private String relative(Path file) {
        if (Files.isRegularFile(project)) {
            return project.getFileName().toString();
        }
        if (file.startsWith(project)) {
            return project.relativize(file).toString().replace('\\', '/');
        }
        return file.toString().replace('\\', '/');
    }

    private String concise(String output) {
        String singleLine = output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .limit(3)
                .reduce((left, right) -> left + " | " + right)
                .orElse("unknown error");
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Temp cleanup failure must not hide analyzer diagnostics.
        }
    }
}
