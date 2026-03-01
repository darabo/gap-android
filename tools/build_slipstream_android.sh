#!/usr/bin/env bash
#
# build_slipstream_android.sh
#
# Cross-compiles the Slipstream client for Android (arm64-v8a, armeabi-v7a, x86_64).
# The output .so files are placed in app/src/main/jniLibs/<abi>/ so Gradle picks
# them up automatically.
#
# Prerequisites:
#   - Android NDK (r26+) installed, ANDROID_NDK_HOME set
#   - Meson + Ninja installed (pip install meson ninja, or brew install meson ninja)
#   - Git (to clone Slipstream source)
#   - OpenSSL headers for cross-compilation (auto-handled below)
#
# Usage:
#   export ANDROID_NDK_HOME=/path/to/ndk
#   ./tools/build_slipstream_android.sh
#
# Based on dnstt_xyz_app's build_slipstream_android.sh approach.
# Adapted for direct Meson cross-compilation of C/C++ Slipstream source.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$PROJECT_ROOT/build/slipstream"
SLIPSTREAM_SRC="$BUILD_DIR/slipstream"
JNILIBS_DIR="$PROJECT_ROOT/app/src/full/jniLibs"

# Slipstream repo (official)
SLIPSTREAM_REPO="https://github.com/EndPositive/slipstream.git"
SLIPSTREAM_BRANCH="main"

# Android API level (minimum)
ANDROID_API=24

# Target ABIs and their NDK triples (bash 3.2 compatible)
# Note: armeabi-v7a excluded (micro-ecc asm incompatibility with NDK clang).
# x86_64 excluded (picotls fusion requires aligned_alloc, unavailable in API 24).
# arm64-v8a covers 99%+ of modern Android devices.
ABI_LIST="arm64-v8a"

get_triple() {
    case "$1" in
        arm64-v8a)   echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7a-linux-androideabi" ;;
        x86_64)      echo "x86_64-linux-android" ;;
    esac
}

# ── Validation ──────────────────────────────────────────────────────────

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: ANDROID_NDK_HOME is not set."
    echo "  export ANDROID_NDK_HOME=/path/to/android-ndk-r26d"
    exit 1
fi

if ! command -v meson &>/dev/null; then
    echo "ERROR: meson not found. Install via: pip install meson   OR   brew install meson"
    exit 1
fi

if ! command -v ninja &>/dev/null; then
    echo "ERROR: ninja not found. Install via: pip install ninja   OR   brew install ninja"
    exit 1
fi

# Detect NDK toolchain
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt"
if [ "$(uname)" = "Darwin" ]; then
    TOOLCHAIN="$TOOLCHAIN/darwin-x86_64"
elif [ "$(uname)" = "Linux" ]; then
    TOOLCHAIN="$TOOLCHAIN/linux-x86_64"
fi

if [ ! -d "$TOOLCHAIN" ]; then
    echo "ERROR: NDK toolchain not found at $TOOLCHAIN"
    exit 1
fi

echo "═══════════════════════════════════════════════════════════"
echo " Slipstream Android Cross-Compilation"
echo "═══════════════════════════════════════════════════════════"
echo " NDK:       $ANDROID_NDK_HOME"
echo " Toolchain: $TOOLCHAIN"
echo " API level: $ANDROID_API"
echo " Output:    $JNILIBS_DIR"
echo "═══════════════════════════════════════════════════════════"

# ── Clone / Update Slipstream source ────────────────────────────────────

mkdir -p "$BUILD_DIR"

if [ -d "$SLIPSTREAM_SRC/.git" ]; then
    echo "→ Updating Slipstream source..."
    cd "$SLIPSTREAM_SRC"
    git fetch origin
    git checkout "$SLIPSTREAM_BRANCH"
    git pull --ff-only origin "$SLIPSTREAM_BRANCH" || true
    git submodule update --init --recursive
else
    echo "→ Cloning Slipstream..."
    git clone --recursive -b "$SLIPSTREAM_BRANCH" "$SLIPSTREAM_REPO" "$SLIPSTREAM_SRC"
fi

cd "$SLIPSTREAM_SRC"

# ── Cross-compile OpenSSL for Android ───────────────────────────────────
# picoquic (a Slipstream subproject) requires OpenSSL built for the target ABI.

