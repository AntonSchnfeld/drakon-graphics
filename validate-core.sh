#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
CORE="$ROOT/drakon-graphics"
OUT="$ROOT/.validation"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/javadoc"
find "$CORE/src/main/java" "$CORE/src/test/java" -name '*.java' -print0 \
  | xargs -0 javac --release 21 -Xlint:all -Werror -d "$OUT/classes"
if [ -d "$CORE/src/test/resources" ]; then
  cp -a "$CORE/src/test/resources/." "$OUT/classes/"
fi
find "$CORE/src/main/java" -name '*.java' -print0 \
  | xargs -0 javadoc --release 21 -quiet -Xdoclint:all -Werror -d "$OUT/javadoc"
java -cp "$OUT/classes" io.github.antonschnfeld.drakon.graphics.examples.AllExamples
