#!/usr/bin/env bash
# Builds the libzstd tropical ships for its zstd encoding
# (co.multiply.tropical.sse.ZstdEncoder), from zstd's release source: macOS,
# one universal file for arm64 and x86_64, with the local clang; Linux, x86_64
# and aarch64, in Docker. Run on macOS, from the repository root:
# `bb native:zstd`.
#
# The library is zstd's own single-threaded shared library target, with
# compression only: no decoder, no dictionary builder, no worker threads, and
# no tracing hooks. It exports zstd's API and nothing else, binds its own calls
# to itself, so another libzstd in the process can't stand in for any of it,
# and needs nothing of libc but malloc, calloc, free, memcpy, memmove and
# memset: musl's loader takes it as it is, as glibc's does.
set -euo pipefail

VERSION=1.5.7
# The release tarball's SHA-256, as published with it. Its signature was
# checked against zstd's release signing key (signing@zstd.net,
# 4EF4AC63455FC9F4545D9B7DEF8FE99528B52FFD) when this version was taken up.
SHA256=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
# Debian 12 (bookworm-slim), each architecture's image.
IMAGE_x86_64=debian@sha256:a4672c0cb26fbdde88e38fa2dfb6c681942306680e41e4378b28770b6e79ee91
IMAGE_aarch64=debian@sha256:a1b86db52ce3daef089e45aabe36dfec4091f82464c25c1fdcf03de197cbe82a
OUT=resources/co/multiply/tropical/native

MAKE_ARGS=(libzstd-nomt ZSTD_LIB_DECOMPRESSION=0 ZSTD_LIB_DICTBUILDER=0)
FLAGS="-DZSTD_TRACE=0"

[[ "$(uname)" == Darwin ]] || { echo "Run on macOS: the macOS library is built with the local clang." >&2; exit 1; }
[[ -d "$OUT" ]] || { echo "Run from the repository root." >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/zstd.tar.gz" "https://github.com/facebook/zstd/releases/download/v$VERSION/zstd-$VERSION.tar.gz"
echo "$SHA256  $work/zstd.tar.gz" | shasum -a 256 -c -
tar -xzf "$work/zstd.tar.gz" -C "$work"
src="$work/zstd-$VERSION"

# macOS 11 and later.
cp -R "$src" "$work/darwin"
make -s -C "$work/darwin/lib" "${MAKE_ARGS[@]}" \
  MOREFLAGS="$FLAGS -arch arm64 -arch x86_64 -mmacosx-version-min=11.0" \
  SONAME_FLAGS="-install_name @rpath/libtropical-zstd.dylib"
strip -x "$work/darwin/lib/libzstd-nomt"
mkdir -p "$OUT/darwin"
cp "$work/darwin/lib/libzstd-nomt" "$OUT/darwin/libzstd.dylib"

for arch in x86_64:amd64 aarch64:arm64; do
  name=${arch%%:*}
  image=IMAGE_$name
  mkdir -p "$work/out-$name"
  docker run --rm --platform "linux/${arch##*:}" -v "$src:/src:ro" -v "$work/out-$name:/out" "${!image}" sh -euc '
      apt-get update -qq
      apt-get install -y -qq --no-install-recommends gcc libc6-dev make binutils >/dev/null
      cp -R /src /build
      make -s -C /build/lib '"${MAKE_ARGS[*]}"' MOREFLAGS="'"$FLAGS"'" \
        SONAME_FLAGS="-Wl,-soname=libtropical-zstd.so -Wl,-Bsymbolic -Wl,-z,noexecstack"
      strip --strip-unneeded /build/lib/libzstd-nomt
      cp /build/lib/libzstd-nomt /out/libzstd.so
      gcc --version | head -1 > /out/compiler
      objdump -T /out/libzstd.so | grep -o "GLIBC_[0-9.]*" | sort -uV | tail -1 > /out/glibc'
  mkdir -p "$OUT/linux-$name"
  cp "$work/out-$name/libzstd.so" "$OUT/linux-$name/libzstd.so"
  echo "linux-$name: $(cat "$work/out-$name/compiler"), needs $(cat "$work/out-$name/glibc")"
done

cp "$src/LICENSE" "$OUT/zstd-LICENSE"
echo "macOS: $(clang --version | head -1)"
(cd "$OUT" && shasum -a 256 darwin/libzstd.dylib linux-*/libzstd.so && ls -l darwin/libzstd.dylib linux-*/libzstd.so)
