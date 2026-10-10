#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance"
javac --release 8 -d "$OUT" "$SRC/RuntimeJvmArguments.java" "$SRC/RuntimeMemoryBudget.java" \
    "$SRC/RuntimeRendererRules.java" "$SRC/RuntimeThermalState.java" "$SRC/RuntimeThreadRules.java" \
    "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/Runtime.java" \
    "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/RuntimeLibraryLoader.java" \
    "$ROOT/tools/RuntimeSafetyPolicyTest.java" "$ROOT/tools/RuntimeLaunchRegressionTest.java"
java -cp "$OUT" RuntimeSafetyPolicyTest
java -cp "$OUT" RuntimeLaunchRegressionTest
