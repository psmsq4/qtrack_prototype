package com.ids.qtrack.next.catalog;

import java.util.Locale;

/** Oracle 식별자 정규화: 따옴표가 없으면 대문자, 있으면 따옴표만 벗깁니다. */
public final class Names {
    private Names() {}

    public static String norm(String id) {
        if (id == null) return null;
        String s = id.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("`") && s.endsWith("`")))
            return s.substring(1, s.length() - 1);
        return s.toUpperCase(Locale.ROOT);
    }
}
