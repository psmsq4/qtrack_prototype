package com.ids.qtrack.next.store;

import java.lang.foreign.MemorySegment;

/**
 * 메서드 하나의 L2 chunk (설계서 5.6 l2/chunks.bin). lid 공간: 인터페이스 노드가 앞쪽(0..nIface), 그 뒤 LOCAL·PREDICATE.
 * <pre>
 * [nNodes, nIface, eDefUse, eControl].i32
 * span.i64[n] kind.i32[n] name.i32[n]
 * DEF_USE fwd: off.i32[n+1] tgt.i32[e] conf.u8[e]     rev: 동일
 * CONTROL fwd: off.i32[n+1] tgt.i32[e] conf.u8[e] label.u8[e] caseVal.i32[e]   rev: 동일
 * </pre>
 */
public final class L2Chunk {
    private final MemorySegment seg;
    public final int nodes, iface, eDefUse, eControl;
    private final long spanAt, kindAt, nameAt;
    public final Csr defUseFwd, defUseRev, controlFwd, controlRev;

    public L2Chunk(MemorySegment seg) {
        this.seg = seg;
        nodes = OffHeap.getInt(seg, 0);
        iface = OffHeap.getInt(seg, 1);
        eDefUse = OffHeap.getInt(seg, 2);
        eControl = OffHeap.getInt(seg, 3);
        long p = 16;
        spanAt = p; p += 8L * nodes;
        kindAt = p; p += 4L * nodes;
        nameAt = p; p += 4L * nodes;
        long[] cur = {p};
        defUseFwd = readCsr(cur, eDefUse, false);
        defUseRev = readCsr(cur, eDefUse, false);
        controlFwd = readCsr(cur, eControl, true);
        controlRev = readCsr(cur, eControl, true);
    }

    private MemorySegment take(long[] cur, long len) {
        MemorySegment s = len == 0 ? OffHeap.EMPTY : seg.asSlice(cur[0], len);
        cur[0] += len;
        return s;
    }

    private Csr readCsr(long[] cur, int e, boolean control) {
        MemorySegment off = take(cur, 4L * (nodes + 1));
        MemorySegment tgt = take(cur, 4L * e);
        MemorySegment conf = take(cur, e);
        MemorySegment label = control ? take(cur, e) : OffHeap.EMPTY;
        MemorySegment caseVal = control ? take(cur, 4L * e) : OffHeap.EMPTY;
        return Csr.of(off, tgt, conf, label, caseVal);
    }

    public int kind(int lid) {
        return OffHeap.getInt(seg.asSlice(kindAt), lid);
    }

    public int name(int lid) {
        return OffHeap.getInt(seg.asSlice(nameAt), lid);
    }

    public long span(int lid) {
        return OffHeap.getLong(seg.asSlice(spanAt), lid);
    }

    /** chunk 직렬화. */
    public static byte[] encode(int n, int nIface, long[] span, int[] kind, int[] name, EdgeList defUse, EdgeList control) {
        CsrData duF = CsrData.build(n, defUse, false), duR = CsrData.build(n, defUse, true);
        CsrData ctF = CsrData.build(n, control, false), ctR = CsrData.build(n, control, true);
        int eDu = defUse.size, eCt = control.size;
        long size = 16 + 16L * n
                + 2 * (4L * (n + 1) + 5L * eDu)
                + 2 * (4L * (n + 1) + 10L * eCt);
        byte[] out = new byte[(int) size];
        MemorySegment seg = MemorySegment.ofArray(out);
        seg.setAtIndex(OffHeap.INT, 0, n);
        seg.setAtIndex(OffHeap.INT, 1, nIface);
        seg.setAtIndex(OffHeap.INT, 2, eDu);
        seg.setAtIndex(OffHeap.INT, 3, eCt);
        long[] p = {16};
        for (int i = 0; i < n; i++) { seg.set(OffHeap.LONG, p[0], span[i]); p[0] += 8; }
        for (int i = 0; i < n; i++) { seg.set(OffHeap.INT, p[0], kind[i]); p[0] += 4; }
        for (int i = 0; i < n; i++) { seg.set(OffHeap.INT, p[0], name[i]); p[0] += 4; }
        for (CsrData c : new CsrData[]{duF, duR}) putCsr(seg, p, c, false);
        for (CsrData c : new CsrData[]{ctF, ctR}) putCsr(seg, p, c, true);
        return out;
    }

    private static void putCsr(MemorySegment seg, long[] p, CsrData c, boolean control) {
        for (int v : c.off) { seg.set(OffHeap.INT, p[0], v); p[0] += 4; }
        for (int v : c.tgt) { seg.set(OffHeap.INT, p[0], v); p[0] += 4; }
        for (byte v : c.byteAttrs.get("conf")) { seg.set(OffHeap.BYTE, p[0], v); p[0] += 1; }
        if (control) {
            for (byte v : c.byteAttrs.get("label")) { seg.set(OffHeap.BYTE, p[0], v); p[0] += 1; }
            for (int v : c.intAttrs.get("caseVal")) { seg.set(OffHeap.INT, p[0], v); p[0] += 4; }
        }
    }
}
