#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT HUP INT TERM
JNI="$ROOT/app_pojavlauncher/src/main/jni"
UTILS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils"
javac --release 8 -d "$OUT" "$UTILS/FrameCounterWindow.java" "$UTILS/FpsCounter.java" "$ROOT/tools/MeasurementWindowTest.java"
java -cp "$OUT" MeasurementWindowTest
# Compile/run actual lifecycle implementations against minimal Android doubles.
find "$ROOT/tools/measurement_test_support" -name '*.java' > "$OUT/stubs.txt"
javac --release 8 -cp "$OUT" -d "$OUT" @"$OUT/stubs.txt" \
    "$UTILS/FrameMetrics.java" \
    "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/FrameSampleCollector.java" \
    "$ROOT/tools/MeasurementLifecycleTest.java"
java -cp "$OUT" MeasurementLifecycleTest "$OUT/data"
${CC:-cc} -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -Wall -Wextra -Werror -pthread \
    -I"$JNI" "$JNI/frame_metrics.c" "$ROOT/tools/measurement_native_test.c" \
    -Wl,--wrap=pthread_mutex_lock -Wl,--wrap=clock_gettime -o "$OUT/measurement-native"
"$OUT/measurement-native"
# Static assertions + no undefined atomic helper symbols on BOTH Android ARM ABIs.
# Set ANDROID_NDK_HOME to NDK r27c to make this mandatory in an Android build.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do
        "$BIN/$TARGET-clang" -std=c11 -O2 -Wall -Wextra -Werror -I"$JNI" \
            -c "$JNI/frame_metrics.c" -o "$OUT/$TARGET.o"
        if "$BIN/llvm-nm" -u "$OUT/$TARGET.o" | grep -E '__atomic|__sync'; then
            echo "FAIL $TARGET needs out-of-line atomic helpers" >&2; exit 1
        fi
        echo "PASS $TARGET C11 lock-free static assertions; no libatomic helper dependency"
    done
else
    if [ "${MEASUREMENT_REQUIRE_NDK:-0}" = "1" ]; then
        echo "FAIL Android ARM32/ARM64 validation required but ANDROID_NDK_HOME is unset" >&2
        exit 1
    fi
    echo "SKIP Android ARM32/ARM64 cross-compile: ANDROID_NDK_HOME not set"
    if command -v clang >/dev/null 2>&1; then
        for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do
            clang --target="$TARGET" -ffreestanding -std=c11 -O2 -Wall -Wextra -Werror \
                -c "$ROOT/tools/measurement_atomic_probe.c" -o "$OUT/$TARGET.o"
            if nm -u "$OUT/$TARGET.o" | grep -E '__atomic|__sync'; then
                echo "FAIL freestanding $TARGET needs out-of-line atomic helpers" >&2; exit 1
            fi
            echo "PASS freestanding $TARGET atomic codegen/static assertions; no libatomic helpers (not full NDK compile)"
        done
    fi
fi
