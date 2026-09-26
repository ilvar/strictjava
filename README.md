# strictjava

`strictjava` is a strict Java profile plus a deterministic, machine-readable diagnostic loop for coding agents.

It is not a new language, parser, compiler fork, or standard library. It uses the JDK compiler and AST APIs, normalizes compiler diagnostics, adds a deliberately small set of source rules, and emits one stable JSON report suitable for a check → patch → re-check loop.

## Status

Early implementation. **M0 is functional and M1 has its first rules.** The current checker is intentionally dependency-free and targets ordinary JDK-only Java source trees; build-tool classpath integration, Error Prone, NullAway/JSpecify, safe fixes, project generation, and agent-skill installation remain roadmap work.

The repository targets **JDK 25 LTS**. The build file is pinned to Java 25 and CI runs on Temurin 25.

## Usage

Build the runnable JAR:

```bash
./scripts/build.sh
```

Check a project:

```bash
java -jar build/strictjava.jar check path/to/project
```

For projects with external dependencies, pass the compile classpath explicitly:

```bash
java -jar build/strictjava.jar check --classpath "lib/*:build/deps/*" path/to/project
```

`--class-path` is accepted as an alias. The value uses the platform classpath separator.

A bare path is equivalent to `check`:

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

Diagnostics are deterministically ordered by `(file, line, column, code, message)`. Javac warnings are promoted to errors in the strict profile.

## Current strict subset

| Stable code | Banned construct | Preferred direction |
| --- | --- | --- |
| `strictjava::no_wildcard_import` | `import x.*` | explicit imports |
| `strictjava::no_suppress_warnings` | `@SuppressWarnings` | resolve the diagnostic or create a narrowly designed future policy exemption |
| `strictjava::no_mutable_global` | static object/mutable state | explicitly owned/injected state; only compile-time constants stay global |
| `strictjava::no_optional_get` | `Optional.get()` | explicit empty-case handling |
| `strictjava::no_system_exit` | `System.exit()` outside `main` | return/throw to the top-level boundary |
| `strictjava::no_runtime_halt` | `Runtime.exit()` / `Runtime.halt()` | ordinary return/exception control flow |
| `strictjava::no_native_code` | `native` methods and `System`/`Runtime` native-library loading | keep implementation inside the JVM |
| `strictjava::no_reflection` | selected reflection APIs outside a capability source file | ordinary typed APIs or an explicit capability boundary |
| `strictjava::capability_boundary` | filesystem/process/environment/network effects in ordinary source files | isolate effects in a marked capability source file |
| `strictjava::no_catchall_switch` | `default` on enum/sealed switches | enumerate all variants |

The rules are intentionally few. `strictjava` is not intended to become a large style-guide checker.


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

## Why compiler APIs first

M0 uses `javax.tools.JavaCompiler`, `DiagnosticListener`, `JavacTask`, and `Trees`. That gives structured compiler locations and typed AST access without scraping human javac output or introducing a second parser.

Error Prone and NullAway are planned as additional sources behind the same public report schema rather than defining that schema themselves.

## Development

Run the dependency-free acceptance suite:

```bash
./scripts/test.sh
```

The suite checks clean and broken compiler fixtures, exact ordered rule codes for a multi-violation fixture, capability-boundary enforcement/exemption, explicit dependency classpaths, byte-identical output across repeated checks, and a clean dogfood pass over strictjava itself.

A Gradle 9.8 build definition is included for IDE/build-tool use, but a Gradle wrapper is intentionally deferred to M3 together with generated-project support. The authoritative CI path for the current milestones is `scripts/test.sh` on JDK 25.

## Roadmap

- **M0 — deterministic diagnostic oracle:** functional
  - javac compiler diagnostics
  - stable JSON report
  - deterministic ordering
  - stable exit semantics
- **M1 — strict subset:** in progress
  - first source rules implemented
  - add JSpecify + NullAway
  - add Error Prone checks where they are stronger than local AST rules
  - capability boundaries for filesystem/network/process/environment/reflection — implemented for core JDK APIs
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
