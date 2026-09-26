#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/scripts/build.sh" >/dev/null
JAR="$ROOT/build/strictjava.jar"
ANALYZER_DIR="$ROOT/build/analyzers"
WORK="$ROOT/build/m2-work"

if [[ ! -d "$ANALYZER_DIR" ]]; then
  echo "build/analyzers is missing; run: gradle prepareAnalyzers" >&2
  exit 2
fi

mapfile -t ANALYZER_JARS < <(find "$ANALYZER_DIR" -maxdepth 1 -type f -name '*.jar' -print | sort)
if [[ "${#ANALYZER_JARS[@]}" -eq 0 ]]; then
  echo "build/analyzers contains no jars" >&2
  exit 2
fi
ANALYZER_PATH=$(IFS=:; echo "${ANALYZER_JARS[*]}")

fresh_copy() {
  local fixture=$1
  local destination=$2
  rm -rf "$destination"
  mkdir -p "$destination"
  cp -R "$ROOT/fixtures/$fixture/." "$destination/"
}

rm -rf "$WORK"
mkdir -p "$WORK"

# An allowlisted Error Prone fix is applied, fully re-checked, and becomes clean.
fresh_copy errorprone-return "$WORK/return-a"
set +e
return_json=$(java -jar "$JAR" fix --classpath "$ANALYZER_PATH" "$WORK/return-a")
return_status=$?
set -e
[[ "$return_status" -eq 0 ]]
python3 - "$return_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["diagnostics"] == [], report
assert report["fix"]["status"] == "clean", report
assert report["fix"]["passes"] == 1, report
assert report["fix"]["applied_checks"] == ["errorprone::ReturnValueIgnored"], report
assert report["fix"]["changed_files"] == ["src/main/java/demo/App.java"], report
PY
! cmp -s   "$ROOT/fixtures/errorprone-return/src/main/java/demo/App.java"   "$WORK/return-a/src/main/java/demo/App.java"

# The same source tree must produce the same edit and byte-identical final JSON.
fresh_copy errorprone-return "$WORK/return-b"
return_again=$(java -jar "$JAR" fix --classpath "$ANALYZER_PATH" "$WORK/return-b")
[[ "$return_json" == "$return_again" ]]
cmp -s   "$WORK/return-a/src/main/java/demo/App.java"   "$WORK/return-b/src/main/java/demo/App.java"

# NullAway diagnostics have no M2 tool-fix allowlist entry and must remain untouched.
fresh_copy nullness "$WORK/blocked"
cp -R "$WORK/blocked" "$WORK/blocked-before"
set +e
blocked_json=$(java -jar "$JAR" fix --classpath "$ANALYZER_PATH" "$WORK/blocked")
blocked_status=$?
set -e
[[ "$blocked_status" -eq 1 ]]
python3 - "$blocked_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert report["fix"]["status"] == "blocked", report
assert report["fix"]["passes"] == 0, report
assert report["fix"]["changed_files"] == [], report
assert report["fix"]["applied_checks"] == [], report
assert "allowlisted" in report["fix"]["blocked_reason"], report
PY
diff -ru "$WORK/blocked-before" "$WORK/blocked"

# A pass cap is explicit and resumable: one checker batch per successful pass.
fresh_copy fix-multipass "$WORK/multipass"
set +e
limited_json=$(java -jar "$JAR" fix --max-passes 1 --classpath "$ANALYZER_PATH" "$WORK/multipass")
limited_status=$?
set -e
[[ "$limited_status" -eq 1 ]]
python3 - "$limited_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert report["fix"]["status"] == "iteration_limit", report
assert report["fix"]["passes"] == 1, report
assert len(report["fix"]["applied_checks"]) == 1, report
PY

resumed_json=$(java -jar "$JAR" fix --classpath "$ANALYZER_PATH" "$WORK/multipass")
python3 - "$resumed_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["fix"]["status"] == "clean", report
assert report["fix"]["passes"] >= 1, report
PY

# fix never falls back to core-only behavior.
set +e
core_fix_json=$(java -jar "$JAR" fix --core-only "$WORK/return-a")
core_fix_status=$?
set -e
[[ "$core_fix_status" -eq 2 ]]
python3 - "$core_fix_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert "operational_error" in report, report
PY

echo "strictjava M2 fix-loop tests passed"
