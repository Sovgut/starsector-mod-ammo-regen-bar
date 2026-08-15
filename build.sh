#!/bin/sh
# Builds jars/AmmoRegenBar.jar from src/.
#
# The game runs on Java 17 (jre_linux), so the classes must target 17 even when
# a newer JDK is installed. Run from anywhere: paths are resolved from this file.
set -e

MOD_DIR=$(cd "$(dirname "$0")" && pwd)
OUT="$MOD_DIR/build"

# The game's own jars are the compile classpath and cannot be vendored here.
# Default to the install this mod sits in; override when the repo is cloned
# somewhere else: STARSECTOR_HOME=/path/to/Starsector ./build.sh
if [ -z "$STARSECTOR_HOME" ]; then
    STARSECTOR_HOME=$(cd "$MOD_DIR/../.." 2>/dev/null && pwd)
fi

if [ ! -f "$STARSECTOR_HOME/starfarer.api.jar" ]; then
    echo "starfarer.api.jar not found under '$STARSECTOR_HOME'." >&2
    echo "Set STARSECTOR_HOME to the Starsector install directory." >&2
    exit 1
fi

CP="$STARSECTOR_HOME/starfarer.api.jar:$STARSECTOR_HOME/lwjgl.jar:$STARSECTOR_HOME/json.jar:$STARSECTOR_HOME/log4j-1.2.9.jar"

rm -rf "$OUT"
mkdir -p "$OUT" "$MOD_DIR/jars"

SOURCES="$OUT.sources"
find "$MOD_DIR/src" -name '*.java' > "$SOURCES"
javac --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT" @"$SOURCES"
rm -f "$SOURCES"

jar cf "$MOD_DIR/jars/AmmoRegenBar.jar" -C "$OUT" .
rm -rf "$OUT"

echo "built $MOD_DIR/jars/AmmoRegenBar.jar"
