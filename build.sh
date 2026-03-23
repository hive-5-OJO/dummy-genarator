#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build"
CLASSES="$OUT/classes"
mkdir -p "$CLASSES"
find "$ROOT/src/main/java" -name "*.java" > "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -d "$CLASSES" @"$OUT/sources.txt"
jar --create --file "$OUT/telecom-dummygen.jar" --main-class com.que.telecomdummy.Main -C "$CLASSES" .
echo "Built: $OUT/telecom-dummygen.jar"