OPENSSL_VERSION="3.3.2"
OPENSSL_SRC="$BUILD_DIR/openssl-src"
OPENSSL_TARBALL="$BUILD_DIR/openssl-${OPENSSL_VERSION}.tar.gz"

if [ ! -f "$OPENSSL_TARBALL" ]; then
    echo "→ Downloading OpenSSL ${OPENSSL_VERSION}..."
    curl -L -o "$OPENSSL_TARBALL" \
        "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz"
fi

if [ ! -d "$OPENSSL_SRC" ]; then
    echo "→ Extracting OpenSSL..."
    tar -xzf "$OPENSSL_TARBALL" -C "$BUILD_DIR"
    mv "$BUILD_DIR/openssl-${OPENSSL_VERSION}" "$OPENSSL_SRC"
fi

get_openssl_target() {
    case "$1" in
        arm64-v8a)   echo "android-arm64" ;;
        armeabi-v7a) echo "android-arm" ;;
        x86_64)      echo "android-x86_64" ;;
    esac
}

build_openssl_for_abi() {
    local ABI="$1"
    local OPENSSL_INSTALL="$BUILD_DIR/openssl-$ABI"

    if [ -f "$OPENSSL_INSTALL/lib/libssl.a" ]; then
        echo "→ OpenSSL for $ABI already built, skipping."
        return
    fi

    local OPENSSL_TARGET
    OPENSSL_TARGET="$(get_openssl_target "$ABI")"

    echo "→ Building OpenSSL for $ABI ($OPENSSL_TARGET)..."

    # Work in a per-ABI copy to avoid contamination between builds
    local OPENSSL_BUILD="$BUILD_DIR/openssl-build-$ABI"
    rm -rf "$OPENSSL_BUILD"
    cp -r "$OPENSSL_SRC" "$OPENSSL_BUILD"
    cd "$OPENSSL_BUILD"

    export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
    export PATH="$TOOLCHAIN/bin:$PATH"

    ./Configure "$OPENSSL_TARGET" \
        -D__ANDROID_API__="$ANDROID_API" \
        --prefix="$OPENSSL_INSTALL" \
        no-shared \
        no-tests \
        no-ui-console \
        2>&1 | tail -3

    make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)" 2>&1 | tail -3
    make install_sw 2>&1 | tail -3

    cd "$SLIPSTREAM_SRC"
    echo "✓ OpenSSL for $ABI installed at $OPENSSL_INSTALL"
}

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Phase 1: Building OpenSSL for all ABIs"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
for ABI in $ABI_LIST; do
    build_openssl_for_abi "$ABI"
done

# ── Build Slipstream for each ABI ───────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Phase 2: Building Slipstream"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

for ABI in $ABI_LIST; do
    TRIPLE="$(get_triple "$ABI")"
    BUILD_ABI_DIR="$BUILD_DIR/build-$ABI"
    CROSS_FILE="$BUILD_DIR/cross-$ABI.ini"
    OPENSSL_INSTALL="$BUILD_DIR/openssl-$ABI"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo " Building Slipstream for $ABI ($TRIPLE)"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Determine the compiler prefix
    CC_PREFIX="${TRIPLE}${ANDROID_API}"
    if [ "$ABI" = "armeabi-v7a" ]; then
        CC_PREFIX="armv7a-linux-androideabi${ANDROID_API}"
    fi

    CMAKE_ABI="$ABI"

    # Determine cpu_family and cpu for Meson host_machine
    case "$ABI" in
        arm64-v8a)   CPU_FAMILY="aarch64"; CPU="aarch64" ;;
        armeabi-v7a) CPU_FAMILY="arm";     CPU="armv7a" ;;
        x86_64)      CPU_FAMILY="x86_64";  CPU="x86_64" ;;
    esac

    # Create a CMake init script with CACHE variables that FindOpenSSL respects.
    # Meson's [cmake] section can't set CACHE variables, so we use CMAKE_PROJECT_INCLUDE
    # to include this file early in the CMake configure, which sets them properly.
    CMAKE_INIT="$BUILD_DIR/cmake-init-$ABI.cmake"
    cat > "$CMAKE_INIT" <<CMAKEOF
