#!/usr/bin/env bash
# Builds PJSIP/PJSUA2 for Android (spec §4.1, plan Phase 1) and copies the outputs into this module:
#   src/main/jniLibs/<abi>/libpjsua2.so, libc++_shared.so
#   src/main/java/org/pjsip/...          (SWIG-generated Java bindings)
#
# Runs on Linux x86_64 (WSL works). Needs: make, gcc/g++, perl, python3, curl. swig and the NDK
# are fetched into $WORK when missing, so no root access is required.
#
#   sip/pjsip-native/scripts/build-pjsip.sh            # all ABIs
#   ABIS="arm64-v8a" sip/pjsip-native/scripts/build-pjsip.sh
set -euo pipefail

# ---- pinned versions (plan §5 #7) -------------------------------------------------------------
PJSIP_VERSION="2.17"
OPENSSL_VERSION="3.5.9"          # LTS line
NDK_VERSION="r28c"               # = 28.2.13676358; 16 KB page alignment by default
SWIG_WHEEL_URL="https://files.pythonhosted.org/packages/e3/0b/cea4ec8414faf951ec2702416f4dea63f9b5a092472e19cff83a0d1b137f/swig-4.5.1-py3-none-manylinux_2_12_x86_64.manylinux2010_x86_64.whl"
ANDROID_API="${ANDROID_API:-26}"  # must match minSdk
ABIS="${ABIS:-arm64-v8a x86_64}"
# -----------------------------------------------------------------------------------------------

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-$HOME/.cache/simplysip-pjsip}"
JOBS="${JOBS:-$(nproc)}"
mkdir -p "$WORK/dl" "$WORK/tools"

log() { printf '\n==> %s\n' "$*"; }

fetch() { # url dest
    if [[ ! -s "$2" ]]; then
        curl -fsSL --retry 3 -o "$2.part" "$1"
        mv -f "$2.part" "$2"
    fi
}

# Extract a zip keeping unix permissions and symlinks (no `unzip` needed). The NDK relies on
# symlinks such as clang -> clang-19; zipfile alone would write them as text files.
unzip_py() { # zip destdir
    python3 - "$1" "$2" <<'PY'
import os, stat, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as z:
    for info in z.infolist():
        mode = info.external_attr >> 16
        target = os.path.join(dst, info.filename)
        if stat.S_ISLNK(mode):
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.lexists(target):
                os.remove(target)
            os.symlink(z.read(info).decode(), target)
            continue
        path = z.extract(info, dst)
        if mode & 0o777:
            os.chmod(path, mode & 0o777)
PY
}

# ---- NDK --------------------------------------------------------------------------------------
# Use PJSIP_NDK_ROOT to point at an existing NDK of the pinned version. ANDROID_NDK_ROOT is
# deliberately ignored: CI images preset it to whatever NDK they ship.
if [[ -n "${PJSIP_NDK_ROOT:-}" ]]; then
    NDK="$PJSIP_NDK_ROOT"
else
    NDK="$WORK/tools/android-ndk-$NDK_VERSION"
    if [[ ! -d "$NDK" ]]; then
        log "Fetching NDK $NDK_VERSION"
        fetch "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip" "$WORK/dl/ndk.zip"
        unzip_py "$WORK/dl/ndk.zip" "$WORK/tools"
    fi
fi
export ANDROID_NDK_ROOT="$NDK"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# ---- swig -------------------------------------------------------------------------------------
if ! command -v swig >/dev/null; then
    SWIG_DIR="$WORK/tools/swig"
    if [[ ! -x "$SWIG_DIR/swig/data/bin/swig" ]]; then
        log "Fetching swig"
        fetch "$SWIG_WHEEL_URL" "$WORK/dl/swig.whl"
        rm -rf "$SWIG_DIR" && mkdir -p "$SWIG_DIR"
        unzip_py "$WORK/dl/swig.whl" "$SWIG_DIR"
    fi
    export SWIG_LIB="$SWIG_DIR/swig/data/share/swig/$(ls "$SWIG_DIR/swig/data/share/swig" | head -1)"
    mkdir -p "$WORK/tools/bin"
    ln -sf "$SWIG_DIR/swig/data/bin/swig" "$WORK/tools/bin/swig"
    export PATH="$WORK/tools/bin:$PATH"
fi
swig -version | grep -i 'SWIG Version'

# ---- sources ----------------------------------------------------------------------------------
fetch "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz" \
    "$WORK/dl/openssl-$OPENSSL_VERSION.tar.gz"
fetch "https://github.com/pjsip/pjproject/archive/refs/tags/$PJSIP_VERSION.tar.gz" \
    "$WORK/dl/pjproject-$PJSIP_VERSION.tar.gz"

