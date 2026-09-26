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
- use `source = "javac"`, `source = "strictjava"`, `source = "errorprone"`, or `source = "nullaway"`;
- use stable `strictjava::` codes for policy diagnostics;
- include exact source spans when available;
- sort deterministically by `(file, line, column, code, message)`;
- promote javac warnings to strict errors;
- exit `0` only when no error diagnostics remain;
- never parse or depend on javac's human rendering when the compiler API exposes structured data.

Error Prone/NullAway diagnostics must normalize into this contract. Their public codes are `errorprone::<CheckName>` and `nullaway::<CheckName>`; do not expose unstable javac `error.prone` wrapper codes as the public identity.

## Rule policy

Prefer compiler/type information over text matching. Reuse JDK APIs and established analyzers before implementing equivalent analysis locally.

A new rule must remove meaningful ambiguity, unsafe escape hatches, hidden failure handling, or architecture leakage. Do not add subjective formatting rules.

Every stable rule needs a deliberately broken fixture and an acceptance assertion for its code and ordering.

The M1 analyzer allowlist is intentionally narrow: NullAway, RequireExplicitNullMarking, JSpecifyUnrecognizedAnnotationLocation, ReturnValueIgnored, FutureReturnValueIgnored, MustBeClosedChecker, and StreamResourceLeak. Do not enable the full Error Prone default catalogue without an explicit profile decision and fixtures.

Pinned M1 analyzer versions are Error Prone 2.50.0, NullAway 0.14.2, and JSpecify 1.0.0. Keep `build.gradle.kts`, help, README, tests, and CI coherent when upgrading them.

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

The project target is JDK 25 LTS. Full analyzer checks require JDK 25; `--core-only` is bootstrap/debug mode. Keep the Gradle toolchain, runtime check, and CI Java version synchronized.
