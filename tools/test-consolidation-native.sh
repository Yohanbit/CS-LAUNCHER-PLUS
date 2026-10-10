#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
python3 "$ROOT/tools/consolidation_native_test.py" "$ROOT"
# Compile all modified native lifetime/driver units on both production ABIs.
# No object compile is a claim about GPU, private namespace or display behavior.
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    OUT=$(mktemp -d)
    trap 'rm -rf "$OUT"' EXIT HUP INT TERM
    BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    JNI="$ROOT/app_pojavlauncher/src/main/jni"
    for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do
        ARCH_FLAGS=
        if [ "$TARGET" = "aarch64-linux-android21" ]; then ARCH_FLAGS=-DADRENO_POSSIBLE; fi
        for SOURCE in egl_bridge.c frame_metrics.c ctxbridges/gl_bridge.c ctxbridges/osm_bridge.c driver_helper/nsbypass.c driver_helper/hook.c jvm_hooks/lwjgl_dlopen_hook.c; do
            "$BIN/$TARGET-clang" -std=gnu11 -O2 -Werror=implicit-function-declaration \
                $ARCH_FLAGS -I"$JNI" -c "$JNI/$SOURCE" -o "$OUT/$TARGET-$(basename "$SOURCE").o"
        done
        if "$BIN/llvm-nm" -u "$OUT/$TARGET-"*.o | grep -E '__atomic_|__sync_'; then
            echo "FAIL $TARGET added an out-of-line atomic helper dependency" >&2; exit 1
        fi
        echo "PASS $TARGET modified production lifetime/window/driver units (NDK compile only)"
    done
else
    if [ "${NATIVE_REQUIRE_NDK:-0}" = "1" ]; then
        echo "FAIL NDK consolidation compilation required but ANDROID_NDK_HOME unset" >&2; exit 1
    fi
    echo "SKIP Android ARM32/ARM64 consolidation compile: ANDROID_NDK_HOME not set"
fi
