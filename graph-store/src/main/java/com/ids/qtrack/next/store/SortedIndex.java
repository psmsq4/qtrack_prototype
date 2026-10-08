package com.ids.qtrack.next.store;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 정렬된 (문자열 키 → int 값) 색인. 같은 키에 값이 여럿일 수 있습니다.
 * 형식: [n.i32][keyOff.i32 × (n+1)][value.i32 × n][utf8 keys]. mmap 상태에서 이진 탐색합니다 (FR-QR-07).
 */
public final class SortedIndex {
    private final MemorySegment seg;
    private final int n;

    private SortedIndex(MemorySegment seg) {
        this.seg = seg;
        this.n = seg.byteSize() == 0 ? 0 : OffHeap.getInt(seg, 0);
    }

    public static SortedIndex open(Path file, Arena arena) {
        return new SortedIndex(OffHeap.map(file, arena));
    }

    public int size() {
        return n;
    }

    private byte[] keyBytes(int i) {
        int a = OffHeap.getInt(seg, 1 + i), b = OffHeap.getInt(seg, 2 + i);
        long base = 4L * (2 + 2L * n);
        return seg.asSlice(base + a, b - a).toArray(OffHeap.BYTE);
    }

    public String key(int i) {
        return new String(keyBytes(i), StandardCharsets.UTF_8);
    }

    public int value(int i) {
        return OffHeap.getInt(seg, 2 + n + i);
    }

    /** key 이상인 첫 위치. */
    private int lowerBound(byte[] key) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (Arrays.compareUnsigned(keyBytes(mid), key) < 0) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    public int[] lookup(String key) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        List<Integer> out = new ArrayList<>();
        for (int i = lowerBound(k); i < n && Arrays.equals(keyBytes(i), k); i++) out.add(value(i));
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 접두사가 같은 (키, 값) 목록. */
    public List<Entry> prefix(String prefix) {
        byte[] k = prefix.getBytes(StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        for (int i = lowerBound(k); i < n; i++) {
            byte[] kb = keyBytes(i);
            if (kb.length < k.length || !Arrays.equals(kb, 0, k.length, k, 0, k.length)) break;
            out.add(new Entry(new String(kb, StandardCharsets.UTF_8), value(i)));
        }
        return out;
    }

    public List<Entry> all() {
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(new Entry(key(i), value(i)));
        return out;
    }

    public long byteSize() {
        return seg.byteSize();
    }

    public record Entry(String key, int value) {}

    public static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        public Builder put(String key, int value) {
            entries.add(new Entry(key, value));
            return this;
        }

        public void write(Path file) {
            List<byte[]> keys = new ArrayList<>();
            entries.sort(Comparator.<Entry, byte[]>comparing(e -> e.key.getBytes(StandardCharsets.UTF_8),
                    Arrays::compareUnsigned).thenComparingInt(Entry::value));
            int n = entries.size();
            int[] off = new int[n + 1];
            for (int i = 0; i < n; i++) {
                byte[] b = entries.get(i).key.getBytes(StandardCharsets.UTF_8);
                keys.add(b);
                off[i + 1] = off[i] + b.length;
            }
            long base = 4L * (2 + 2L * n);
            OffHeap.write(file, base + off[n], seg -> {
                seg.setAtIndex(OffHeap.INT, 0, n);
                for (int i = 0; i <= n; i++) seg.setAtIndex(OffHeap.INT, 1 + i, off[i]);
                for (int i = 0; i < n; i++) seg.setAtIndex(OffHeap.INT, 2 + n + i, entries.get(i).value);
                for (int i = 0; i < n; i++) MemorySegment.copy(keys.get(i), 0, seg, OffHeap.BYTE, base + off[i], keys.get(i).length);
            });
        }
    }
}
