#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch"
find "$ROOT/tools/round3_runtime_test_support" -name '*.java' > "$OUT/sources"
printf '%s\n' "$SRC/Architecture.java" "$SRC/performance/RuntimeVulkanSupport.java" \
    "$SRC/performance/RuntimeRendererRules.java" "$SRC/performance/DeviceCapability.java" \
    "$ROOT/tools/Round3RuntimeCapabilityTest.java" >> "$OUT/sources"
javac --release 8 -d "$OUT/classes" @"$OUT/sources"
java -cp "$OUT/classes" Round3RuntimeCapabilityTest
