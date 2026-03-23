#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
JAR="$ROOT/build/telecom-dummygen.jar"
if [ ! -f "$JAR" ]; then
  echo "Jar not found. Run ./build.sh first."
  exit 2
fi
java -jar "$JAR" "$@"
