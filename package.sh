#!/bin/sh
# Builds the jar and packs a release archive at dist/AmmoRegenBar.zip.
#
# The archive holds a single top level folder, so extracting it into the game's
# mods/ directory installs the mod as is. The name matches directDownloadURL in
# ammoRegenBar.version, which is what Version Checker fetches.
set -e

MOD_DIR=$(cd "$(dirname "$0")" && pwd)
NAME=AmmoRegenBar
DIST="$MOD_DIR/dist"
STAGE="$DIST/$NAME"

sh "$MOD_DIR/build.sh"

rm -rf "$DIST"
mkdir -p "$STAGE"

cp "$MOD_DIR/mod_info.json" "$MOD_DIR/ammoRegenBar.version" "$MOD_DIR/build.sh" "$STAGE/"
cp -r "$MOD_DIR/data" "$MOD_DIR/jars" "$MOD_DIR/src" "$STAGE/"

cd "$DIST"
zip -qr "$NAME.zip" "$NAME"
rm -rf "$STAGE"

echo "packaged $DIST/$NAME.zip"
