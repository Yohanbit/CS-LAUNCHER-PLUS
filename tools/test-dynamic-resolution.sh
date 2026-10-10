#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT INT TERM
javac --release 8 -d "$out" "$root/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/performance/DynamicResolutionController.java" "$root/tools/DynamicResolutionTest.java" "$root/tools/DynamicResolutionResponseRegressionTest.java"
java -cp "$out" DynamicResolutionTest
java -cp "$out" DynamicResolutionResponseRegressionTest
