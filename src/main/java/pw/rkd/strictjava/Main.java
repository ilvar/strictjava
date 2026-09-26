// strictjava: capability
package pw.rkd.strictjava;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import pw.rkd.strictjava.check.FixLoop;
import pw.rkd.strictjava.check.StrictJavaChecker;

public final class Main {
    private static final String HELP = """
            strictjava — deterministic strict Java feedback loop for coding agents

            USAGE
              strictjava check [OPTIONS] [PATH]
              strictjava fix [OPTIONS] [PATH]
              strictjava [PATH]
              strictjava --help

            OPTIONS
              --classpath PATHS      Pass a platform-separated dependency classpath to javac.
              --class-path PATHS     Alias for --classpath.
              --analyzer-path PATHS  Override the Error Prone / NullAway processor path.
              --core-only            Run javac + strictjava rules without external analyzers.
              --max-passes N         Maximum successful fix passes (fix only; default 8).

            TOOLCHAIN
              Full M1/M2 operations require JDK 25. --core-only is available for check
              bootstrap/debugging; fix always requires the pinned analyzer bundle.

            ANALYZERS
              Normal checks require the pinned M1 analyzer bundle. strictjava resolves it in
              this order: --analyzer-path, STRICTJAVA_ANALYZER_PATH, then an analyzers/
              directory next to strictjava.jar. Use --core-only only for bootstrap/debugging.

              nullaway::NullAway
              nullaway::RequireExplicitNullMarking
              nullaway::JSpecifyUnrecognizedAnnotationLocation
              errorprone::ReturnValueIgnored
              errorprone::FutureReturnValueIgnored
              errorprone::MustBeClosedChecker
              errorprone::StreamResourceLeak

            FIXING
              fix applies only Error Prone's own suggested replacements from this allowlist:
                errorprone::ReturnValueIgnored
                errorprone::FutureReturnValueIgnored
                errorprone::MustBeClosedChecker
                errorprone::StreamResourceLeak
              Each checker runs alone in two temporary copies. Only byte-identical proposals
              that reduce diagnostics without introducing new diagnostic codes are committed.

            OUTPUT
              check/fix write exactly one JSON document to stdout and exit:
                0  no diagnostics remain
                1  diagnostics remain or fixing is blocked/limited
                2  invocation or operational failure

            STRICT PROFILE
              strictjava::no_wildcard_import
              strictjava::no_suppress_warnings
              strictjava::no_mutable_global
              strictjava::no_optional_get
              strictjava::no_system_exit
              strictjava::no_runtime_halt
              strictjava::no_native_code
              strictjava::no_reflection
              strictjava::capability_boundary
              strictjava::no_catchall_switch

              javac warnings are promoted to errors. Compiler, analyzer, and strictjava
              diagnostics are normalized and deterministically ordered.
            """;

    private Main() {}

    public static void main(String[] args) {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h") || args[0].equals("help"))) {
                System.out.print(HELP);
                return 0;
            }

            Invocation invocation = parse(args);
            if (!invocation.coreOnly() && Runtime.version().feature() != 25) {
                return invocationError("full strictjava checks require JDK 25; use --core-only only for bootstrap/debugging");
            }
            if (invocation.fix() && invocation.coreOnly()) {
                return invocationError("fix requires the pinned analyzer bundle; --core-only is check-only");
            }
            String analyzerPath = invocation.coreOnly() ? null : resolveAnalyzerPath(invocation.analyzerPath());
            if (!invocation.coreOnly() && analyzerPath == null) {
                return invocationError(
                        "analyzer jars not found; run 'gradle prepareAnalyzers', pass --analyzer-path, "
                                + "or use --core-only for check bootstrap/debugging");
            }

            if (invocation.fix()) {
                var result = new FixLoop(invocation.path(), invocation.classpath(), analyzerPath)
                        .run(invocation.maxPasses());
                System.out.println(Json.fixResult(result));
                return result.report().ok() ? 0 : 1;
            }

            var report = new StrictJavaChecker(invocation.path(), invocation.classpath(), analyzerPath).check();
            System.out.println(Json.report(report));
            return report.ok() ? 0 : 1;
        } catch (Exception exception) {
            System.out.println(Json.operationalError(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
            return 2;
        }
    }

    private static Invocation parse(String[] args) {
        if (args.length == 0) {
            return new Invocation(Path.of("."), null, null, false, false, 8);
        }

        boolean fix = args[0].equals("fix");
        boolean check = args[0].equals("check");
        if (!check && !fix) {
            if (args.length == 1 && !args[0].startsWith("-")) {
                return new Invocation(Path.of(args[0]), null, null, false, false, 8);
            }
            throw new IllegalArgumentException("invalid invocation; run strictjava --help");
        }

        String classpath = null;
        String analyzerPath = null;
        boolean coreOnly = false;
        int maxPasses = 8;
        boolean maxPassesSet = false;
        Path path = null;
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if (argument.equals("--classpath") || argument.equals("--class-path")) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--classpath requires PATHS");
                }
                classpath = args[index];
            } else if (argument.equals("--analyzer-path")) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--analyzer-path requires PATHS");
                }
                analyzerPath = args[index];
            } else if (argument.equals("--core-only")) {
                coreOnly = true;
            } else if (argument.equals("--max-passes")) {
                if (++index >= args.length) {
                    throw new IllegalArgumentException("--max-passes requires N");
                }
                try {
                    maxPasses = Integer.parseInt(args[index]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("--max-passes requires an integer", exception);
                }
                if (maxPasses < 1 || maxPasses > 100) {
                    throw new IllegalArgumentException("--max-passes must be between 1 and 100");
                }
                maxPassesSet = true;
            } else if (argument.startsWith("-")) {
                throw new IllegalArgumentException("unknown option: " + argument);
            } else if (path == null) {
                path = Path.of(argument);
            } else {
                throw new IllegalArgumentException((fix ? "fix" : "check") + " accepts at most one PATH");
            }
        }
        if (!fix && maxPassesSet) {
            throw new IllegalArgumentException("--max-passes is valid only with fix");
        }
        return new Invocation(path == null ? Path.of(".") : path, classpath, analyzerPath, coreOnly, fix, maxPasses);
    }

    private static String resolveAnalyzerPath(String explicit) throws Exception {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String environment = System.getenv("STRICTJAVA_ANALYZER_PATH");
        if (environment != null && !environment.isBlank()) {
            return environment;
        }
        Path directory = analyzerDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        List<Path> jars = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(jars::add);
        }
        if (jars.isEmpty()) {
            return null;
        }
        return String.join(File.pathSeparator, jars.stream().map(Path::toString).toList());
    }

    private static Path analyzerDirectory() throws URISyntaxException {
        Path location = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(location)) {
            return location.getParent().resolve("analyzers");
        }
        return location.resolveSibling("analyzers");
    }

    private static int invocationError(String message) {
        System.out.println(Json.operationalError(message));
        return 2;
    }

    private record Invocation(
            Path path,
            String classpath,
            String analyzerPath,
            boolean coreOnly,
            boolean fix,
            int maxPasses) {}
}
