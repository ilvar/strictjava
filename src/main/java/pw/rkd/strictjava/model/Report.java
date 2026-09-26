package pw.rkd.strictjava.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Report {
    private final List<StrictDiagnostic> diagnostics;

    public Report(List<StrictDiagnostic> diagnostics) {
        var copy = new ArrayList<>(diagnostics);
        copy.sort(StrictDiagnostic.stableOrder());
        this.diagnostics = Collections.unmodifiableList(copy);
    }

    public List<StrictDiagnostic> diagnostics() {
        return diagnostics;
    }

    public long errorCount() {
        return diagnostics.stream().filter(d -> d.level().equals("error")).count();
    }

    public long warningCount() {
        return diagnostics.stream().filter(d -> d.level().equals("warning")).count();
    }

    public boolean ok() {
        return errorCount() == 0;
    }
}
