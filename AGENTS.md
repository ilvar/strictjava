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

M2 is implemented and must remain more conservative than IDE quick-fixes.

- The fix allowlist is exactly `ReturnValueIgnored`, `FutureReturnValueIgnored`, `MustBeClosedChecker`, and `StreamResourceLeak` unless an explicit profile change adds another checker with fixtures.
- Use only Error Prone's tool-supplied `-XepPatchChecks` replacements. Never synthesize source edits from diagnostic prose.
- Run one checker at a time in a fixed order. This avoids cross-checker overlap in a single pass.
- Reproduce every candidate fix in two independent temporary source trees and require byte-identical outputs before touching the requested project.
- Never edit a path outside the requested project root and never create/delete source files during a fix.
- Verify originals are unchanged before applying. Multi-file application must restore already-written files if a later write fails.
- Re-run the complete M1 checker after every successful proposal.
- Keep a proposal only if its target diagnostic count decreases, total errors decrease, and it introduces no new diagnostic code; otherwise roll it back.
- Stop with an explicit `clean`, `blocked`, or `iteration_limit` status.
- NullAway/JSpecify diagnostics are not automatically fixed in M2.

## Generated-project policy

M3 project generation is part of the public behavior.

- `strictjava new NAME` must be deterministic for identical inputs.
- Generate into a staging directory and never partially overwrite an existing destination.
- Keep the official Gradle wrapper binary unmodified and pin both the wrapper JAR provenance and distribution SHA-256.
- Generated dependency locking is strict; generated dependency verification is strict for artifacts.
- Keep generated build dependencies minimal. JSpecify is currently the only Gradle dependency.
- Formatter downloads are outside Gradle dependency resolution and therefore must have an explicit published SHA-256 check.
- Generated CI must remain reusable with `workflow_call` and must compile the application distribution only once before the Docker artifact stage.
- Embedded skills install idempotently for detected Codex/Claude installations and must refuse to overwrite modified copies.
- Every generated file belongs in an M3 acceptance fixture/gate; avoid untested scaffold decoration.

## Validation

Current authoritative validation:

```bash
./scripts/test.sh
./gradlew --no-daemon prepareAnalyzers
./scripts/test-m1.sh
./scripts/test-m2.sh
./scripts/test-m3.sh
```

The project target is JDK 25 LTS. Full analyzer checks require JDK 25; `--core-only` is bootstrap/debug mode. Keep the Gradle toolchain, runtime check, and CI Java version synchronized.
