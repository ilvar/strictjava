---
name: strictjava
description: Use strictjava to create, check, and conservatively fix Java projects with deterministic JSON diagnostics, NullAway/JSpecify, and a strict agent-oriented profile.
---

# strictjava

Use this skill whenever you create or modify Java code in a project that uses `strictjava`.

## Required workflow

1. Run `strictjava --help` and treat its embedded manual as the current source of truth.
2. Inspect the project and make the smallest coherent change that advances the task.
3. Run the project's formatter check and tests.
4. Run `strictjava check --classpath "<project compile classpath>" <path>` and parse the single JSON report on stdout.
5. Address every error diagnostic. Do not suppress warnings or invent fixes from diagnostic prose.
6. Use `strictjava fix` only for its allowlisted Error Prone tool-supplied replacements.
7. Repeat the complete check until `ok` is `true`.
8. Inspect the final diff and do not submit known failures.

## Strict-subset expectations

Avoid wildcard imports, `@SuppressWarnings`, mutable global state, `Optional.get()`, native code, runtime halts, catch-all enum/sealed switches, and unmarked reflection or filesystem/network/process/environment access.

Keep capability access in narrowly scoped source files whose first 20 lines contain exactly:

```java
// strictjava: capability
```

Use JSpecify nullness annotations and explicit `@NullMarked` / `@NullUnmarked` scopes. Treat ignored results and resource ownership diagnostics as correctness failures.

## Generated projects

Use `strictjava new <name>` for a deterministic Java 25 project with:

- Gradle 9.8.0 wrapper plus distribution checksum verification;
- strict dependency locking and SHA-256 dependency verification;
- JSpecify 1.0.0;
- checksum-pinned google-java-format 1.36.1;
- built-in smoke tests;
- Docker packaging;
- reusable GitHub Actions CI;
- pre-commit and agent guidance.

In generated projects, `./gradlew -q strictjavaClasspath` prints the classpath that should be passed to strictjava.
