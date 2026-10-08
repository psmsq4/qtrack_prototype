package com.ids.qtrack.next.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 인덱스 스냅샷 읽기 전용 뷰. 모든 배열은 역직렬화 없이 mmap된 MemorySegment로 접근합니다 (FR-GR-08, NFR-02).
 * L2 chunk는 질의가 경로를 펼칠 때만 해당 구간을 map합니다 (FR-QR-06).
 */
public final class Snapshot implements AutoCloseable {
    public final Path dir;
    private final Arena arena = Arena.ofShared();
    public final Map<String, Object> meta;
    public final StringDict strings;
    private final MemorySegment kind, owner, name, span, chunk;
    private final MemorySegment mStart, mEnd, mSig, mFlags, mFo, mFile;
    public final Csr formalIns;
    private final Csr[][] csr = new Csr[Kinds.EDGE_NAMES.length][2];
    public final Csr callFwd, callRev;
    private final MemorySegment chunkIdx;
    private final FileChannel chunkChannel;
    private final Map<Integer, L2Chunk> chunkCache = new ConcurrentHashMap<>();
    private final List<String> files = new ArrayList<>();
    public final SortedIndex columnIdx, endpointIdx, endpointParamIdx, methodIdx;
    private final MemorySegment summaryTbl;
    private long mappedL2Bytes;

    private Snapshot(Path dir) throws IOException {
        this.dir = dir;
        meta = Json.parseObject(Files.readString(dir.resolve(Layout.META)));
        int ver = ((Number) meta.get("schemaVersion")).intValue();
        if (ver != Layout.SCHEMA_VERSION) throw new IllegalStateException("snapshot schema " + ver);
        strings = StringDict.open(dir.resolve(Layout.STRINGS), arena);
        Path nd = dir.resolve(Layout.NODES);
        kind = OffHeap.map(nd.resolve("kind.u8"), arena);
        owner = OffHeap.map(nd.resolve("owner.i32"), arena);
        name = OffHeap.map(nd.resolve("name.i32"), arena);
        span = OffHeap.map(nd.resolve("span.i64"), arena);
        chunk = OffHeap.map(nd.resolve("chunk.i32"), arena);
        Path md = dir.resolve(Layout.METHODS);
        mStart = OffHeap.map(md.resolve("start.i32"), arena);
        mEnd = OffHeap.map(md.resolve("end.i32"), arena);
        mSig = OffHeap.map(md.resolve("sig.i32"), arena);
        mFlags = OffHeap.map(md.resolve("flags.u8"), arena);
        mFo = OffHeap.map(md.resolve("fo.i32"), arena);
        mFile = OffHeap.map(md.resolve("file.i32"), arena);
        formalIns = Csr.open(md, "fin", arena);
        Path iface = dir.resolve(Layout.IFACE);
        for (int k : Kinds.RESIDENT) {
            csr[k][0] = Csr.open(iface, Kinds.EDGE_NAMES[k] + ".fwd", arena);
            csr[k][1] = Csr.open(iface, Kinds.EDGE_NAMES[k] + ".rev", arena);
        }
        summaryTbl = OffHeap.map(iface.resolve(Layout.SUMMARY_TBL), arena);
        Path idx = dir.resolve(Layout.IDX);
        callFwd = Csr.open(idx, Layout.CALL + ".fwd", arena);
        callRev = Csr.open(idx, Layout.CALL + ".rev", arena);
        columnIdx = SortedIndex.open(idx.resolve(Layout.COLUMN_IDX), arena);
        endpointIdx = SortedIndex.open(idx.resolve(Layout.ENDPOINT_IDX), arena);
        endpointParamIdx = SortedIndex.open(idx.resolve(Layout.ENDPOINT_PARAM_IDX), arena);
        methodIdx = SortedIndex.open(idx.resolve(Layout.METHOD_IDX), arena);
        Path l2 = dir.resolve(Layout.L2);
        chunkIdx = OffHeap.map(l2.resolve(Layout.CHUNKS_IDX), arena);
        chunkChannel = FileChannel.open(l2.resolve(Layout.CHUNKS_BIN), StandardOpenOption.READ);
        for (String line : Files.readAllLines(dir.resolve(Layout.FILES))) {
            if (line.isBlank()) continue;
            String[] p = line.split("\t", 2);
            int id = Integer.parseInt(p[0]);
            while (files.size() <= id) files.add("");
            files.set(id, p[1]);
        }
    }

