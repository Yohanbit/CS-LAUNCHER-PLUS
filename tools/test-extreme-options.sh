#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -source 8 -target 8 -d "$OUT" "$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/ExtremeLaunchOptions.java" "$ROOT/tools/ExtremeOptionsTest.java"
java -cp "$OUT" ExtremeOptionsTest
