#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}
PERF="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance"
UTILS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils"
SUPPORT="$ROOT/tools/measurement_test_support"
# Reuse only inert existing doubles; scheduler/governor/samplers are production code.
find "$ROOT/tools/overhead_test_support" -name '*.java' > "$OUT/sources.txt"
javac --release 8 -d "$OUT" @"$OUT/sources.txt" \
    "$SUPPORT/android/os/Looper.java" "$SUPPORT/android/os/Debug.java" \
    "$SUPPORT/net/kdt/pojavlaunch/Tools.java" \
    "$SUPPORT/net/kdt/pojavlaunch/prefs/LauncherPreferences.java" \
    "$SUPPORT/net/kdt/pojavlaunch/performance/PerformanceMode.java" \
    "$SUPPORT/net/kdt/pojavlaunch/performance/McContext.java" \
    "$SUPPORT/net/kdt/pojavlaunch/performance/SessionStats.java" \
    "$PERF/RuntimeThreadRules.java" "$PERF/RuntimeThermalState.java" \
    "$PERF/SchedulingPolicy.java" "$PERF/ThermalGovernor.java" "$PERF/FrameSampleCollector.java" \
    "$UTILS/FrameCounterWindow.java" "$UTILS/FpsCounter.java" "$UTILS/FrameMetrics.java" \
    "$ROOT/tools/OverheadLifecycleTest.java"
${CC:-cc} -std=c11 -O2 -Wall -Wextra -Werror -fPIC -shared \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    "$ROOT/tools/overhead_frame_metrics_jni.c" -o "$OUT/liboverhead-spy.so"
java -cp "$OUT" OverheadLifecycleTest "$OUT/liboverhead-spy.so" "$OUT/data"
