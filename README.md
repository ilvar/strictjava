# strictjava

`strictjava` is a strict Java profile plus a deterministic, machine-readable diagnostic loop for coding agents.

It is not a new language, parser, compiler fork, or standard library. It uses the JDK compiler and AST APIs, normalizes compiler diagnostics, adds a deliberately small set of source rules, and emits one stable JSON report suitable for a check → patch → re-check loop.

## Status

Early implementation. **M0 and M1 are implemented.** The core checker remains dependency-free; the full M1 profile runs a pinned external analyzer bundle containing Error Prone, NullAway, and JSpecify behind the same deterministic JSON contract. Safe fixes, project generation, and agent-skill installation remain roadmap work.

The repository targets **JDK 25 LTS**. Full checks require JDK 25 so compiler/analyzer behavior does not silently vary across Java releases. `--core-only` remains available for bootstrap/debugging. CI runs on Temurin 25.

## Usage

Build the runnable JAR and prepare the pinned M1 analyzer bundle:

```bash
./scripts/build.sh
gradle prepareAnalyzers
```

`prepareAnalyzers` materializes the pinned analyzer runtime into `build/analyzers/`, next to `build/strictjava.jar` where the CLI discovers it automatically.

Check a project:

```bash
java -jar build/strictjava.jar check path/to/project
```

For projects with external dependencies, pass the compile classpath explicitly:

```bash
java -jar build/strictjava.jar check --classpath "lib/*:build/deps/*" path/to/project
```

`--class-path` is accepted as an alias. The value uses the platform classpath separator. For JSpecify-annotated projects, the project compile classpath must include `org.jspecify:jspecify:1.0.0` (the prepared analyzer bundle already contains it for the included fixtures).

For analyzer debugging or bootstrap only, skip the external analyzer pass explicitly:

```bash
java -jar build/strictjava.jar check --core-only path/to/project
```

A bare path is equivalent to a full `check` and therefore requires the analyzer bundle:

```bash
java -jar build/strictjava.jar path/to/project
```

Print the embedded agent-facing help:

```bash
java -jar build/strictjava.jar --help
```

Operational checks emit exactly one JSON document to stdout and use exit status `0` when clean, `1` when diagnostics remain, and `2` for invocation or operational failures.

## Diagnostic contract

Example:

```json
{
  "ok": false,
  "error_count": 1,
  "warning_count": 0,
  "diagnostics": [
    {
      "level": "error",
      "source": "strictjava",
      "code": "strictjava::no_optional_get",
      "message": "Optional.get() is not allowed; handle the empty case explicitly",
      "at": {
        "file": "src/main/java/example/App.java",
        "start": {"line": 12, "column": 20, "offset": 246},
        "end": {"line": 12, "column": 31, "offset": 257},
        "snippet": "        String s = value.get();"
      }
    }
  ]
}
```

Diagnostics are deterministically ordered by `(file, line, column, code, message)`. Javac warnings are promoted to errors in the strict profile. Current diagnostic sources are `javac`, `strictjava`, `errorprone`, and `nullaway`.

## Current strict subset

| Stable code | Banned construct | Preferred direction |
| --- | --- | --- |
| `strictjava::no_wildcard_import` | `import x.*` | explicit imports |
| `strictjava::no_suppress_warnings` | `@SuppressWarnings` | resolve the diagnostic or create a narrowly designed future policy exemption |
| `strictjava::no_mutable_global` | static object/mutable state | explicitly owned/injected state; only compile-time constants stay global |
| `strictjava::no_optional_get` | `Optional.get()` | explicit empty-case handling |
| `strictjava::no_system_exit` | `System.exit()` outside a Java 25 candidate `main` method | return/throw to the top-level boundary |
| `strictjava::no_runtime_halt` | `Runtime.exit()` / `Runtime.halt()` | ordinary return/exception control flow |
| `strictjava::no_native_code` | `native` methods and `System`/`Runtime` native-library loading | keep implementation inside the JVM |
| `strictjava::no_reflection` | selected reflection APIs outside a capability source file | ordinary typed APIs or an explicit capability boundary |
| `strictjava::capability_boundary` | filesystem/process/environment/network effects in ordinary source files | isolate effects in a marked capability source file |
| `strictjava::no_catchall_switch` | `default` on enum/sealed switches | enumerate all variants |

