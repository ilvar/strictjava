#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/scripts/build.sh" >/dev/null
JAR="$ROOT/build/strictjava.jar"
ANALYZER_DIR="$ROOT/build/analyzers"

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
JSPECIFY_JAR=$(find "$ANALYZER_DIR" -maxdepth 1 -type f -name 'jspecify-*.jar' -print | sort | head -1)
if [[ -z "$JSPECIFY_JAR" ]]; then
  echo "JSpecify jar missing from analyzer bundle" >&2
  exit 2
fi

run_failure() {
  local fixture=$1
  local classpath=$2
  local output
  local status
  set +e
  output=$(java -jar "$JAR" check --analyzer-path "$ANALYZER_PATH" --classpath "$classpath" "$ROOT/fixtures/$fixture")
  status=$?
  set -e
  [[ "$status" -eq 1 ]]
  printf '%s' "$output"
}

nullness_json=$(run_failure nullness "$ANALYZER_PATH")
python3 - "$nullness_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "nullaway"]
assert "nullaway::NullAway" in codes, report
PY

nullmarking_json=$(run_failure nullmarking "$ANALYZER_PATH")
python3 - "$nullmarking_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "nullaway"]
assert codes == ["nullaway::RequireExplicitNullMarking"], report
PY

return_json=$(run_failure errorprone-return "$ANALYZER_PATH")
python3 - "$return_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "errorprone"]
assert codes == ["errorprone::ReturnValueIgnored"], report
PY

resource_json=$(run_failure errorprone-resource "$ANALYZER_PATH")
python3 - "$resource_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "errorprone"]
assert "errorprone::StreamResourceLeak" in codes, report
PY

location_json=$(run_failure jspecify-location "$ANALYZER_PATH")
python3 - "$location_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "nullaway"]
assert "nullaway::JSpecifyUnrecognizedAnnotationLocation" in codes, report
PY

future_json=$(run_failure errorprone-future "$ANALYZER_PATH")
python3 - "$future_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "errorprone"]
assert "errorprone::FutureReturnValueIgnored" in codes, report
PY

must_close_json=$(run_failure errorprone-must-close "$ANALYZER_PATH")
python3 - "$must_close_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
codes = [d["code"] for d in report["diagnostics"] if d["source"] == "errorprone"]
assert "errorprone::MustBeClosedChecker" in codes, report
PY

# Analyzer output must remain byte-identical across runs.
return_again=$(run_failure errorprone-return "$ANALYZER_PATH")
[[ "$return_json" == "$return_again" ]]

# A normal (non-core-only) invocation auto-discovers build/analyzers next to the jar.
set +e
auto_json=$(java -jar "$JAR" check --classpath "$ANALYZER_PATH" "$ROOT/fixtures/errorprone-return")
auto_status=$?
set -e
[[ "$auto_status" -eq 1 ]]
[[ "$auto_json" == "$return_json" ]]

echo "strictjava M1 analyzer tests passed"
