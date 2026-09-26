package pw.rkd.strictjava.model;

import java.util.List;

public record FixResult(
        Report report,
        String status,
        int passes,
        List<String> changedFiles,
        List<String> appliedChecks,
        String blockedReason) {

    public FixResult {
        changedFiles = List.copyOf(changedFiles);
        appliedChecks = List.copyOf(appliedChecks);
    }
}
