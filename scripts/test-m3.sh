#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
"$ROOT/scripts/build.sh" >/dev/null
JAR="$ROOT/build/strictjava.jar"
WORK="$ROOT/build/m3-work"
WRAPPER_SHA=238e777fcddd7e34f9708186085def2abd6e08e658505b38718d79d74c21abd5
DIST_SHA=bafd5ce9cfaea0fbccfdc8439a1ac42fbd4cd9c89dc9a988228d8a2639a58e6c

rm -rf "$WORK"
mkdir -p "$WORK/one" "$WORK/two"

generate() {
  local parent=$1
  local output
  (
    cd "$parent"
    output=$(java -jar "$JAR" new demo-app)
    python3 - "$output" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["diagnostics"] == [], report
PY
  )
}

generate "$WORK/one"
generate "$WORK/two"

PROJECT="$WORK/one/demo-app"
SECOND="$WORK/two/demo-app"

# Generation is deterministic and leaves no unresolved template placeholders.
diff -ru "$PROJECT" "$SECOND"
if grep -R -E '__PROJECT__|__STRICTJAVA_REF__' "$PROJECT"; then
  echo "unresolved template placeholder" >&2
  exit 1
fi

[[ -x "$PROJECT/gradlew" ]]
[[ -x "$PROJECT/scripts/commit.sh" ]]
[[ -x "$PROJECT/scripts/strictjava.sh" ]]

printf '%s  %s
' "$WRAPPER_SHA" "$ROOT/gradle/wrapper/gradle-wrapper.jar" | sha256sum --check
printf '%s  %s
' "$WRAPPER_SHA" "$PROJECT/gradle/wrapper/gradle-wrapper.jar" | sha256sum --check
grep -Fqx "distributionSha256Sum=$DIST_SHA" "$PROJECT/gradle/wrapper/gradle-wrapper.properties"
grep -Fq 'org.jspecify:jspecify:1.0.0=' "$PROJECT/gradle.lockfile"
grep -Fq '1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab'   "$PROJECT/gradle/verification-metadata.xml"

# Existing destinations and invalid names are never overwritten/created.
set +e
existing_json=$(cd "$WORK/one" && java -jar "$JAR" new demo-app 2>/dev/null)
existing_status=$?
invalid_json=$(cd "$WORK/one" && java -jar "$JAR" new BadName 2>/dev/null)
invalid_status=$?
set -e
[[ "$existing_status" -eq 2 ]]
[[ "$invalid_status" -eq 2 ]]
python3 - "$existing_json" "$invalid_json" <<'PY'
import json, sys
for raw in sys.argv[1:]:
    report = json.loads(raw)
    assert report["ok"] is False, report
    assert "operational_error" in report, report
PY
[[ ! -e "$WORK/one/BadName" ]]

(
  cd "$PROJECT"

  ./gradlew --no-daemon formatCheck test installDist

  cp gradle.lockfile "$WORK/gradle.lockfile.before"
  ./gradlew --no-daemon dependencies --write-locks
  diff -u "$WORK/gradle.lockfile.before" gradle.lockfile

  CLASSPATH=$(./gradlew -q strictjavaClasspath)
  check_json=$(java -jar "$JAR" check --classpath "$CLASSPATH" .)
  python3 - "$check_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
assert report["diagnostics"] == [], report
PY

  STRICTJAVA_JAR="$JAR" ./scripts/strictjava.sh check --classpath "$CLASSPATH" . >/dev/null

  docker build --target from-artifact --tag strictjava-m3-test .
  docker run --rm strictjava-m3-test | grep -Fqx 'Hello from demo-app.'
)

# Skill installation is idempotent and refuses to overwrite local modifications.
HOME_DIR="$WORK/home"
mkdir -p "$HOME_DIR/.codex" "$HOME_DIR/.claude"

install_json=$(HOME="$HOME_DIR" java -jar "$JAR" install-skills 2>"$WORK/install.stderr")
python3 - "$install_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is True, report
PY
CODEx_SKILL="$HOME_DIR/.agents/skills/strictjava/SKILL.md"
CLAUDE_SKILL="$HOME_DIR/.claude/skills/strictjava/SKILL.md"
[[ -f "$CODEx_SKILL" ]]
[[ -f "$CLAUDE_SKILL" ]]
grep -Fq 'strictjava new <name>' "$CODEx_SKILL"
cmp "$CODEx_SKILL" "$CLAUDE_SKILL"

HOME="$HOME_DIR" java -jar "$JAR" install-skills >/dev/null 2>/dev/null
printf '\n# local edit\n' >> "$CLAUDE_SKILL"
cp "$CLAUDE_SKILL" "$WORK/modified-skill"

set +e
modified_json=$(HOME="$HOME_DIR" java -jar "$JAR" install-skills 2>/dev/null)
modified_status=$?
set -e
[[ "$modified_status" -eq 2 ]]
cmp "$WORK/modified-skill" "$CLAUDE_SKILL"
python3 - "$modified_json" <<'PY'
import json, sys
report = json.loads(sys.argv[1])
assert report["ok"] is False, report
assert "refusing to overwrite" in report["operational_error"], report
PY

echo "strictjava M3 generated-project and skill tests passed"
