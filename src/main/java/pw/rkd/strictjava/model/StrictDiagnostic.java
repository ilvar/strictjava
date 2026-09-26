package pw.rkd.strictjava.model;

import java.util.Comparator;

public record StrictDiagnostic(
        String level,
        String source,
        String code,
        String message,
        SourceSpan at) {

    public static Comparator<StrictDiagnostic> stableOrder() {
        return Comparator
                .comparing((StrictDiagnostic d) -> d.at() == null ? "" : d.at().file())
                .thenComparingLong(d -> d.at() == null ? -1 : d.at().start().line())
                .thenComparingLong(d -> d.at() == null ? -1 : d.at().start().column())
                .thenComparing(StrictDiagnostic::code, Comparator.nullsFirst(String::compareTo))
                .thenComparing(StrictDiagnostic::message);
    }
}
