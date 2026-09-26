#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLASSES="$ROOT/build/manual/classes"
JAR="$ROOT/build/strictjava.jar"

rm -rf "$CLASSES"
mkdir -p "$CLASSES"
mapfile -t SOURCES < <(find "$ROOT/src/main/java" -name '*.java' -print | sort)
javac -Xlint:all -Werror -d "$CLASSES" "${SOURCES[@]}"
if [[ -d "$ROOT/src/main/resources" ]]; then
  cp -R "$ROOT/src/main/resources/." "$CLASSES/"
fi
jar --create --file "$JAR" --main-class pw.rkd.strictjava.Main -C "$CLASSES" .
printf '%s\n' "$JAR"
