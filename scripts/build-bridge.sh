#!/usr/bin/env bash
# Builds the bridge (bridge/: grf-server, the hub's game files over HTTP) for the host or Android.
# Usage: scripts/build-bridge.sh host|android
set -euo pipefail
source "$(dirname "$0")/common.sh"

target="${1:?usage: build-bridge.sh host|android}"
build="$OUT/build/bridge-$target"
args=( -S "$BRIDGE" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release
	-DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$OUT/$target/bin" )

# shellcheck disable=SC2207
[ "$target" = android ] && args+=( $(android_cmake_args) )

cmake "${args[@]}"
ninja -C "$build"
[ "$target" = android ] && "$NDK_BIN/llvm-strip" "$OUT/android/bin/grf-server"
echo "Built: $OUT/$target/bin/grf-server"
