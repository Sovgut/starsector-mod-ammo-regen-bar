#!/bin/sh
# Builds jars/AmmoRegenBar.jar from src/.
#
# The game runs on Java 17 (jre_linux), so the classes must target 17 even when
# a newer JDK is installed. Run from anywhere: paths are resolved from this file.
set -e

MOD_DIR=$(cd "$(dirname "$0")" && pwd)
GAME_DIR=$(cd "$MOD_DIR/../.." && pwd)
OUT="$MOD_DIR/build"

CP="$GAME_DIR/starfarer.api.jar:$GAME_DIR/lwjgl.jar:$GAME_DIR/json.jar:$GAME_DIR/log4j-1.2.9.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$MOD_DIR/jars"

SOURCES="$OUT.sources"
find "$MOD_DIR/src" -name '*.java' > "$SOURCES"
javac --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" @"$SOURCES"
rm -f "$SOURCES"

jar cf "$MOD_DIR/jars/AmmoRegenBar.jar" -C "$OUT" .
rm -rf "$OUT"

echo "built $MOD_DIR/jars/AmmoRegenBar.jar"
