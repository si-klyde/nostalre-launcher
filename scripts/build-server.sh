#!/usr/bin/env bash
# Builds rAthena (server/: SQLite backend, Renewal, nostalRE's packet version) for the host or Android.
# Usage: scripts/build-server.sh host|android
set -euo pipefail
source "$(dirname "$0")/common.sh"

target="${1:?usage: build-server.sh host|android}"
build="$OUT/build/server-$target"
bin="$OUT/$target/bin"
args=(
	-S "$RATHENA" -B "$build" -G Ninja
	-DCMAKE_BUILD_TYPE=Release
	-DENABLE_SQLITE=ON
	-DENABLE_WEB_SERVER=OFF
	-DPACKETVER="$PACKETVER"
	-DCMAKE_RUNTIME_OUTPUT_DIRECTORY="$bin"
	-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY="$build/lib"
	-DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$build/lib"
)

case "$target" in
	host) ;;
	android)
		# shellcheck disable=SC2207
		args+=( $(android_cmake_args)
			# in-tree 3rdparty headers live outside the NDK sysroot
			-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH
			# try-run checks cannot execute when cross compiling; both hold on Android
			-DHAVE_TLS_EXITCODE=0
			-DHAVE_MONOTONIC_CLOCK_EXITCODE=0
			# no librt on Android, clock functions are in libc
			-DRT_LIBRARY="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/$ANDROID_API/libc.so" )
		;;
	*) echo "unknown target: $target" >&2; exit 1 ;;
esac

cmake "${args[@]}"
ninja -C "$build" login-server char-server map-server

if [ "$target" = android ]; then
	for exe in login-server char-server map-server; do
		"$NDK_BIN/llvm-strip" "$bin/$exe"
	done
fi
echo "Built: $bin"
