#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/scripts/build.sh" >/dev/null
JAR="$ROOT/build/strictjava.jar"

clean_json=$(java -jar "$JAR" check "$ROOT/fixtures/clean")
python3 - "$clean_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["error_count"] == 0, report
assert report["diagnostics"] == [], report
PY

set +e
bad_json=$(java -jar "$JAR" check "$ROOT/fixtures/violations")
bad_status=$?
set -e
[[ "$bad_status" -eq 1 ]]
python3 - "$bad_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
expected = [
    "strictjava::no_wildcard_import",
    "strictjava::no_suppress_warnings",
    "strictjava::no_mutable_global",
    "strictjava::no_optional_get",
    "strictjava::no_catchall_switch",
    "strictjava::no_reflection",
    "strictjava::no_system_exit",
    "strictjava::no_runtime_halt",
]
codes = [item.get("code") for item in report["diagnostics"]]
assert report["ok"] is False, report
assert report["error_count"] == len(expected), report
assert codes == expected, (codes, expected)
PY

set +e
warning_json=$(java -jar "$JAR" check "$ROOT/fixtures/javac-warning")
warning_status=$?
set -e
[[ "$warning_status" -eq 1 ]]
python3 - "$warning_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["warning_count"] == 0, report
assert any(item["source"] == "javac" and "warn" in item.get("code", "") for item in report["diagnostics"]), report
assert all(item["level"] == "error" for item in report["diagnostics"]), report
PY

set +e
compiler_json=$(java -jar "$JAR" check "$ROOT/fixtures/compiler-error")
compiler_status=$?
set -e
[[ "$compiler_status" -eq 1 ]]
python3 - "$compiler_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert any(item["source"] == "javac" and item["level"] == "error" for item in report["diagnostics"]), report
PY

set +e
operational_json=$(java -jar "$JAR" check "$ROOT/fixtures/does-not-exist")
operational_status=$?
set -e
[[ "$operational_status" -eq 2 ]]
python3 - "$operational_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert "operational_error" in report, report
PY

set +e
capability_json=$(java -jar "$JAR" check "$ROOT/fixtures/capabilities-bad")
capability_status=$?
set -e
[[ "$capability_status" -eq 1 ]]
python3 - "$capability_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [item.get("code") for item in report["diagnostics"]]
assert codes == [
    "strictjava::capability_boundary",
    "strictjava::capability_boundary",
    "strictjava::capability_boundary",
    "strictjava::no_reflection",
], codes
PY

capability_good_json=$(java -jar "$JAR" check "$ROOT/fixtures/capabilities-good")
python3 - "$capability_good_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["diagnostics"] == [], report
PY

set +e
native_json=$(java -jar "$JAR" check "$ROOT/fixtures/native-code")
native_status=$?
set -e
[[ "$native_status" -eq 1 ]]
python3 - "$native_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [
    item.get("code")
    for item in report["diagnostics"]
    if item.get("source") == "strictjava"
]
assert codes == [
    "strictjava::no_native_code",
    "strictjava::no_native_code",
], codes
PY

set +e
interface_json=$(java -jar "$JAR" check "$ROOT/fixtures/mutable-interface")
interface_status=$?
set -e
[[ "$interface_status" -eq 1 ]]
python3 - "$interface_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [item.get("code") for item in report["diagnostics"]]
assert codes == ["strictjava::no_mutable_global"], codes
PY

main_good_json=$(java -jar "$JAR" check "$ROOT/fixtures/main-boundary-good")
python3 - "$main_good_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
PY

set +e
main_bad_json=$(java -jar "$JAR" check "$ROOT/fixtures/main-boundary-bad")
main_bad_status=$?
set -e
[[ "$main_bad_status" -eq 1 ]]
python3 - "$main_bad_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [item.get("code") for item in report["diagnostics"]]
assert codes == ["strictjava::no_system_exit"], codes
PY

classpath_classes="$ROOT/build/test-classpath/classes"
classpath_jar="$ROOT/build/test-classpath/examplelib.jar"
rm -rf "$ROOT/build/test-classpath"
mkdir -p "$classpath_classes"
javac -d "$classpath_classes" "$ROOT/fixtures/classpath-lib/src/main/java/examplelib/Greeting.java"
jar --create --file "$classpath_jar" -C "$classpath_classes" .

set +e
classpath_missing_json=$(java -jar "$JAR" check "$ROOT/fixtures/classpath-app")
classpath_missing_status=$?
set -e
[[ "$classpath_missing_status" -eq 1 ]]
python3 - "$classpath_missing_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert any(item["source"] == "javac" for item in report["diagnostics"]), report
PY

classpath_json=$(java -jar "$JAR" check --classpath "$classpath_jar" "$ROOT/fixtures/classpath-app")
python3 - "$classpath_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["diagnostics"] == [], report
PY

# The same input must produce byte-identical JSON.
again=$(java -jar "$JAR" check "$ROOT/fixtures/violations" || true)
[[ "$bad_json" == "$again" ]]

# Dogfood the checker on its own production sources.
self_json=$(java -jar "$JAR" check "$ROOT")
python3 - "$self_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
PY

echo "strictjava tests passed"
