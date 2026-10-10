#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
JNI="$ROOT/app_pojavlauncher/src/main/jni"
python3 "$ROOT/tools/vulkan_loader_round3_test.py" "$ROOT"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    OUT=$(mktemp -d)
    trap 'rm -rf "$OUT"' EXIT HUP INT TERM
    BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do
        ARCH_FLAGS=
        if [ "$TARGET" = "aarch64-linux-android21" ]; then
            ARCH_FLAGS=-DADRENO_POSSIBLE
        fi
        "$BIN/$TARGET-clang" -std=gnu11 -O2 -Werror=implicit-function-declaration \
            $ARCH_FLAGS -I"$JNI" -c "$JNI/egl_bridge.c" -o "$OUT/$TARGET-egl_bridge.o"
        "$BIN/$TARGET-clang" -std=gnu11 -O2 -Werror=implicit-function-declaration \
            $ARCH_FLAGS -I"$JNI" -c "$JNI/jvm_hooks/lwjgl_dlopen_hook.c" -o "$OUT/$TARGET-lwjgl-hook.o"
        # The translation unit's static assert checks lock-free atomic32. Also
        # reject unexpected out-of-line atomic helpers on either production ABI.
        if "$BIN/llvm-nm" -u "$OUT/$TARGET-egl_bridge.o" | grep -E '__atomic_|__sync_'; then
            echo "FAIL $TARGET coverage snapshot acquired an atomic helper dependency" >&2
            exit 1
        fi
        echo "PASS $TARGET full production egl_bridge.c, loader/readiness atomics (compile only, no device)"
    done
else
    if [ "${NATIVE_REQUIRE_NDK:-0}" = "1" ]; then
        echo "FAIL NDK Vulkan compilation required but ANDROID_NDK_HOME is unset" >&2
        exit 1
    fi
    echo "SKIP Android ARM32/ARM64 Vulkan integration compile: ANDROID_NDK_HOME not set"
fi
