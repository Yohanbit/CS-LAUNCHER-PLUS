#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
JNI="$ROOT/app_pojavlauncher/src/main/jni"
python3 "$ROOT/tools/native_graphics_round2_test.py" "$ROOT"
if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    OUT=$(mktemp -d)
    trap 'rm -rf "$OUT"' EXIT HUP INT TERM
    BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
    for TARGET in armv7a-linux-androideabi21 aarch64-linux-android21; do
        ARCH_FLAGS=
        if [ "$TARGET" = "aarch64-linux-android21" ]; then
            ARCH_FLAGS=-DADRENO_POSSIBLE
        fi
        for SOURCE in egl_bridge.c ctxbridges/egl_loader.c ctxbridges/osmesa_loader.c ctxbridges/gl_bridge.c ctxbridges/osm_bridge.c; do
            "$BIN/$TARGET-clang" -std=gnu11 -O2 -Werror=implicit-function-declaration \
                $ARCH_FLAGS -I"$JNI" -c "$JNI/$SOURCE" -o "$OUT/$TARGET-$(basename "$SOURCE").o"
        done
        echo "PASS $TARGET production native bridge/loader/context translation units (NDK compile, no device execution)"
    done
else
    if [ "${NATIVE_REQUIRE_NDK:-0}" = "1" ]; then
        echo "FAIL NDK graphics compilation required but ANDROID_NDK_HOME is unset" >&2
        exit 1
    fi
    echo "SKIP Android ARM32/ARM64 native integration compile: ANDROID_NDK_HOME not set"
fi
