package com.ids.qtrack.next.store;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 문자열 사전 strings.dict. 형식: [n.i32][off.i32 × (n+1)][utf8 bytes].
 * 읽을 때는 mmap 후 필요한 문자열만 디코딩합니다.
 */
public final class StringDict {
    private final MemorySegment seg;
    private final int n;

    private StringDict(MemorySegment seg) {
        this.seg = seg;
        this.n = seg.byteSize() == 0 ? 0 : OffHeap.getInt(seg, 0);
    }

    public static StringDict open(Path file, Arena arena) {
        return new StringDict(OffHeap.map(file, arena));
    }

    public int size() {
        return n;
    }

    public String get(int id) {
        if (id < 0 || id >= n) return "";
        int a = OffHeap.getInt(seg, 1 + id), b = OffHeap.getInt(seg, 2 + id);
        long base = 4L * (n + 2);
        byte[] bytes = seg.asSlice(base + a, b - a).toArray(OffHeap.BYTE);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public long byteSize() {
        return seg.byteSize();
    }

    /** 빌드용 인터닝 사전. */
    public static final class Builder {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> list = new ArrayList<>();

        public int intern(String s) {
            if (s == null) s = "";
            Integer id = ids.get(s);
            if (id != null) return id;
            ids.put(s, list.size());
            list.add(s);
            return list.size() - 1;
        }

        public String get(int id) {
            return list.get(id);
        }

        public int size() {
            return list.size();
        }

        public void write(Path file) {
            ByteArrayOutputStream blob = new ByteArrayOutputStream();
            int[] off = new int[list.size() + 1];
            for (int i = 0; i < list.size(); i++) {
                byte[] b = list.get(i).getBytes(StandardCharsets.UTF_8);
                blob.writeBytes(b);
                off[i + 1] = off[i] + b.length;
            }
            byte[] data = blob.toByteArray();
            int n = list.size();
            OffHeap.write(file, 4L * (n + 2) + data.length, seg -> {
                seg.setAtIndex(OffHeap.INT, 0, n);
                for (int i = 0; i <= n; i++) seg.setAtIndex(OffHeap.INT, 1 + i, off[i]);
                MemorySegment.copy(data, 0, seg, OffHeap.BYTE, 4L * (n + 2), data.length);
            });
        }
    }
}
