#!/usr/bin/env bash
# Build the Rust core for Android and regenerate UniFFI Kotlin bindings.
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/librcode_ffi.so   (cargo-ndk, release)
#   app/build/generated/uniffi/kotlin/uniffi/rcode_ffi/  (UniFFI Kotlin bindings)
#
# Env:
#   ANDROID_NDK_HOME  NDK root (required by cargo-ndk). Falls back to $ANDROID_HOME/ndk/*.
#   CARGO_NDK_ON_ANDROID=1  Needed only when cargo-ndk itself is built on Termux.
#   PROFILE           release (default) | debug
set -euo pipefail

export CARGO_NDK_ON_ANDROID=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
APP="$ROOT/app/src/main"
GEN="$ROOT/app/build/generated/uniffi/kotlin"
PROFILE="${PROFILE:-release}"
LIB_NAME="librcode_ffi.so"
# Ensure cargo-installed tools are found (e.g. cargo-ndk).
export PATH="${CARGO_HOME:-$HOME/.cargo}/bin:$PATH"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [[ -n "$ANDROID_HOME" && -d "$ANDROID_HOME/ndk" ]]; then
        ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/*/ 2>/dev/null | sort -V | tail -n1 | sed 's:/$::')"
        if [[ -z "$ANDROID_NDK_HOME" ]]; then
            ANDROID_NDK_HOME="$ANDROID_HOME/ndk"
        fi
        export ANDROID_NDK_HOME
    fi
fi
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set and no NDK found under ANDROID_HOME}"

command -v cargo-ndk >/dev/null || { echo "cargo-ndk not found; install with: cargo install cargo-ndk --locked" >&2; exit 1; }

PROFILE_FLAG=()
PROFILE_DIR="debug"
if [[ "$PROFILE" == "release" ]]; then
    PROFILE_FLAG=(--release)
    PROFILE_DIR="release"
fi

echo "==> cargo ndk (arm64-v8a, $PROFILE)"
(cd "$CORE" && cargo ndk -t arm64-v8a -o "$APP/jniLibs" build -p rcode-ffi "${PROFILE_FLAG[@]}")

echo "==> UniFFI bindgen (Kotlin)"
(cd "$CORE" && cargo build -p rcode-ffi --features bindgen --bin uniffi-bindgen)
# Generate from the host-built library so metadata matches the Android build.
HOST_LIB="$CORE/target/debug/$LIB_NAME"
(cd "$CORE" && cargo build -p rcode-ffi)
rm -rf "$GEN"
mkdir -p "$GEN"
(cd "$CORE" && "$CORE/target/debug/uniffi-bindgen" generate \
    --library "$HOST_LIB" \
    --language kotlin \
    --out-dir "$GEN" \
    --no-format)

echo "==> done: $APP/jniLibs/arm64-v8a/$LIB_NAME"
