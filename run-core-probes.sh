#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CORE="$ROOT/drakon-graphics"
OUT="$ROOT/out"
CLASSES="$OUT/classes"
DOCS="$OUT/javadoc"

rm -rf "$OUT"
mkdir -p "$CLASSES" "$DOCS"

find "$CORE/src/main/java" "$CORE/src/test/java" -name '*.java' -print0 \
    | xargs -0 javac --release 21 -Xlint:all -Werror -d "$CLASSES"

# Documentation is part of the API contract in this iteration. -Werror makes
# missing or malformed public Javadoc fail the build rather than rot silently.
find "$CORE/src/main/java" -name '*.java' -print0 \
    | xargs -0 javadoc --release 21 -quiet -Xdoclint:all -Werror -d "$DOCS"

cp -R "$CORE/src/test/resources/." "$CLASSES/"
java -cp "$CLASSES" io.github.antonschnfeld.drakon.graphics.examples.AllExamples
