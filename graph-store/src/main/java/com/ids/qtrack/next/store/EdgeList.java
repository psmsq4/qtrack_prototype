package com.ids.qtrack.next.store;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 가변 길이 간선 리스트 (src[i] → dst[i], 속성 attrs[*][i]). 빌드 버퍼로 씁니다. */
public final class EdgeList {
    public int[] src = new int[16];
    public int[] dst = new int[16];
    public int size;
    public final Map<String, byte[]> byteAttrs = new LinkedHashMap<>();
    public final Map<String, int[]> intAttrs = new LinkedHashMap<>();

    public EdgeList(String[] byteAttrNames, String... intAttrNames) {
        for (String a : byteAttrNames) byteAttrs.put(a, new byte[16]);
        for (String a : intAttrNames) intAttrs.put(a, new int[16]);
    }

    /** 간선 추가. 속성은 생성 시 이름 순서대로 bytes, ints. */
    public int add(int s, int d, int[] bytes, int... ints) {
        if (size == src.length) grow();
        src[size] = s;
        dst[size] = d;
        int i = 0;
        for (byte[] a : byteAttrs.values()) a[size] = (byte) (bytes == null || i >= bytes.length ? 0 : bytes[i++]);
        int j = 0;
        for (int[] a : intAttrs.values()) a[size] = j < ints.length ? ints[j++] : 0;
        return size++;
    }

    public int add(int s, int d, int... bytes) {
        return add(s, d, bytes, new int[0]);
    }

    private void grow() {
        int n = src.length * 2;
        src = Arrays.copyOf(src, n);
        dst = Arrays.copyOf(dst, n);
        byteAttrs.replaceAll((k, v) -> Arrays.copyOf(v, n));
        intAttrs.replaceAll((k, v) -> Arrays.copyOf(v, n));
    }

    public byte[] bytes(String name) {
        return byteAttrs.get(name);
    }

    public int[] ints(String name) {
        return intAttrs.get(name);
    }
}
