#!/usr/bin/env bash
set -euo pipefail

if [[ -n "${STRICTJAVA_JAR:-}" ]]; then
  exec java -jar "$STRICTJAVA_JAR" "$@"
fi

if command -v strictjava >/dev/null 2>&1; then
  exec strictjava "$@"
fi

echo "strictjava is not installed; put it on PATH or set STRICTJAVA_JAR" >&2
exit 2
