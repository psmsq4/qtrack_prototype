package com.ids.qtrack.next.store;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * mmap된 CSR 하나 (간선 종류 하나 × 방향 하나). 노드 v의 이웃은 targets[off[v]..off[v+1]),
 * i번째 이웃의 신뢰도·속성은 같은 위치 i에 있습니다 (FR-GR-05).
 */
public final class Csr {
    public static final Csr EMPTY = new Csr(OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY,
            OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY);

    private final MemorySegment off, tgt, conf, clause, flags, masks, label, caseVal, site;

    private Csr(MemorySegment off, MemorySegment tgt, MemorySegment conf, MemorySegment clause, MemorySegment flags,
                MemorySegment masks, MemorySegment label, MemorySegment caseVal, MemorySegment site) {
        this.off = off;
        this.tgt = tgt;
        this.conf = conf;
        this.clause = clause;
        this.flags = flags;
        this.masks = masks;
        this.label = label;
        this.caseVal = caseVal;
        this.site = site;
    }

    public static Csr open(Path dir, String prefix, Arena arena) {
        MemorySegment off = OffHeap.map(dir.resolve(prefix + ".off"), arena);
        if (off.byteSize() == 0) return EMPTY;
        return new Csr(off, OffHeap.map(dir.resolve(prefix + ".tgt"), arena),
                OffHeap.map(dir.resolve(prefix + ".conf"), arena),
                OffHeap.map(dir.resolve(prefix + ".clause"), arena),
                OffHeap.map(dir.resolve(prefix + ".flags"), arena),
                OffHeap.map(dir.resolve(prefix + ".masks"), arena),
                OffHeap.map(dir.resolve(prefix + ".label"), arena),
                OffHeap.map(dir.resolve(prefix + ".caseVal"), arena),
                OffHeap.map(dir.resolve(prefix + ".site"), arena));
    }

    /** 메모리 위의 세그먼트로 만든 CSR (L2 chunk용). */
    public static Csr of(MemorySegment off, MemorySegment tgt, MemorySegment conf, MemorySegment label,
                         MemorySegment caseVal) {
        return new Csr(off, tgt, conf, OffHeap.EMPTY, OffHeap.EMPTY, OffHeap.EMPTY, label, caseVal, OffHeap.EMPTY);
    }

    public boolean isEmpty() {
        return off.byteSize() == 0;
    }

    public int nodeCount() {
        return off.byteSize() == 0 ? 0 : (int) (off.byteSize() / 4) - 1;
    }

    public int edgeCount() {
        return (int) (tgt.byteSize() / 4);
    }

    public int begin(int v) {
        if (off.byteSize() == 0 || v + 1 >= off.byteSize() / 4) return 0;
        return OffHeap.getInt(off, v);
    }

    public int end(int v) {
        if (off.byteSize() == 0 || v + 1 >= off.byteSize() / 4) return 0;
        return OffHeap.getInt(off, v + 1);
    }

    public int target(int i) {
        return OffHeap.getInt(tgt, i);
    }

    public int conf(int i) {
        return OffHeap.getU8(conf, i);
    }

    public int clause(int i) {
        return OffHeap.getU8(clause, i);
    }

    public int flags(int i) {
        return OffHeap.getU8(flags, i);
    }

    public int masks(int i) {
        return OffHeap.getU8(masks, i);
    }

    public int label(int i) {
        return OffHeap.getU8(label, i);
    }

    public int caseVal(int i) {
        return caseVal.byteSize() == 0 ? -1 : OffHeap.getInt(caseVal, i);
    }

    public int site(int i) {
        return site.byteSize() == 0 ? -1 : OffHeap.getInt(site, i);
    }

    public long byteSize() {
        return off.byteSize() + tgt.byteSize() + conf.byteSize() + clause.byteSize() + flags.byteSize()
                + masks.byteSize() + label.byteSize() + caseVal.byteSize() + site.byteSize();
    }
}
