package com.ids.qtrack.next.sql;

/** SQL 텍스트 전처리: 바인드 슬롯 표기 {@code :__b<slot>} 와 JDBC {@code ?} 치환. */
public final class SqlText {
    private SqlText() {}

    public static final String BIND_PREFIX = "__b";

    public static String bindName(int slot) {
        return ":" + BIND_PREFIX + slot;
    }

    public static int slotOf(String name) {
        if (name == null || !name.startsWith(BIND_PREFIX)) return -1;
        try {
            return Integer.parseInt(name.substring(BIND_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 문자열 밖의 {@code ?}를 순서대로 {@code :__b<base+i>}로 바꿉니다. 바꾼 개수는 out[0]. */
    public static String numberQuestionMarks(String sql, int base, int[] out) {
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        int n = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') q = !q;
            if (c == '?' && !q) {
                sb.append(bindName(base + n++));
            } else sb.append(c);
        }
        if (out != null && out.length > 0) out[0] = n;
        return sb.toString();
    }

    /** 공백 정리 (리포트·샘플용). */
    public static String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
