#!/bin/sh
# tree-sitter 코어 + tree-sitter-java 네이티브 공유 라이브러리 빌드 (NFR-03)
# 사용법: build-native.sh <소스 디렉터리> <출력 디렉터리>
#  <소스>/tree-sitter      : tree-sitter 코어 소스 (lib/src/lib.c)
#  <소스>/tree-sitter-java : tree-sitter-java 문법 소스 (src/parser.c)
set -eu
SRC="$1"; OUT="$2"; CC="${CC:-cc}"
mkdir -p "$OUT"
CFLAGS="-O2 -fPIC -std=c11 -D_POSIX_C_SOURCE=200112L -D_DEFAULT_SOURCE"
"$CC" $CFLAGS -shared -I"$SRC/tree-sitter/lib/include" -I"$SRC/tree-sitter/lib/src" \
    "$SRC/tree-sitter/lib/src/lib.c" -o "$OUT/libtree-sitter.so"
JSRC="$SRC/tree-sitter-java/src"
EXTRA=""
[ -f "$JSRC/scanner.c" ] && EXTRA="$JSRC/scanner.c"
"$CC" $CFLAGS -shared -I"$JSRC" "$JSRC/parser.c" $EXTRA -o "$OUT/libtree-sitter-java.so"
echo "built: $OUT"
