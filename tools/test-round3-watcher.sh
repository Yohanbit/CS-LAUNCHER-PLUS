#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
UTILS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils"
python3 "$ROOT/tools/round3_watcher_harness.py" "$ROOT" "$OUT/fixtures"
find "$OUT/fixtures" -name '*.java' > "$OUT/fixtures.txt"
javac --release 8 -d "$OUT" @"$OUT/fixtures.txt" \
    "$UTILS/FrameCounterWindow.java" "$UTILS/FpsCounter.java" \
    "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/launch/FirstSubmissionWatch.java" \
    "$ROOT/tools/Round3WatcherLifecycleTest.java"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$ROOT/tools/round3_watcher_jni.c" -o "$OUT/libround3-watcher-spy.so"
java -cp "$OUT" Round3WatcherLifecycleTest "$OUT/libround3-watcher-spy.so"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared -DROUND3_LEGACY_LIBRARY \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$ROOT/tools/round3_watcher_jni.c" -o "$OUT/libround3-watcher-legacy.so"
java -cp "$OUT" Round3WatcherLifecycleTest --legacy "$OUT/libround3-watcher-legacy.so"
