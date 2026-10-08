package com.ids.qtrack.next.store;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** 힙 위의 CSR (빌드 결과). 기록 후 버립니다 (NFR-07). */
public final class CsrData {
    public final int[] off;
    public final int[] tgt;
    public final Map<String, byte[]> byteAttrs;
    public final Map<String, int[]> intAttrs;

    CsrData(int[] off, int[] tgt, Map<String, byte[]> byteAttrs, Map<String, int[]> intAttrs) {
        this.off = off;
        this.tgt = tgt;
        this.byteAttrs = byteAttrs;
        this.intAttrs = intAttrs;
    }

    /**
     * 간선 리스트 → CSR. counting sort, O(V + E), 안정 정렬 (설계서 5.4, D-02).
     * 기획서 s17과 달리 간선 순열을 그대로 써서 conf·clause·flags 등 모든 속성을 함께 옮깁니다.
     *
     * @param reverse true면 src와 dst를 바꾼 역방향 CSR
     */
    public static CsrData build(int n, EdgeList edges, boolean reverse) {
        int m = edges.size;
        int[] src = reverse ? edges.dst : edges.src;
        int[] dst = reverse ? edges.src : edges.dst;
        int[] off = new int[n + 1];
        for (int e = 0; e < m; e++) off[src[e] + 1]++;
        for (int i = 0; i < n; i++) off[i + 1] += off[i];
        int[] pos = Arrays.copyOf(off, n);
        int[] tgt = new int[m];
        Map<String, byte[]> outB = new LinkedHashMap<>();
        edges.byteAttrs.forEach((k, v) -> outB.put(k, new byte[m]));
        Map<String, int[]> outI = new LinkedHashMap<>();
        edges.intAttrs.forEach((k, v) -> outI.put(k, new int[m]));
        for (int e = 0; e < m; e++) {
            int p = pos[src[e]]++;
            tgt[p] = dst[e];
            for (var en : edges.byteAttrs.entrySet()) outB.get(en.getKey())[p] = en.getValue()[e];
            for (var en : edges.intAttrs.entrySet()) outI.get(en.getKey())[p] = en.getValue()[e];
        }
        return new CsrData(off, tgt, outB, outI);
    }

    /** {@code <dir>/<prefix>.off|.tgt|.<attr>} 파일로 기록합니다. */
    public long write(Path dir, String prefix) {
        long bytes = 0;
        OffHeap.writeInts(dir.resolve(prefix + ".off"), off);
        OffHeap.writeInts(dir.resolve(prefix + ".tgt"), tgt);
        bytes += 4L * off.length + 4L * tgt.length;
        for (var en : byteAttrs.entrySet()) {
            OffHeap.writeBytes(dir.resolve(prefix + "." + en.getKey()), en.getValue());
            bytes += en.getValue().length;
        }
        for (var en : intAttrs.entrySet()) {
            OffHeap.writeInts(dir.resolve(prefix + "." + en.getKey()), en.getValue());
            bytes += 4L * en.getValue().length;
        }
        return bytes;
    }
}
