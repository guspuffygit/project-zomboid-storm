#!/bin/sh
# Storm Launcher — pre-game UI for joining servers and syncing java mods.
# When this script lives inside the Steam workshop item
# (steamapps/workshop/content/108600/<id>/mods/storm/launcher/) the game install
# sits seven directories up in the same Steam library; use its bundled JRE.
# The linux/mac depots nest the game one level deeper (projectzomboid/).
DIR="$(cd "$(dirname "$0")" && pwd)"
PZ="$DIR/../../../../../../../common/ProjectZomboid"
if [ ! -f "$PZ/ProjectZomboid64.json" ] && [ -f "$PZ/projectzomboid/ProjectZomboid64.json" ]; then
  PZ="$PZ/projectzomboid"
fi
for JAVA in "$PZ/jre64/bin/java" "$PZ/jre64/Contents/Home/bin/java"; do
  if [ -x "$JAVA" ]; then
    exec "$JAVA" -jar "$DIR/storm-launcher.jar" "$@"
  fi
done
exec java -jar "$DIR/storm-launcher.jar" "$@"