    public static Snapshot open(Path dir) {
        try {
            return new Snapshot(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int nodeCount() {
        return (int) kind.byteSize();
    }

    public int kind(int gid) {
        return OffHeap.getU8(kind, gid);
    }

    public int owner(int gid) {
        return OffHeap.getInt(owner, gid);
    }

    public int nameId(int gid) {
        return OffHeap.getInt(name, gid);
    }

    public String name(int gid) {
        return strings.get(nameId(gid));
    }

    public long span(int gid) {
        return OffHeap.getLong(span, gid);
    }

    public int chunkStart(int gid) {
        return OffHeap.getInt(chunk, gid);
    }

    public int methodCount() {
        return (int) (mStart.byteSize() / 4);
    }

    public int methodStart(int m) {
        return OffHeap.getInt(mStart, m);
    }

    public int methodEnd(int m) {
        return OffHeap.getInt(mEnd, m);
    }

    public String methodSig(int m) {
        return strings.get(OffHeap.getInt(mSig, m));
    }

    /** bit0 = synthetic(SQL), bit1 = abstract, bit2 = endpoint handler. */
    public int methodFlags(int m) {
        return OffHeap.getU8(mFlags, m);
    }

    public int methodFormalOut(int m) {
        return OffHeap.getInt(mFo, m);
    }

    public int methodFile(int m) {
        return OffHeap.getInt(mFile, m);
    }

    public int[] methodFormalIns(int m) {
        int b = formalIns.begin(m), e = formalIns.end(m);
        int[] r = new int[e - b];
        for (int i = b; i < e; i++) r[i - b] = formalIns.target(i);
        return r;
    }

    public Csr csr(int edgeKind, boolean reverse) {
        Csr c = csr[edgeKind][reverse ? 1 : 0];
        return c == null ? Csr.EMPTY : c;
    }

    public String file(int fileId) {
        return fileId >= 0 && fileId < files.size() ? files.get(fileId) : "";
    }

    public int fileCount() {
        return files.size();
    }

    /** 메서드 m의 L2 chunk. 처음 접근할 때 해당 구간만 map합니다. */
    public L2Chunk chunk(int m) {
        return chunkCache.computeIfAbsent(m, k -> {
            long off = OffHeap.getLong(chunkIdx, 2L * k), len = OffHeap.getLong(chunkIdx, 2L * k + 1);
            try {
                MemorySegment seg = chunkChannel.map(FileChannel.MapMode.READ_ONLY, off, len, arena);
                synchronized (this) { mappedL2Bytes += len; }
                return new L2Chunk(seg);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    public int chunksOpened() {
        return chunkCache.size();
    }

    public long mappedL2Bytes() {
        return mappedL2Bytes;
    }

    /** summary.tbl: [n] (method, src gid, dst gid, masks) × n. */
    public int summaryCount() {
        return summaryTbl.byteSize() == 0 ? 0 : OffHeap.getInt(summaryTbl, 0);
    }

    public int[] summaryRow(int i) {
        return new int[]{OffHeap.getInt(summaryTbl, 1 + 4L * i), OffHeap.getInt(summaryTbl, 2 + 4L * i),
                OffHeap.getInt(summaryTbl, 3 + 4L * i), OffHeap.getInt(summaryTbl, 4 + 4L * i)};
    }

    /** 상주 영역 바이트 (노드 속성 + 상주 CSR). L2 제외. */
    public long residentBytes() {
        long b = kind.byteSize() + owner.byteSize() + name.byteSize() + span.byteSize() + chunk.byteSize();
        for (int k : Kinds.RESIDENT) b += csr(k, false).byteSize() + csr(k, true).byteSize();
        return b;
    }

    @Override
    public void close() {
        try {
            chunkChannel.close();
        } catch (IOException ignored) {
        }
        arena.close();
    }
}
