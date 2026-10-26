#!/bin/bash
# Build the harness against a server jar and run every scenario once, without and with the plugin.
# usage: test-harness/run.sh <server-paperclip.jar> [java]
#   e.g. test-harness/run.sh ~/jars/paper-26.2-40.jar /usr/lib/jvm/jdk-25/bin/java
set -eu
SERVER=$(readlink -f "$1"); JAVA=${2:-java}; JBIN=$(dirname "$(readlink -f "$(command -v "$JAVA")")")
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(dirname "$HERE")
RUN=$HERE/run; BUILD=$HERE/build
mkdir -p "$RUN" "$BUILD/classes"
cp "$SERVER" "$RUN/server.jar"
( cd "$RUN" && "$JAVA" -Dpaperclip.patchonly=true -jar server.jar > patch.log 2>&1 )
CP=$(find "$RUN/libraries" -name '*.jar' | tr '\n' ':')$(ls "$RUN"/versions/*/*.jar)
"$JBIN/javac" -nowarn -d "$BUILD/classes" -cp "$CP" $(find "$HERE/src" -name '*.java')
cp "$HERE/plugin.yml" "$BUILD/classes/"
( cd "$BUILD/classes" && "$JBIN/jar" cf ../PrdfHarness.jar . )
PLUGIN=$(ls "$ROOT"/target/PlaceRevertDropFix-*.jar)
for mode in without with; do
  ( cd "$RUN"
    rm -rf world world_nether world_the_end plugins logs config; mkdir -p plugins config
    echo eula=true > eula.txt
    printf 'online-mode=false\nserver-port=25619\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=3\nsimulation-distance=3\nspawn-protection=0\n' > server.properties
    # one orb group per area: makes vanilla merge new xp into orbs already lying there every time (scenario F)
    printf 'misc:\n  xp-orb-groups-per-area: 1\n' > config/paper-global.yml
    cp "$BUILD/PrdfHarness.jar" plugins/
    [ "$mode" = with ] && cp "$PLUGIN" plugins/
    timeout 600 "$JAVA" -Xms2G -Xmx2G -jar server.jar --nogui < /dev/null > console-$mode.log 2>&1 || true
    cp plugins/PrdfHarness/result.txt "$RUN/result-$mode.txt" )
  echo "=== $mode PlaceRevertDropFix"; grep -E '^==|VERDICT' "$RUN/result-$mode.txt"
done