openssl_target() {
    case "$1" in
        arm64-v8a) echo android-arm64 ;;
        x86_64) echo android-x86_64 ;;
        armeabi-v7a) echo android-arm ;;
        *) echo "unsupported ABI $1" >&2; exit 1 ;;
    esac
}

OUT_JNI="$MODULE_DIR/src/main/jniLibs"
OUT_JAVA="$MODULE_DIR/src/main/java"
rm -rf "$OUT_JNI" "$OUT_JAVA/org/pjsip"
mkdir -p "$OUT_JNI" "$OUT_JAVA"

for ABI in $ABIS; do
    BUILD="$WORK/build/$ABI"
    rm -rf "$BUILD" && mkdir -p "$BUILD"

    # ---- OpenSSL (static) -- TLS for SIP signalling, SRTP keying ----
    log "[$ABI] OpenSSL $OPENSSL_VERSION"
    tar -xzf "$WORK/dl/openssl-$OPENSSL_VERSION.tar.gz" -C "$BUILD"
    (
        cd "$BUILD/openssl-$OPENSSL_VERSION"
        export PATH="$TOOLCHAIN/bin:$PATH"
        ./Configure "$(openssl_target "$ABI")" -D__ANDROID_API__="$ANDROID_API" \
            no-shared no-tests no-docs no-apps --prefix="$BUILD/openssl" --libdir=lib >/dev/null
        make -j"$JOBS" >/dev/null
        make install_sw >/dev/null
    )

    # ---- PJSIP ----
    log "[$ABI] PJSIP $PJSIP_VERSION"
    tar -xzf "$WORK/dl/pjproject-$PJSIP_VERSION.tar.gz" -C "$BUILD"
    PJ="$BUILD/pjproject-$PJSIP_VERSION"
    cat > "$PJ/pjlib/include/pj/config_site.h" <<'EOF'
#define PJ_CONFIG_ANDROID 1
#include <pj/config_site_sample.h>
/* Voice only for the MVP (spec §2.3). */
#define PJMEDIA_HAS_VIDEO 0
/* SRTP is required for the production profile (spec §5.4). */
#define PJMEDIA_HAS_SRTP 1
#define PJ_HAS_SSL_SOCK 1
EOF
    (
        cd "$PJ"
        TARGET_ABI="$ABI" APP_PLATFORM="android-$ANDROID_API" \
            ./configure-android --use-ndk-cflags --with-ssl="$BUILD/openssl" \
            --disable-video --disable-v4l2 --disable-libyuv --disable-openh264 >"$BUILD/configure.log" 2>&1 \
            || { tail -40 "$BUILD/configure.log"; exit 1; }
        grep -E 'Checking for OpenSSL|OpenSSL' "$BUILD/configure.log" | tail -3 || true
        make dep >"$BUILD/make-dep.log" 2>&1
        make -j"$JOBS" >"$BUILD/make.log" 2>&1 || { tail -60 "$BUILD/make.log"; exit 1; }
        cd pjsip-apps/src/swig
        make >"$BUILD/swig.log" 2>&1 || { tail -60 "$BUILD/swig.log"; exit 1; }
    )

    SWIG_OUT="$PJ/pjsip-apps/src/swig/java/android/pjsua2/src/main"
    mkdir -p "$OUT_JNI/$ABI"
    cp "$SWIG_OUT/jniLibs/$ABI/"*.so "$OUT_JNI/$ABI/"
    # Java bindings are ABI independent; copy once.
    [[ -d "$OUT_JAVA/org/pjsip" ]] || cp -r "$SWIG_OUT/java/org" "$OUT_JAVA/"
done

# ---- verify 16 KB page alignment (plan §4) ----------------------------------------------------
log "Checking ELF LOAD alignment"
status=0
for so in "$OUT_JNI"/*/*.so; do
    # No early `exit` in awk: with pipefail, closing the pipe early fails the script.
    align=$("$TOOLCHAIN/bin/llvm-readelf" -lW "$so" | awk '/LOAD/ && !found {print $NF; found=1}')
    printf '%-60s %s\n' "${so#"$MODULE_DIR"/}" "$align"
    case "$so" in
        */arm64-v8a/*|*/x86_64/*)
            [[ $((align)) -ge 16384 ]] || { echo "  ^ not 16 KB aligned" >&2; status=1; } ;;
    esac
done

cat > "$MODULE_DIR/BUILD_INFO" <<EOF
pjsip=$PJSIP_VERSION
openssl=$OPENSSL_VERSION
ndk=$NDK_VERSION
android_api=$ANDROID_API
abis=$ABIS
swig=$(swig -version | awk '/SWIG Version/ {print $3}')
EOF
log "Done → $OUT_JNI, $OUT_JAVA/org/pjsip"
exit $status
