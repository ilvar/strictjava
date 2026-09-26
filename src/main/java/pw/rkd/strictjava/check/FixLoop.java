package pw.rkd.strictjava.check;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import pw.rkd.strictjava.model.FixResult;
import pw.rkd.strictjava.model.Report;
import pw.rkd.strictjava.model.StrictDiagnostic;

public final class FixLoop {
    private static final List<String> FIXABLE_CHECKS = List.of(
            "ReturnValueIgnored",
            "FutureReturnValueIgnored",
            "MustBeClosedChecker",
            "StreamResourceLeak");

    private final Path project;
    private final String classpath;
    private final String analyzerPath;

    public FixLoop(Path project, String classpath, String analyzerPath) {
        this.project = project.toAbsolutePath().normalize();
        this.classpath = classpath;
        this.analyzerPath = analyzerPath;
    }

    public FixResult run(int maxPasses) throws IOException {
        if (maxPasses < 1) {
            throw new IllegalArgumentException("max passes must be at least 1");
        }

        StrictJavaChecker checker = new StrictJavaChecker(project, classpath, analyzerPath);
        ErrorProneFixer fixer = new ErrorProneFixer(project, classpath, analyzerPath);
        Report report = checker.check();
        if (report.ok()) {
            return result(report, "clean", 0, List.of(), List.of(), null);
        }

        LinkedHashSet<String> changedFiles = new LinkedHashSet<>();
        List<String> appliedChecks = new ArrayList<>();
        int passes = 0;

        while (passes < maxPasses) {
            boolean applied = false;
            for (String checkName : FIXABLE_CHECKS) {
                String publicCode = "errorprone::" + checkName;
                if (count(report, publicCode) == 0) {
                    continue;
                }

                ErrorProneFixer.FixProposal proposal;
                try {
                    proposal = fixer.propose(checkName);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fix invocation interrupted", exception);
                }
                if (proposal.isEmpty()) {
                    continue;
                }

                long beforeErrors = report.errorCount();
                long beforeTarget = count(report, publicCode);
                Set<String> beforeCodes = codes(report);

                proposal.apply();
                Report after;
                try {
                    after = checker.check();
                } catch (IOException exception) {
                    proposal.rollback();
                    throw exception;
                }

                boolean progress = after.errorCount() < beforeErrors
                        && count(after, publicCode) < beforeTarget
                        && beforeCodes.containsAll(codes(after));
                if (!progress) {
                    proposal.rollback();
                    continue;
                }

                passes++;
                applied = true;
                changedFiles.addAll(proposal.changedFiles());
                appliedChecks.add(publicCode);
                report = after;
                if (report.ok()) {
                    return result(
                            report,
                            "clean",
                            passes,
                            List.copyOf(changedFiles),
                            appliedChecks,
                            null);
                }
                break;
            }

            if (!applied) {
                return result(
                        report,
                        "blocked",
                        passes,
                        List.copyOf(changedFiles),
                        appliedChecks,
                        "no allowlisted tool-supplied fix made safe progress");
            }
        }

        return result(
                report,
                report.ok() ? "clean" : "iteration_limit",
                passes,
                List.copyOf(changedFiles),
                appliedChecks,
                report.ok() ? null : "maximum fix passes reached");
    }

    private long count(Report report, String code) {
        return report.diagnostics().stream()
                .filter(diagnostic -> code.equals(diagnostic.code()))
                .count();
    }

    private Set<String> codes(Report report) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (StrictDiagnostic diagnostic : report.diagnostics()) {
            if (diagnostic.code() != null) {
                codes.add(diagnostic.code());
            }
        }
        return codes;
    }

    private FixResult result(
            Report report,
            String status,
            int passes,
            List<String> changedFiles,
            List<String> appliedChecks,
            String blockedReason) {
        return new FixResult(report, status, passes, changedFiles, appliedChecks, blockedReason);
    }
}
