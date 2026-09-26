package pw.rkd.strictjava.model;

public record SourceSpan(
        String file,
        SourcePosition start,
        SourcePosition end,
        String snippet) {}
