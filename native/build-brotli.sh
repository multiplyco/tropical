#!/usr/bin/env bash
# Builds the libbrotlienc tropical ships for its brotli encoding
# (co.multiply.tropical.sse.BrotliEncoder), from brotli's release source:
# macOS, one universal file for arm64 and x86_64, with the local clang; Linux,
# x86_64 and aarch64, in Docker. Run on macOS, from the repository root:
# `bb native:brotli`.
#
# The library is brotli's encoder and the code it shares with the decoder, in
# one file, compiled as brotli's own build compiles its shared libraries, with
# all but its API hidden. It exports brotli's API and nothing else, binds its
# own calls to itself, so another libbrotli in the process can't stand in for
# any of it, and needs nothing of libc but malloc, calloc, free, memcpy,
# memmove, memset and exit, and libm's log2, bound at the version every glibc
# has: musl's loader takes it as it is, as glibc's does.
#
# As in every build of brotli's own, the encoder ends the process if malloc
# fails (BROTLI_ENCODER_EXIT_ON_OOM, its default).
set -euo pipefail

VERSION=1.2.1
# GitHub's archive of the release tag. brotli publishes no signature: the
# archive was checked, when this version was taken up, to be the release
# commit, db24a98aed38a16de78cc05ca15cdd8dc2a0d92d, every archived file
# matching that commit, with the omissions its export-ignore attributes name.
SHA256=98d35d11a78dc453aebb7d10c3748f3f73ff78c8267c0dc697b8631f67bf7e8f
# Debian 12 (bookworm-slim), each architecture's image.
IMAGE_x86_64=debian@sha256:a4672c0cb26fbdde88e38fa2dfb6c681942306680e41e4378b28770b6e79ee91
IMAGE_aarch64=debian@sha256:a1b86db52ce3daef089e45aabe36dfec4091f82464c25c1fdcf03de197cbe82a
OUT=resources/co/multiply/tropical/native

# As brotli's CMake builds a release's shared libraries, and hidden by default.
FLAGS="-O3 -DNDEBUG -fPIC -fvisibility=hidden -DBROTLI_SHARED_COMPILATION -DBROTLIENC_SHARED_COMPILATION"
FLAGS+=" -DBROTLICOMMON_SHARED_COMPILATION -Ic/include"
SOURCES="c/common/*.c c/enc/*.c"

[[ "$(uname)" == Darwin ]] || { echo "Run on macOS: the macOS library is built with the local clang." >&2; exit 1; }
[[ -d "$OUT" ]] || { echo "Run from the repository root." >&2; exit 1; }

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

curl -fsSL -o "$work/brotli.tar.gz" "https://github.com/google/brotli/archive/refs/tags/v$VERSION.tar.gz"
echo "$SHA256  $work/brotli.tar.gz" | shasum -a 256 -c -
tar -xzf "$work/brotli.tar.gz" -C "$work"
src="$work/brotli-$VERSION"

# macOS 11 and later.
(cd "$src" && clang $FLAGS -arch arm64 -arch x86_64 -mmacosx-version-min=11.0 -dynamiclib \
  -install_name @rpath/libtropical-brotli.dylib $SOURCES -o "$work/libbrotlienc.dylib")
strip -x "$work/libbrotlienc.dylib"
mkdir -p "$OUT/darwin"
cp "$work/libbrotlienc.dylib" "$OUT/darwin/libbrotlienc.dylib"

for arch in x86_64:amd64:2.2.5 aarch64:arm64:2.17; do
  IFS=: read -r name platform log2 <<< "$arch"
  image=IMAGE_$name
  mkdir -p "$work/out-$name"
  docker run --rm --platform "linux/$platform" -v "$src:/src:ro" -v "$work/out-$name:/out" "${!image}" sh -euc '
      apt-get update -qq
      apt-get install -y -qq --no-install-recommends gcc libc6-dev binutils >/dev/null
      cp -R /src /build
      cd /build
      # glibc 2.29 gave log2 a new version, which would hold the library to
      # glibc 2.29 and later: bind the first, which every glibc has.
      echo "__asm__(\".symver log2,log2@GLIBC_'"$log2"'\");" > /build/log2.h
      gcc '"$FLAGS"' -include /build/log2.h -shared '"$SOURCES"' -lm -o libbrotlienc.so \
        -Wl,-soname=libtropical-brotli.so -Wl,-Bsymbolic -Wl,-z,noexecstack
      strip --strip-unneeded libbrotlienc.so
      cp libbrotlienc.so /out/libbrotlienc.so
      gcc --version | head -1 > /out/compiler
      objdump -T /out/libbrotlienc.so | grep -o "GLIBC_[0-9.]*" | sort -uV | tail -1 > /out/glibc'
  mkdir -p "$OUT/linux-$name"
  cp "$work/out-$name/libbrotlienc.so" "$OUT/linux-$name/libbrotlienc.so"
  echo "linux-$name: $(cat "$work/out-$name/compiler"), needs $(cat "$work/out-$name/glibc")"
done

cp "$src/LICENSE" "$OUT/brotli-LICENSE"
echo "macOS: $(clang --version | head -1)"
(cd "$OUT" && shasum -a 256 darwin/libbrotlienc.dylib linux-*/libbrotlienc.so \
  && ls -l darwin/libbrotlienc.dylib linux-*/libbrotlienc.so)
