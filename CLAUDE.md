# AGENTS.md

## Project intent

`strictjava` is a strict subset/profile of ordinary Java plus a deterministic diagnostic interface for coding agents. Keep Java syntax and semantics unchanged.

Do not introduce a custom parser, javac fork, new language syntax, or a large style-guide ruleset.

## Priorities

1. deterministic compiler diagnostic oracle;
2. small, high-value strict-subset rules;
3. conservative mechanical fix loop;
4. reproducible generated Gradle project;
5. property and architecture testing.

## Diagnostic contract

Treat stdout JSON as the public API.

- output exactly one JSON document for operational commands;
- use `source = "javac"` or `source = "strictjava"` for current diagnostics;
- use stable `strictjava::` codes for policy diagnostics;
- include exact source spans when available;
- sort deterministically by `(file, line, column, code, message)`;
- promote javac warnings to strict errors;
- exit `0` only when no error diagnostics remain;
- never parse or depend on javac's human rendering when the compiler API exposes structured data.

Future Error Prone/NullAway integration must normalize into this contract rather than changing it casually.

## Rule policy

Prefer compiler/type information over text matching. Reuse JDK APIs and established analyzers before implementing equivalent analysis locally.

A new rule must remove meaningful ambiguity, unsafe escape hatches, hidden failure handling, or architecture leakage. Do not add subjective formatting rules.

Every stable rule needs a deliberately broken fixture and an acceptance assertion for its code and ordering.

Capability effects use the exact `// strictjava: capability` source-file marker within the first 20 lines. Keep capability files narrow. Do not use the marker as a general lint suppression mechanism.

## Fix policy

M2 fixes must be more conservative than IDE quick-fixes:

- apply only explicit allowlisted tool-provided replacements;
- never synthesize source edits from diagnostic prose;
- never edit outside the requested project root;
- reject overlapping edits unless the deterministic policy selects one unambiguously;
- re-run the complete checker after every edit pass;
- stop on clean, blocked, unchanged, or iteration cap.

## Validation

Current authoritative validation:

```bash
./scripts/test.sh
```

The project target is JDK 25 LTS. Keep the Gradle toolchain and CI Java version synchronized.
