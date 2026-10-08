package com.ids.qtrack.next.rules;

import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.NativeLibraryLookup;
import io.github.treesitter.jtreesitter.Parser;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * tree-sitter 네이티브 라이브러리 로딩. 위치는 시스템 속성 {@code qtrack.native.dir} 또는 환경 변수 {@code QTRACK_NATIVE_DIR}.
 * jtreesitter가 ServiceLoader로 찾는 {@link NativeLibraryLookup} 구현이기도 합니다.
 */
public final class TreeSitterJava implements NativeLibraryLookup {
    private static volatile Language language;

    public static Path nativeDir() {
        String d = System.getProperty("qtrack.native.dir");
        if (d == null || d.isEmpty()) d = System.getenv("QTRACK_NATIVE_DIR");
        if (d == null || d.isEmpty())
            throw new IllegalStateException("tree-sitter 네이티브 라이브러리 위치가 없습니다: -Dqtrack.native.dir=<dir>");
        return Path.of(d);
    }

    private static Path lib(String name) {
        Path p = nativeDir().resolve(System.mapLibraryName(name));
        if (!Files.exists(p)) throw new IllegalStateException("네이티브 라이브러리 없음: " + p);
        return p;
    }

    @Override
    public SymbolLookup get(Arena arena) {
        return SymbolLookup.libraryLookup(lib("tree-sitter"), arena);
    }

    public static Language language() {
        if (language == null) {
            synchronized (TreeSitterJava.class) {
                if (language == null)
                    language = Language.load(SymbolLookup.libraryLookup(lib("tree-sitter-java"), Arena.global()), "tree_sitter_java");
            }
        }
        return language;
    }

    public static Parser parser() {
        return new Parser(language());
    }
}