# Disable DTrace (host macOS has it, but Android target doesn't)
set(DTRACE "DTRACE-NOTFOUND" CACHE FILEPATH "" FORCE)
# Point FindOpenSSL to our cross-compiled static libs
set(OPENSSL_ROOT_DIR "${OPENSSL_INSTALL}" CACHE PATH "" FORCE)
set(OPENSSL_INCLUDE_DIR "${OPENSSL_INSTALL}/include" CACHE PATH "" FORCE)
set(OPENSSL_CRYPTO_LIBRARY "${OPENSSL_INSTALL}/lib/libcrypto.a" CACHE FILEPATH "" FORCE)
set(OPENSSL_SSL_LIBRARY "${OPENSSL_INSTALL}/lib/libssl.a" CACHE FILEPATH "" FORCE)
set(OPENSSL_USE_STATIC_LIBS TRUE CACHE BOOL "" FORCE)
CMAKEOF

    # Generate Meson cross-compilation file
    cat > "$CROSS_FILE" <<CROSSEOF
[binaries]
c = '${TOOLCHAIN}/bin/${CC_PREFIX}-clang'
cpp = '${TOOLCHAIN}/bin/${CC_PREFIX}-clang++'
ar = '${TOOLCHAIN}/bin/llvm-ar'
strip = '${TOOLCHAIN}/bin/llvm-strip'
pkgconfig = '$(command -v pkg-config)'
cmake = '$(command -v cmake)'

[host_machine]
system = 'linux'
cpu_family = '${CPU_FAMILY}'
cpu = '${CPU}'
endian = 'little'

[built-in options]
c_args = ['-DANDROID', '-fPIC']
cpp_args = ['-DANDROID', '-fPIC']
c_link_args = ['-llog', '-landroid']
cpp_link_args = ['-llog', '-landroid']

[cmake]
CMAKE_PROJECT_INCLUDE = '${CMAKE_INIT}'
CMAKE_SYSTEM_NAME = 'Generic'
CROSSEOF

    # Clean previous build
    rm -rf "$BUILD_ABI_DIR"

    # Configure with Meson
    echo "→ Configuring Meson for $ABI..."
    meson setup "$BUILD_ABI_DIR" "$SLIPSTREAM_SRC" \
        --cross-file "$CROSS_FILE" \
        --buildtype=release \
        --strip \
        -Ddefault_library=static \
        2>&1 | tail -15

    # Build
    echo "→ Building for $ABI..."
    ninja -C "$BUILD_ABI_DIR" -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" 2>&1 | tail -5

    # Find the output binary
    BINARY=""
    for candidate in \
        "$BUILD_ABI_DIR/slipstream_client" \
        "$BUILD_ABI_DIR/slipstream-client" \
        "$BUILD_ABI_DIR/src/slipstream_client" \
        "$BUILD_ABI_DIR/src/slipstream-client" \
        "$BUILD_ABI_DIR/client/slipstream_client"; do
        if [ -f "$candidate" ]; then
            BINARY="$candidate"
            break
        fi
    done

    if [ -z "$BINARY" ]; then
        echo "⚠ WARNING: Could not find output binary for $ABI"
        echo "  Listing build dir contents:"
        find "$BUILD_ABI_DIR" -type f -executable 2>/dev/null | head -10
        continue
    fi

    # Copy to jniLibs as .so (Android requirement for native extraction)
    OUTPUT_DIR="$JNILIBS_DIR/$ABI"
    mkdir -p "$OUTPUT_DIR"
    cp "$BINARY" "$OUTPUT_DIR/libslipstream_client.so"
    chmod 755 "$OUTPUT_DIR/libslipstream_client.so"

    SIZE=$(du -h "$OUTPUT_DIR/libslipstream_client.so" | cut -f1)
    echo "✓ $ABI: $OUTPUT_DIR/libslipstream_client.so ($SIZE)"
done

echo ""
echo "═══════════════════════════════════════════════════════════"
echo " Build complete!"
echo ""
echo " Output files:"
find "$JNILIBS_DIR" -name "libslipstream_client.so" -exec echo "   {}" \;
echo ""
echo " These will be automatically included in your APK by Gradle."
echo " Run: ./gradlew assembleFullDebug"
echo "═══════════════════════════════════════════════════════════"