The rules are intentionally few. `strictjava` is not intended to become a large style-guide checker.

### M1 analyzer profile

The full check runs a deliberately small analyzer allowlist rather than Error Prone's full default catalogue:

| Stable code | Purpose |
| --- | --- |
| `nullaway::NullAway` | JSpecify-aware nullness errors |
| `nullaway::RequireExplicitNullMarking` | require every top-level class to be explicitly in a `@NullMarked` or `@NullUnmarked` scope |
| `nullaway::JSpecifyUnrecognizedAnnotationLocation` | reject JSpecify nullness annotations in locations JSpecify does not recognize |
| `errorprone::ReturnValueIgnored` | reject ignored return values for APIs whose results must be used |
| `errorprone::FutureReturnValueIgnored` | reject ignored `Future` results |
| `errorprone::MustBeClosedChecker` | enforce `@MustBeClosed` resource ownership |
| `errorprone::StreamResourceLeak` | reject unclosed resource-backed streams |

The pinned analyzer versions are:

- Error Prone `2.50.0`
- NullAway `0.14.2`
- JSpecify `1.0.0`

NullAway runs with `OnlyNullMarked=true`, JSpecify mode, optional-emptiness checking, and explicit null-marking enforcement. Analyzer diagnostics are normalized into the same report and stable ordering as compiler and handwritten `strictjava::` diagnostics.

### Capability source files

Effects that touch the filesystem, process execution, environment/system properties, or selected network APIs must be isolated in an explicitly marked source file:

```java
// strictjava: capability
package example.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ConfigFile {
    static String load(Path path) throws IOException {
        return Files.readString(path);
    }
}
```

The exact marker must appear within the first 20 source lines. Reflection is also permitted only inside such a boundary. `System.exit()` and `Runtime.exit()`/`halt()` are not capability exemptions: their dedicated rules still apply.

For Java 25, `strictjava` follows the launcher definition of a candidate `main`: it may be static or instance, accepts either no parameters or one `String[]`/`String...` parameter, returns `void`, and is not private. `System.exit()` is permitted only inside such a method.

## Why compiler APIs first

M0 uses `javax.tools.JavaCompiler`, `DiagnosticListener`, `JavacTask`, and `Trees`. That gives structured compiler locations and typed AST access without scraping human javac output or introducing a second parser.

Error Prone and NullAway run as an external pinned javac analyzer pass. Their diagnostics are converted to stable `errorprone::` / `nullaway::` codes and merged into the same public report schema; analyzer implementation details do not define the JSON API.

## Development

Run the core acceptance suite:

```bash
./scripts/test.sh
```

Prepare analyzers and run the full M1 suite on JDK 25:

```bash
gradle prepareAnalyzers
./scripts/test-m1.sh
```

The suites cover compiler failures/warnings, exact ordered handwritten rules, capability boundaries, classpaths, native/JVM escape hatches, Java 25 `main` semantics, NullAway/JSpecify failures, selected Error Prone ownership/result checks, deterministic repeated output, analyzer auto-discovery, and self-dogfooding.

The build targets Gradle `9.8.0` in CI. A Gradle wrapper remains intentionally deferred to M3 together with generated-project support.

## Roadmap

- **M0 — deterministic diagnostic oracle:** functional
  - javac compiler diagnostics
  - stable JSON report
  - deterministic ordering
  - stable exit semantics
- **M1 — strict subset:** implemented
  - handwritten high-value source rules
  - JSpecify + NullAway with explicit null-marking enforcement
  - curated Error Prone result/resource checks
  - capability boundaries for core JDK filesystem/network/process/environment/reflection APIs
  - explicit dependency classpaths and deterministic analyzer normalization
- **M2 — conservative fix loop:** planned
  - only allowlisted, tool-supplied fixes
  - deterministic non-overlapping edits
  - re-check after every pass
- **M3 — generated project:** planned
  - `strictjava new NAME`
  - Gradle wrapper and dependency locking/verification
  - formatting, tests, Docker and reusable CI
  - agent-skill installation
- **M4 — property and architecture checks:** planned
  - property-test integration with an AI-compatible license
  - optional ArchUnit-based architecture constraints

## Non-goals

- new Java syntax;
- a custom Java parser;
- a javac fork;
- hundreds of formatting/style rules;
- automatic fixes inferred from diagnostic prose.
