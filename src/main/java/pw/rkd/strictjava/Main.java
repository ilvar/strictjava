package pw.rkd.strictjava;

import java.nio.file.Path;
import pw.rkd.strictjava.check.StrictJavaChecker;

public final class Main {
    private static final String HELP = """
            strictjava — deterministic strict Java feedback loop for coding agents

            USAGE
              strictjava check [--classpath PATHS] [PATH]
              strictjava [PATH]
              strictjava --help

            COMMANDS
              check [PATH]   Check Java sources below PATH. PATH defaults to '.'.
              --classpath    Pass a platform-separated dependency classpath to javac.
              --help         Print this help text.

            OUTPUT
              check writes exactly one JSON document to stdout and exits:
                0  no diagnostics
                1  source/compiler diagnostics remain
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

              javac warnings are promoted to errors. Compiler diagnostics and
              strictjava diagnostics are normalized and deterministically ordered.
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

            Path path;
            String classpath = null;
            if (args.length == 0) {
                path = Path.of(".");
            } else if (args[0].equals("check")) {
                int index = 1;
                if (index < args.length && (args[index].equals("--classpath") || args[index].equals("--class-path"))) {
                    if (index + 1 >= args.length) {
                        return invocationError("--classpath requires PATHS");
                    }
                    classpath = args[index + 1];
                    index += 2;
                }
                if (args.length - index > 1) {
                    return invocationError("check accepts at most one PATH");
                }
                path = index < args.length ? Path.of(args[index]) : Path.of(".");
            } else if (args.length == 1 && !args[0].startsWith("-")) {
                path = Path.of(args[0]);
            } else {
                return invocationError("invalid invocation; run strictjava --help");
            }

            var report = new StrictJavaChecker(path, classpath).check();
            System.out.println(Json.report(report));
            return report.ok() ? 0 : 1;
        } catch (Exception exception) {
            System.out.println(Json.operationalError(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage()));
            return 2;
        }
    }

    private static int invocationError(String message) {
        System.out.println(Json.operationalError(message));
        return 2;
    }
}
