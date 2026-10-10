#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch"
python3 "$ROOT/tools/runtime_consolidation_extract.py" "$OUT"
javac --release 8 -d "$OUT/classes" \
    "$ROOT/tools/round3_runtime_test_support/android/os/Build.java" \
    "$ROOT/tools/round3_runtime_test_support/android/os/Process.java" "$SRC/Architecture.java" \
    "$SRC/multirt/Runtime.java" "$SRC/performance/RuntimeJvmArguments.java" \
    "$SRC/performance/RuntimeMemoryBudget.java" "$SRC/performance/RuntimeRendererRules.java" \
    "$SRC/performance/RuntimeArgumentTokenizer.java" "$SRC/performance/RuntimeRendererSelection.java" \
    "$SRC/performance/RuntimeModWorkarounds.java" "$SRC/performance/RuntimeNativeProperties.java" \
    "$OUT/ActualLaunch.java" "$ROOT/tools/ConsolidationRuntimeTest.java"
java -cp "$OUT/classes" ConsolidationRuntimeTest
# CI must supply the declared Gradle versions, or cached matching jars. Never download from a script.
# Debian host fallback is explicitly labelled, not represented as the Android dependency version.
if [ -z "${RUNTIME_TAR_TEST_CP:-}" ]; then
    CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
    COMPRESS=$(find "$CACHE/org.apache.commons/commons-compress/1.24.0" -name '*.jar' 2>/dev/null | head -1 || true)
    XZ=$(find "$CACHE/org.tukaani/xz/1.8" -name '*.jar' 2>/dev/null | head -1 || true)
    IO=$(find "$CACHE/commons-io/commons-io/2.14.0" -name '*.jar' 2>/dev/null | head -1 || true)
    if [ -n "$COMPRESS" ] && [ -n "$XZ" ] && [ -n "$IO" ]; then
        RUNTIME_TAR_TEST_CP="$COMPRESS:$XZ:$IO"
    elif [ "${CI:-false}" = true ]; then
        echo 'CI requires Gradle-resolved commons-compress 1.24.0/xz 1.8/commons-io 2.14.0; run dependency resolution or supply RUNTIME_TAR_TEST_CP' >&2
        exit 1
    else
    RUNTIME_TAR_TEST_CP=/usr/share/java/commons-compress.jar:/usr/share/java/xz.jar:/usr/share/java/commons-io.jar
    echo 'Archive test dependency fallback: system jars (host versions; CI set RUNTIME_TAR_TEST_CP to commons-compress 1.24/xz 1.8 and commons-io 2.14)'
    fi
fi
javac --release 8 -cp "$RUNTIME_TAR_TEST_CP" -d "$OUT/classes" \
    "$SRC/multirt/RuntimeArchiveExtractor.java" "$ROOT/tools/RuntimeArchiveExtractionTest.java"
java -Druntime.requireExact="${CI:-false}" -cp "$OUT/classes:$RUNTIME_TAR_TEST_CP" RuntimeArchiveExtractionTest
sh "$ROOT/tools/test-runtime-safety.sh"
sh "$ROOT/tools/test-round3-runtime.sh"
