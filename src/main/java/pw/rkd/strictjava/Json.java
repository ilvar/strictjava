package pw.rkd.strictjava;

import java.util.StringJoiner;
import pw.rkd.strictjava.model.Report;
import pw.rkd.strictjava.model.SourcePosition;
import pw.rkd.strictjava.model.SourceSpan;
import pw.rkd.strictjava.model.StrictDiagnostic;

final class Json {
    private Json() {}

    static String report(Report report) {
        StringJoiner diagnostics = new StringJoiner(",", "[", "]");
        for (StrictDiagnostic diagnostic : report.diagnostics()) {
            diagnostics.add(diagnostic(diagnostic));
        }
        return "{"
                + "\"ok\":" + report.ok() + ","
                + "\"error_count\":" + report.errorCount() + ","
                + "\"warning_count\":" + report.warningCount() + ","
                + "\"diagnostics\":" + diagnostics
                + "}";
    }

    static String operationalError(String message) {
        return "{\"ok\":false,\"error_count\":0,\"warning_count\":0,\"diagnostics\":[],"
                + "\"operational_error\":" + quote(message) + "}";
    }

    private static String diagnostic(StrictDiagnostic diagnostic) {
        StringBuilder out = new StringBuilder("{");
        out.append("\"level\":").append(quote(diagnostic.level()));
        out.append(",\"source\":").append(quote(diagnostic.source()));
        if (diagnostic.code() != null) {
            out.append(",\"code\":").append(quote(diagnostic.code()));
        }
        out.append(",\"message\":").append(quote(diagnostic.message()));
        if (diagnostic.at() != null) {
            out.append(",\"at\":").append(span(diagnostic.at()));
        }
        return out.append('}').toString();
    }

    private static String span(SourceSpan span) {
        return "{"
                + "\"file\":" + quote(span.file()) + ","
                + "\"start\":" + position(span.start()) + ","
                + "\"end\":" + position(span.end()) + ","
                + "\"snippet\":" + quote(span.snippet())
                + "}";
    }

    private static String position(SourcePosition position) {
        return "{"
                + "\"line\":" + position.line() + ","
                + "\"column\":" + position.column() + ","
                + "\"offset\":" + position.offset()
                + "}";
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
