package com.c;

/** 여러 호출자가 공유하는 공통 메서드. 2단계 탐색이 다른 호출자로 새지 않아야 한다 (FR-QR-04). */
public class Util {
    public static String normalize(String s) {
        String t = s.trim();
        return t.toUpperCase();
    }
}
