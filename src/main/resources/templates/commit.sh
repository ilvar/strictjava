#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PUSH=0
SKIP_CHECKS=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--push) PUSH=1; shift ;;
    -n|--no-checks) SKIP_CHECKS=1; shift ;;
    -h|--help)
      echo 'usage: ./scripts/commit.sh [-p] [-n] "commit message"'
      exit 0
      ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) break ;;
  esac
done

MESSAGE="${1:-}"
if [[ -z "$MESSAGE" ]]; then
  echo 'usage: ./scripts/commit.sh [-p] [-n] "commit message"' >&2
  exit 2
fi

git add -A
if git diff --cached --quiet; then
  echo "nothing staged, nothing to commit"
  exit 0
fi

if [[ "$SKIP_CHECKS" == "0" ]]; then
  ./gradlew --no-daemon format
  git add -A
  ./gradlew --no-daemon test formatCheck
  ./scripts/strictjava.sh check --classpath "$(./gradlew -q strictjavaClasspath)" .
fi

git commit --no-verify -m "$MESSAGE"

if [[ "$PUSH" == "1" ]]; then
  git push origin HEAD
fi
