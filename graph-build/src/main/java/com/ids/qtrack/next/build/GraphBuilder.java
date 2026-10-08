package com.ids.qtrack.next.build;

import com.ids.qtrack.next.ir.CallKind;
import com.ids.qtrack.next.ir.CallSite;
import com.ids.qtrack.next.ir.ClassIR;
import com.ids.qtrack.next.ir.ColumnKey;
import com.ids.qtrack.next.ir.Edge;
import com.ids.qtrack.next.ir.EdgeKind;
import com.ids.qtrack.next.ir.EndpointIR;
import com.ids.qtrack.next.ir.EndpointParam;
import com.ids.qtrack.next.ir.ExternalRef;
import com.ids.qtrack.next.ir.FieldDecl;
import com.ids.qtrack.next.ir.FileIR;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.Node;
import com.ids.qtrack.next.ir.NodeKind;
import com.ids.qtrack.next.store.CsrData;
import com.ids.qtrack.next.store.EdgeList;
import com.ids.qtrack.next.store.Json;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.L2Chunk;
import com.ids.qtrack.next.store.Layout;
import com.ids.qtrack.next.store.OffHeap;
import com.ids.qtrack.next.store.SortedIndex;
import com.ids.qtrack.next.store.StringDict;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * IR → 인덱스 스냅샷 (설계서 2.2 단계 2~5).
 * <ol>
 *   <li>ID 배정: 메서드 chunk마다 인터페이스 노드 먼저(gid), LOCAL·PREDICATE는 chunk 로컬 lid (D-01)</li>
 *   <li>LOCAL_FLOW / CONTROL_FLOW 축약 (설계서 3.1, 3.2.4)</li>
 *   <li>링크: 호출 대상 해석(타입, DI, 구현 클래스, Mapper ↔ XML) → ARG_IN/RET_OUT, 엔드포인트</li>
 *   <li>요약: SCC 고정점 + 플래그 마스크 (D-03, 6장) → SUMMARY</li>
 *   <li>CSR: 종류 하나씩 빌드·기록·해제 (D-02, NFR-07) / L2 chunk / 색인 / meta.json</li>
 * </ol>
 */
public final class GraphBuilder {
    public record Options(int expansionLimit, Map<String, Object> extraMeta) {
        public static Options defaults() {
            return new Options(64, Map.of());
        }
    }

    // ───────────────────────────── 메서드 표 ─────────────────────────────

    static final class M {
        int idx;
        MethodIR ir;
        int fileId;
        int n, nIface, start;
        int[] newLid;                    // IR local id → lid
        int[] oldOf;                     // lid → IR local id
        int fo = -1;
        int[] fins = new int[0];
        final List<Call> calls = new ArrayList<>();
        final List<Call> callers = new ArrayList<>();
        final List<long[]> intra = new ArrayList<>();      // src, dst, conf, flags (요약 계산용)
        boolean endpointHandler;

        int gid(int irLocal) {
            int l = newLid[irLocal];
            return l < nIface ? start + l : -1;
        }

        NodeKind kind(int irLocal) {
            return ir.getNodes(irLocal).getKind();
        }
    }

    /** 호출 지점 하나의 대상 하나. */
    record Target(M callee, int conf, List<int[]> argPairs, List<int[]> retPairs) {}

    static final class Call {
        M caller;
        int[] ains;
        int aout;
        final List<Target> targets = new ArrayList<>();
        final List<M> declared = new ArrayList<>();      // 선언 타입의 (추상) 메서드: 호출 그래프에만 기록 (Q3)
        int conf;
    }

    private record Pending(MethodIR ir, int fileId) {}

    private final List<Pending> pending = new ArrayList<>();
    private final List<M> methods = new ArrayList<>();
    private final Map<String, M> bySig = new HashMap<>();
    private final Map<String, List<M>> byClassName = new HashMap<>();
    private final Map<String, ClassIR> classes = new HashMap<>();
    private final Map<String, List<String>> implementors = new HashMap<>();
    private final List<EndpointIR> endpoints = new ArrayList<>();
    private final Map<Integer, String> files = new TreeMap<>();
    private final StringDict.Builder strings = new StringDict.Builder();
    private final Map<String, Integer> globals = new LinkedHashMap<>();
    private final List<int[]> globalAttrs = new ArrayList<>();         // kind, name, fileId, offset
    private final Map<Integer, EdgeList> resident = new LinkedHashMap<>();
    private final Map<Long, Integer> edgeDedup = new HashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private final Map<String, Long> stats = new TreeMap<>();
    private int ifaceTotal;

    public GraphBuilder() {
        for (int k : Kinds.RESIDENT) resident.put(k, new EdgeList(new String[]{"conf", "clause", "flags", "masks", "derive"}));
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    // ───────────────────────────── 입력 ─────────────────────────────

    public void add(FileIR f) {
        files.put(f.getFileId(), f.getPath());
        for (ClassIR c : f.getClassesList()) classes.put(c.getName(), c);
        endpoints.addAll(f.getEndpointsList());
        for (MethodIR mi : f.getMethodsList()) pending.add(new Pending(mi, f.getFileId()));
        stats.merge("errorNodes", (long) f.getErrorNodes(), Long::sum);
        diagnostics.addAll(f.getDiagnosticsList());
    }

    /** 길이 접두 protobuf 스트림(ir/*.pb)에서 FileIR을 읽습니다. */
    public static List<FileIR> readIr(Path dir) throws IOException {
        List<FileIR> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".pb")).sorted().toList()) {
                try (var in = Files.newInputStream(p)) {
                    FileIR f;
                    while ((f = FileIR.parseDelimitedFrom(in)) != null) out.add(f);
                }
            }
        }
        return out;
    }

    public static void writeIr(Path file, List<FileIR> irs) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream o = Files.newOutputStream(file)) {
            for (FileIR f : irs) f.writeDelimitedTo(o);
        }
    }

    // ───────────────────────────── 빌드 ─────────────────────────────

    public Map<String, Object> build(Path out, Options opt) throws IOException {
        long t0 = System.nanoTime();
        Files.createDirectories(out);
        registerMethods();
        assignIds();
        indexHierarchy();
        for (M m : methods) intraEdges(m);
        for (M m : methods) localFlows(m);
        for (M m : methods) linkCalls(m);
        linkEndpoints();
        Summaries sum = new Summaries(methods, ifaceTotal,
                g -> g >= ifaceTotal && g - ifaceTotal < globalAttrs.size() && globalAttrs.get(g - ifaceTotal)[0] == Kinds.FIELD);
        sum.compute();
        int[] summaryCount = {0};
        sum.materialize((src, dst, masks, conf) -> {
            addResident(Kinds.SUMMARY, src, dst, conf, 0, 0, masks, 0);
            summaryCount[0]++;
        });
        stats.put("sccs", (long) sum.sccCount);
        stats.put("recursiveSccs", (long) sum.recursiveSccs);
        stats.put("summaryIterations", (long) sum.iterations);
        int nodeCount = ifaceTotal + globals.size();
        Map<String, Object> meta = new LinkedHashMap<>();
        long resBytes = writeNodes(out, nodeCount);
        writeMethods(out);
        Map<String, Object> edgeCounts = new LinkedHashMap<>();
        Path iface = out.resolve(Layout.IFACE);
        Files.createDirectories(iface);
        for (int k : Kinds.RESIDENT) {                                 // 종류 하나씩 빌드 → 기록 → 해제 (NFR-07)
            EdgeList el = resident.get(k);
            edgeCounts.put(Kinds.EDGE_NAMES[k], el.size);
            if (el.size == 0) continue;
            resBytes += CsrData.build(nodeCount, el, false).write(iface, Kinds.EDGE_NAMES[k] + ".fwd");
            resBytes += CsrData.build(nodeCount, el, true).write(iface, Kinds.EDGE_NAMES[k] + ".rev");
            resident.put(k, new EdgeList(new String[0]));
        }
        writeSummaryTable(iface, sum);
        long[] l2 = writeL2(out);
        edgeCounts.put("DEF_USE", l2[1]);
        edgeCounts.put("CONTROL", l2[2]);
        writeIndices(out);
        Files.createDirectories(out.resolve(Layout.DELTA));
        strings.write(out.resolve(Layout.STRINGS));
        StringBuilder ft = new StringBuilder();
        files.forEach((k, v) -> ft.append(k).append('\t').append(v).append('\n'));
        Files.writeString(out.resolve(Layout.FILES), ft.toString());

        int residentKinds = 0;
        for (var e : edgeCounts.entrySet())
            if (!e.getKey().equals("DEF_USE") && !e.getKey().equals("CONTROL") && ((Number) e.getValue()).longValue() > 0) residentKinds++;
        long residentEdges = 0;
        for (var e : edgeCounts.entrySet())
            if (!e.getKey().equals("DEF_USE") && !e.getKey().equals("CONTROL")) residentEdges += ((Number) e.getValue()).longValue();
        meta.put("schemaVersion", Layout.SCHEMA_VERSION);
        meta.put("nodes", nodeCount);
        meta.put("interfaceNodes", ifaceTotal);
        meta.put("globalNodes", globals.size());
        meta.put("l2Nodes", l2[0]);
        meta.put("methods", methods.size());
        meta.put("files", files.size());
        meta.put("edges", edgeCounts);
        meta.put("residentEdgeKinds", Kinds.RESIDENT.stream().map(k -> Kinds.EDGE_NAMES[k]).toList());
        // NFR-06 메모리 추정식: 노드 17B + 종류×방향 offsets + (tgt 4B + conf 1B)×E×2
        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("nodeAttrBytes", 17L * nodeCount);
        mem.put("offsetsBytes", 2L * 4 * (nodeCount + 1) * residentKinds);
        mem.put("targetsConfBytes", 2L * 5 * residentEdges);
        mem.put("estimateBytes", 17L * nodeCount + 2L * 4 * (nodeCount + 1) * residentKinds + 2L * 5 * residentEdges);
        mem.put("actualResidentBytes", resBytes);
        mem.put("l2Bytes", l2[3]);
        meta.put("memory", mem);
        meta.put("build", Map.of("expansionLimit", opt.expansionLimit(), "summaryEdges", summaryCount[0],
                "millis", (System.nanoTime() - t0) / 1_000_000));
        Map<String, Object> st = new LinkedHashMap<>(stats);
        st.putAll(opt.extraMeta());
        meta.put("stats", st);
        meta.put("diagnostics", diagnostics.size() > 200 ? diagnostics.subList(0, 200) : diagnostics);
        Files.writeString(out.resolve(Layout.META), Json.write(meta));
        return meta;
    }

    // ───────────────────────────── 1. ID 배정 (D-01) ─────────────────────────────

    /**
     * 메서드 표 (설계서 5.2 "메서드 1개 = chunk 1개"): 정규화 시그니처를 키로 한 번만 등록합니다.
     * MyBatis 합성 메서드(XML·@Select)는 같은 클래스·이름의 Mapper 인터페이스 메서드에 본문으로 합칩니다.
     * 등록 순서는 입력 순서(파일 경로 순)로 결정적이며, 호출 관계와 무관합니다.
     */
    private void registerMethods() {
        Map<String, Integer> abstractIdx = new HashMap<>();
        for (int i = 0; i < pending.size(); i++) {
            MethodIR mi = pending.get(i).ir;
            if (!mi.getSynthetic() && mi.getIsAbstract()) abstractIdx.putIfAbsent(mi.getClassName() + "#" + mi.getName(), i);
        }
        List<Pending> list = new ArrayList<>(pending);
        Set<Integer> dropped = new java.util.HashSet<>();
        for (int i = 0; i < pending.size(); i++) {
            MethodIR s = pending.get(i).ir;
            if (!s.getSynthetic() || s.getSql().getNamespace().isEmpty()) continue;
            Integer ai = abstractIdx.get(s.getClassName() + "#" + s.getName());
            if (ai == null) continue;
            Pending a = list.get(ai);
            if (a.ir.getSynthetic()) {                                     // 같은 id의 문장이 둘 이상
                diagnostics.add("합성 메서드 중복: " + s.getSignature());
                dropped.add(i);
                continue;
            }
            list.set(ai, new Pending(mergeSql(a.ir, s), a.fileId));
            dropped.add(i);
            stats.merge("mergedSqlMethods", 1L, Long::sum);
        }
        for (int i = 0; i < list.size(); i++) {
            if (dropped.contains(i)) continue;
            MethodIR mi = list.get(i).ir;
            if (bySig.containsKey(mi.getSignature())) {
                diagnostics.add("중복 시그니처(첫 정의만 사용): " + mi.getSignature());
                stats.merge("duplicateSignatures", 1L, Long::sum);
                continue;
            }
            M m = new M();
            m.idx = methods.size();
            m.ir = mi;
            m.fileId = list.get(i).fileId;
            methods.add(m);
            bySig.put(mi.getSignature(), m);
            byClassName.computeIfAbsent(mi.getClassName() + "#" + mi.getName(), k -> new ArrayList<>()).add(m);
        }
        pending.clear();
    }

    /**
     * Mapper 인터페이스 메서드 a + 합성 SQL 메서드 s → 한 메서드 (설계서 3.4).
     * a의 FORMAL_IN(this, 파라미터)·FORMAL_OUT을 그대로 쓰고, s의 FORMAL_IN(바인드 루트 이름)은 a의 파라미터로 바꿉니다:
     * 파라미터 1개 → 모든 루트, 여러 개 → 이름(@Param·자바 이름)·paramN·argN 일치, 나머지는 HEURISTIC.
     */
    private MethodIR mergeSql(MethodIR a, MethodIR s) {
        int base = a.getNodesCount();
        int aParams0 = a.getHasThis() ? 1 : 0;
        int nParams = a.getFormalInCount() - aParams0;
        List<String> javaNames = a.getParamNamesList();
        Map<Integer, List<int[]>> fiMap = new HashMap<>();              // s local → [(a local, conf)]
        List<String> roots = s.getSql().getParamNamesList();
        for (int j = 0; j < s.getFormalInCount(); j++) {
            List<int[]> to = new ArrayList<>();
            String root = j < roots.size() ? roots.get(j) : "";
            if (nParams == 1) to.add(new int[]{a.getFormalIn(aParams0), s.getFormalInCount() == 1 ? Kinds.EXACT : Kinds.RESOLVED});
            else if (nParams > 1) {
                int idx = javaNames.indexOf(root);
                if (idx < 0 && root.matches("param\\d+")) idx = Integer.parseInt(root.substring(5)) - 1;
                if (idx < 0 && root.matches("arg\\d+")) idx = Integer.parseInt(root.substring(3));
                if (idx >= 0 && idx < nParams) to.add(new int[]{a.getFormalIn(aParams0 + idx), Kinds.EXACT});
                else for (int k = 0; k < nParams; k++) to.add(new int[]{a.getFormalIn(aParams0 + k), Kinds.HEURISTIC});
            }
            fiMap.put(s.getFormalIn(j), to);
        }
        MethodIR.Builder b = a.toBuilder().setSynthetic(true).setIsAbstract(false).setSql(s.getSql())
                .clearExternals().addAllExternals(s.getExternalsList());
        int aFo = a.getFormalOut() - 1, sFo = s.getFormalOut() - 1;
        Map<Integer, Integer> map = new HashMap<>();
        int next = base;
        for (Node n : s.getNodesList()) {
            int id = n.getLocalId();
            if (fiMap.containsKey(id)) continue;
            if (id == sFo && aFo >= 0) {
                map.put(id, aFo);
                continue;
            }
            map.put(id, next);
            b.addNodes(n.toBuilder().setLocalId(next));
            if (id == sFo) b.setFormalOut(next + 1);                      // void 인터페이스: SQL 결과를 FORMAL_OUT으로
            next++;
        }
        for (Edge e : s.getEdgesList()) {
            List<int[]> srcs = e.getSrcExt() > 0 ? List.of(new int[]{0, Kinds.EXACT})
                    : fiMap.containsKey(e.getSrc()) ? fiMap.get(e.getSrc()) : List.of(new int[]{map.get(e.getSrc()), Kinds.EXACT});
            List<int[]> dsts = e.getDstExt() > 0 ? List.of(new int[]{0, Kinds.EXACT})
                    : fiMap.containsKey(e.getDst()) ? fiMap.get(e.getDst()) : List.of(new int[]{map.get(e.getDst()), Kinds.EXACT});
            for (int[] sr : srcs)
                for (int[] dr : dsts) {
                    Edge.Builder eb = e.toBuilder().setConfValue(Math.max(e.getConfValue(), Math.max(sr[1], dr[1])));
                    if (e.getSrcExt() == 0) eb.setSrc(sr[0]);
                    if (e.getDstExt() == 0) eb.setDst(dr[0]);
                    b.addEdges(eb);
                }
        }
        // XML에 parameterType이 없으면 인터페이스 파라미터 타입(DTO)으로 프로퍼티 → 바인드 (FIELD ─LOAD→ BIND)
        if (s.getSql().getParameterType().isEmpty() && nParams == 1 && classes.containsKey(a.getParamTypes(0))) {
            String dto = a.getParamTypes(0);
            for (Node n : s.getNodesList()) {
                if (n.getKind() != NodeKind.BIND) continue;
                String prop = n.getName().replaceAll("^#\\{|}$", "").split("\\.")[0];
                String declaring = declaringClass(dto, prop);
                if (declaring == null) continue;
                b.addExternals(ExternalRef.newBuilder().setField(declaring + "." + prop));
                b.addEdges(Edge.newBuilder().setSrcExt(b.getExternalsCount()).setDst(map.get(n.getLocalId()))
                        .setKind(EdgeKind.LOAD).setConfValue(Kinds.RESOLVED));
            }
        }
        return b.build();
    }

    /**
     * chunk 안 배치 (설계서 5.2, 고정): FORMAL_IN(this, 파라미터 순) → FORMAL_OUT → 호출 지점별 ACTUAL_IN(인자 순)·ACTUAL_OUT
     * → BIND → STORE/LOAD 지점 → 그 밖의 인터페이스 노드(UNKNOWN) → (L2) LOCAL·PREDICATE. 따라서 파라미터 i = chunk 시작 + i.
     */
    private void assignIds() {
        int next = 0;
        for (M m : methods) {
            MethodIR ir = m.ir;
            List<Node> nodes = ir.getNodesList();
            m.n = nodes.size();
            m.newLid = new int[m.n];
            m.oldOf = new int[m.n];
            java.util.Arrays.fill(m.newLid, -1);
            int[] l = {0};
            java.util.function.IntConsumer place = id -> {
                if (id < 0 || id >= m.n || m.newLid[id] >= 0) return;
                m.newLid[id] = l[0];
                m.oldOf[l[0]++] = id;
            };
            for (int fi : ir.getFormalInList()) place.accept(fi);
            if (ir.getFormalOut() > 0) place.accept(ir.getFormalOut() - 1);
            for (CallSite cs : ir.getCallsList()) {
                for (int a : cs.getActualInList()) place.accept(a);
                place.accept(cs.getActualOut());
            }
            for (NodeKind k : List.of(NodeKind.BIND, NodeKind.FIELD_STORE, NodeKind.FIELD_LOAD))
                for (Node nd : nodes) if (nd.getKind() == k) place.accept(nd.getLocalId());
            for (Node nd : nodes) if (Kinds.isInterface(nd.getKindValue())) place.accept(nd.getLocalId());
            m.nIface = l[0];
            for (Node nd : nodes) place.accept(nd.getLocalId());
            m.start = next;
            next += m.nIface;
            if (ir.getFormalOut() > 0) m.fo = m.gid(ir.getFormalOut() - 1);
            m.fins = ir.getFormalInList().stream().mapToInt(m::gid).toArray();
            for (int i = 0; i < m.fins.length; i++)
                if (m.fins[i] != m.start + i) throw new IllegalStateException(ir.getSignature() + ": 파라미터 배치 규칙 위반");
        }
        ifaceTotal = next;
    }

    private int global(String key, int kind, String name, int fileId, int offset) {
        Integer g = globals.get(key);
        if (g != null) return g;
        g = ifaceTotal + globals.size();
        globals.put(key, g);
        globalAttrs.add(new int[]{kind, strings.intern(name), fileId, offset});
        return g;
    }

    private int extGid(MethodIR ir, int ext) {
        ExternalRef r = ir.getExternals(ext - 1);
        return switch (r.getTargetCase()) {
            case FIELD -> global("F:" + r.getField(), Kinds.FIELD, r.getField(), -1, 0);
            case COLUMN -> column(r.getColumn());
            case ENDPOINT -> global("E:" + r.getEndpoint(), Kinds.ENDPOINT, r.getEndpoint(), -1, 0);
            default -> -1;
        };
    }

    /** FIELD 참조의 클래스가 프로젝트에 있는데 그 필드(상위 클래스 포함)가 없으면 true. */
    private boolean missingField(MethodIR ir, int ext) {
        ExternalRef r = ir.getExternals(ext - 1);
        if (r.getTargetCase() != ExternalRef.TargetCase.FIELD) return false;
        String f = r.getField();
        int k = f.lastIndexOf('.');
        if (k < 0 || !classes.containsKey(f.substring(0, k))) return false;
        return declaringClass(f.substring(0, k), f.substring(k + 1)) == null;
    }

    private int column(ColumnKey c) {
        String ds = c.getDataSource().isEmpty() ? "DEFAULT" : c.getDataSource();
        String name = (ds.equals("DEFAULT") ? "" : "[" + ds + "] ") + (c.getSchema().isEmpty() ? "" : c.getSchema() + ".")
                + c.getTable() + "." + c.getColumn();
        return global("C:" + ds + ":" + c.getSchema() + "." + c.getTable() + "." + c.getColumn(), Kinds.COLUMN, name, -1, 0);
    }

    private void indexHierarchy() {
        for (ClassIR c : classes.values()) {
            Set<String> seen = new LinkedHashSet<>();
            List<String> work = new ArrayList<>();
            if (!c.getSuperClass().isEmpty()) work.add(c.getSuperClass());
            work.addAll(c.getInterfacesList());
            while (!work.isEmpty()) {
                String s = work.removeLast();
                if (!seen.add(s)) continue;
                implementors.computeIfAbsent(s, k -> new ArrayList<>()).add(c.getName());
                ClassIR sc = classes.get(s);
                if (sc != null) {
                    if (!sc.getSuperClass().isEmpty()) work.add(sc.getSuperClass());
                    work.addAll(sc.getInterfacesList());
                }
            }
        }
    }

    // ───────────────────────────── 상주 간선 ─────────────────────────────

    private void addResident(int kind, int src, int dst, int conf, int clause, int flags, int masks, int derive) {
        if (src < 0 || dst < 0) return;
        long key = ((((long) kind * 31 + flags) * 7 + clause) * 1_000_003L + src) * 1_000_003L + dst;
        EdgeList el = resident.get(kind);
        Integer prev = edgeDedup.get(key);
        if (prev != null) {
            byte[] c = el.bytes("conf");
            if (conf < c[prev]) c[prev] = (byte) conf;
            el.bytes("masks")[prev] |= (byte) masks;
            return;
        }
        edgeDedup.put(key, el.add(src, dst, conf, clause, flags, masks, derive));
    }

    /** IR의 상주 간선(STORE/LOAD/BIND_TO/MAPS_TO/LOCAL_FLOW/COL_DERIVES)을 gid로 옮깁니다. */
    private void intraEdges(M m) {
        MethodIR ir = m.ir;
        for (Edge e : ir.getEdgesList()) {
            int k = e.getKindValue();
            if (k == Kinds.DEF_USE || k == Kinds.CONTROL) continue;
            if (e.getSrcExt() > 0 && missingField(ir, e.getSrcExt()) || e.getDstExt() > 0 && missingField(ir, e.getDstExt())) {
                stats.merge("droppedUnknownFieldEdges", 1L, Long::sum);  // resultType/parameterType 프로퍼티가 클래스에 없음
                continue;
            }
            int s = e.getSrcExt() > 0 ? extGid(ir, e.getSrcExt()) : m.gid(e.getSrc());
            int d = e.getDstExt() > 0 ? extGid(ir, e.getDstExt()) : m.gid(e.getDst());
            if (s < 0 || d < 0) {
                diagnostics.add(ir.getSignature() + ": 상주 간선 " + e.getKind() + "이 지역 노드를 가리킴");
                continue;
            }
            int flags = e.getImplicit() ? Kinds.F_IMPLICIT : 0;
            addResident(k, s, d, e.getConfValue(), e.getClauseValue(), flags, 0, e.getDeriveValue());
            if (k == Kinds.LOCAL_FLOW || k == Kinds.BIND_TO || k == Kinds.STORE || k == Kinds.LOAD)
                m.intra.add(new long[]{s, d, e.getConfValue(), flags});
        }
    }

    // ───────────────────────────── 2. LOCAL_FLOW / CONTROL_FLOW ─────────────────────────────

    private static boolean isSource(NodeKind k) {
        return k == NodeKind.FORMAL_IN || k == NodeKind.ACTUAL_OUT || k == NodeKind.FIELD_LOAD;
    }

    private static boolean isSink(NodeKind k) {
        return k == NodeKind.ACTUAL_IN || k == NodeKind.FORMAL_OUT || k == NodeKind.FIELD_STORE;
    }

    /**
     * 인터페이스 노드 u에서 (DEF_USE ∪ CONTROL)* 경로로 인터페이스 노드 w에 도달하면:
     * DEF_USE만으로 도달 → LOCAL_FLOW, CONTROL을 하나 이상 거쳐야만 도달 → CONTROL_FLOW (설계서 3.2.4).
     * 신뢰도는 경로상 가장 약한 간선, 여러 경로 중 가장 확정적인 것 (0-1-2 버킷 BFS).
     */
    private void localFlows(M m) {
        MethodIR ir = m.ir;
        int n = m.n;
        List<List<int[]>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        boolean any = false;
        for (Edge e : ir.getEdgesList()) {
            if (e.getKind() == EdgeKind.DEF_USE) adj.get(e.getSrc()).add(new int[]{e.getDst(), e.getConfValue(), 0});
            else if (e.getKind() == EdgeKind.CONTROL) adj.get(e.getSrc()).add(new int[]{e.getDst(), e.getConfValue(), 1});
            else continue;
            any = true;
        }
        if (!any) return;
        for (Node src : ir.getNodesList()) {
            if (!isSource(src.getKind())) continue;
            int s = src.getLocalId();
            int[] best = new int[n * 2];
            java.util.Arrays.fill(best, 3);
            List<ArrayList<int[]>> buckets = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            best[s * 2] = 0;
            buckets.getFirst().add(new int[]{s, 0});
            for (int c = 0; c < 3; c++) {
                ArrayList<int[]> b = buckets.get(c);
                for (int i = 0; i < b.size(); i++) {
                    int v = b.get(i)[0], ctl = b.get(i)[1];
                    if (best[v * 2 + ctl] < c) continue;
                    for (int[] e : adj.get(v)) {
                        int w = e[0], nc = Math.max(c, e[1]), nctl = ctl | e[2];
                        if (best[w * 2 + nctl] <= nc) continue;
                        best[w * 2 + nctl] = nc;
                        buckets.get(nc).add(new int[]{w, nctl});
                    }
                }
            }
            for (Node dst : ir.getNodesList()) {
                int w = dst.getLocalId();
                if (w == s || !isSink(dst.getKind())) continue;
                int g1 = m.gid(s), g2 = m.gid(w);
                if (best[w * 2] < 3) {
                    addResident(Kinds.LOCAL_FLOW, g1, g2, best[w * 2], 0, 0, 0, 0);
                    m.intra.add(new long[]{g1, g2, best[w * 2], 0});
                } else if (best[w * 2 + 1] < 3) {
                    addResident(Kinds.CONTROL_FLOW, g1, g2, best[w * 2 + 1], 0, Kinds.F_CONTROL, 0, 0);
                    m.intra.add(new long[]{g1, g2, best[w * 2 + 1], Kinds.F_CONTROL});
                }
            }
        }
    }

    // ───────────────────────────── 3. 링크 ─────────────────────────────

    private void linkCalls(M m) {
        MethodIR ir = m.ir;
        for (CallSite cs : ir.getCallsList()) {
            Call c = new Call();
            c.caller = m;
            c.ains = cs.getActualInList().stream().mapToInt(m::gid).toArray();
            c.aout = m.gid(cs.getActualOut());
            c.conf = cs.getConfValue();
            for (var t : resolve(m, cs).entrySet()) {
                M callee = t.getKey();
                int conf = Math.max(c.conf, t.getValue());
                List<int[]> args = argPairs(c, cs, callee, conf);
                List<int[]> rets = new ArrayList<>();
                if (callee.fo >= 0 && c.aout >= 0) rets.add(new int[]{callee.fo, c.aout, conf});
                for (int[] a : args) addResident(Kinds.ARG_IN, a[0], a[1], a[2], 0, 0, 0, 0);
                for (int[] r : rets) addResident(Kinds.RET_OUT, r[0], r[1], r[2], 0, 0, 0, 0);
                c.targets.add(new Target(callee, conf, args, rets));
                callee.callers.add(c);
            }
            for (String sig : cs.getCandidateTargetsList()) {
                M d = bySig.get(sig);
                if (d != null && c.targets.stream().noneMatch(t -> t.callee() == d)) c.declared.add(d);
            }
            if (c.targets.isEmpty() || c.targets.stream().allMatch(t -> t.callee.ir.getIsAbstract() && !t.callee.ir.getSynthetic())) {
                // 대상 미상 또는 구현 없는 추상 메서드: 인자 → 결과 HEURISTIC (설계서 3.1 외부 호출 기본 흐름)
                for (int a : c.ains) addResident(Kinds.SUMMARY, a, c.aout, Kinds.HEURISTIC, 0, 0, 1, 0);
                stats.merge(c.targets.isEmpty() ? "unresolvedCalls" : "abstractOnlyCalls", 1L, Long::sum);
            }
            m.calls.add(c);
            stats.merge("callSites", 1L, Long::sum);
        }
    }

    /** 호출 대상 → 신뢰도. 설계서 3.1 호출 대상 해석 + 3.3 DI + 3.4 Mapper ↔ XML. */
    private Map<M, Integer> resolve(M caller, CallSite cs) {
        Map<M, Integer> out = new LinkedHashMap<>();
        String name = cs.getMethodName();
        int arity = cs.getActualInCount();
        if (cs.getCallKind() == CallKind.DIRECT_TARGET || cs.getCallKind() == CallKind.STATIC
                || cs.getCallKind() == CallKind.CONSTRUCTOR || cs.getCallKind() == CallKind.SUPER
                || (cs.getCallKind() == CallKind.THIS)) {
            for (String sig : cs.getCandidateTargetsList()) {
                M t = bySig.get(sig);
                if (t != null) out.put(t, cs.getConfValue());
            }
            if (cs.getCallKind() != CallKind.THIS || !out.isEmpty()) return out;
        }
        String rtype = cs.getReceiverType();
        if (rtype.isEmpty()) {                                           // 이름만 일치 (HEURISTIC)
            for (String sig : cs.getCandidateTargetsList()) {
                M t = bySig.get(sig);
                if (t != null) out.put(t, Kinds.HEURISTIC);
            }
            return out;
        }
        ClassIR rc = classes.get(rtype);
        // MyBatis: 인터페이스 namespace.id ↔ 합성 메서드
        for (M s : byClassName.getOrDefault(rtype + "#" + name, List.of()))
            if (s.ir.getSynthetic()) out.put(s, Kinds.RESOLVED);
        if (!out.isEmpty()) return out;
        boolean iface = rc != null && (rc.getKind().equals("interface") || isAbstractClass(rc));
        if (iface) {
            List<String> impls = new ArrayList<>(implementors.getOrDefault(rtype, List.of()));
            FieldDecl fd = receiverField(caller, cs);
            List<String> beans = impls.stream().filter(i -> classes.containsKey(i) && classes.get(i).getBean()).toList();
            if (fd != null && fd.getInjected() && !beans.isEmpty()) {
                impls = new ArrayList<>(beans);
                if (!fd.getQualifier().isEmpty()) {
                    List<String> q = impls.stream().filter(i -> classes.get(i).getBeanName().equals(fd.getQualifier())).toList();
                    if (!q.isEmpty()) impls = new ArrayList<>(q);
                }
            }
            List<M> found = new ArrayList<>();
            for (String impl : impls) {
                M t = findMethod(impl, name, arity);
                if (t != null && !t.ir.getIsAbstract() && !found.contains(t)) found.add(t);
            }
            for (M t : found) out.put(t, found.size() == 1 ? Kinds.RESOLVED : Kinds.HEURISTIC);
            if (!found.isEmpty()) return out;
        }
        for (String sig : cs.getCandidateTargetsList()) {
            M t = bySig.get(sig);
            if (t != null) out.put(t, cs.getConfValue());
        }
        if (out.isEmpty()) {
            M t = findMethod(rtype, name, arity);
            if (t != null) out.put(t, Kinds.RESOLVED);
        }
        return out;
    }

    private boolean isAbstractClass(ClassIR c) {
        return byClassName.keySet().stream().anyMatch(k -> k.startsWith(c.getName() + "#"))
                && methods.stream().anyMatch(m -> m.ir.getClassName().equals(c.getName()) && m.ir.getIsAbstract());
    }

    private FieldDecl receiverField(M caller, CallSite cs) {
        if (cs.getReceiverField() == 0) return null;
        String f = caller.ir.getExternals(cs.getReceiverField() - 1).getField();
        int k = f.lastIndexOf('.');
        ClassIR c = classes.get(f.substring(0, k));
        if (c == null) return null;
        for (FieldDecl fd : c.getFieldsList()) if (fd.getName().equals(f.substring(k + 1))) return fd;
        return null;
    }

    /** 클래스와 상위 클래스에서 이름·인자 수가 맞는 메서드. */
    private M findMethod(String cls, String name, int arity) {
        for (String c = cls; c != null && !c.isEmpty(); ) {
            for (M t : byClassName.getOrDefault(c + "#" + name, List.of()))
                if (t.fins.length == arity || t.ir.getSynthetic()) return t;
            ClassIR ci = classes.get(c);
            c = ci == null ? null : ci.getSuperClass();
        }
        return null;
    }

    /**
     * ARG_IN 짝 (actual_in gid, formal_in gid, conf). 위치로만 짝짓습니다 (설계서 5.2 링크 방식):
     * 수신 객체 ACTUAL_IN[0] ↔ this(FORMAL_IN 0), 인자 i ↔ 파라미터 i. 한쪽에만 수신 객체/this가 있으면 그 자리는 건너뜁니다.
     * 합성 SQL 메서드의 파라미터 ↔ 바인드 루트 이름 대응은 메서드 병합 단계(mergeSql)에서 이미 끝났습니다.
     */
    private List<int[]> argPairs(Call c, CallSite cs, M callee, int conf) {
        List<int[]> out = new ArrayList<>();
        int a0 = cs.getHasReceiver() ? 1 : 0, f0 = callee.ir.getHasThis() ? 1 : 0;
        if (a0 == 1 && f0 == 1 && c.ains.length > 0 && callee.fins.length > 0) out.add(new int[]{c.ains[0], callee.fins[0], conf});
        int nArgs = c.ains.length - a0, nParams = callee.fins.length - f0;
        for (int i = 0; i < Math.min(nArgs, nParams); i++) out.add(new int[]{c.ains[a0 + i], callee.fins[f0 + i], conf});
        if (nParams > 0 && nArgs > nParams)                                // 가변 인자
            for (int i = nParams; i < nArgs; i++) out.add(new int[]{c.ains[a0 + i], callee.fins[callee.fins.length - 1], conf});
        return out;
    }
    private String declaringClass(String cls, String field) {
        for (String c = cls; c != null && !c.isEmpty(); ) {
            ClassIR ci = classes.get(c);
            if (ci == null) return null;
            for (FieldDecl fd : ci.getFieldsList()) if (fd.getName().equals(field)) return c;
            c = ci.getSuperClass();
        }
        return null;
    }

    /** ENDPOINT ─ARG_IN→ 핸들러 FORMAL_IN, 핸들러 FORMAL_OUT ─RET_OUT→ ENDPOINT. */
    private void linkEndpoints() {
        for (EndpointIR e : endpoints) {
            M h = bySig.get(e.getMethodSignature());
            if (h == null) continue;
            h.endpointHandler = true;
            String key = e.getHttpMethod() + " " + e.getPath();
            int g = global("E:" + key, Kinds.ENDPOINT, key, h.fileId, e.getSpan().getStart());
            for (EndpointParam p : e.getParamsList())
                if (paramGid(h, p.getFormalIndex()) >= 0) addResident(Kinds.ARG_IN, g, paramGid(h, p.getFormalIndex()), Kinds.EXACT, 0, 0, 0, 0);
            if (h.fo >= 0) addResident(Kinds.RET_OUT, h.fo, g, Kinds.EXACT, 0, 0, 0, 0);
        }
    }

    // ───────────────────────────── 5. 기록 ─────────────────────────────

    private long writeNodes(Path out, int nodeCount) {
        byte[] kind = new byte[nodeCount];
        int[] owner = new int[nodeCount], name = new int[nodeCount], chunk = new int[nodeCount];
        long[] span = new long[nodeCount];
        for (M m : methods) {
            for (int l = 0; l < m.nIface; l++) {
                Node nd = m.ir.getNodes(m.oldOf[l]);
                int g = m.start + l;
                kind[g] = (byte) nd.getKindValue();
                owner[g] = m.idx;
                name[g] = strings.intern(nd.getName());
                span[g] = spanOf(m, nd);
                chunk[g] = m.start;
            }
        }
        int i = 0;
        for (int[] a : globalAttrs) {
            int g = ifaceTotal + i++;
            kind[g] = (byte) a[0];
            owner[g] = -1;
            name[g] = a[1];
            span[g] = a[2] < 0 ? -1L << 32 : ((long) a[2] << 32) | a[3];
            chunk[g] = -1;
        }
        Path nd = out.resolve(Layout.NODES);
        OffHeap.writeBytes(nd.resolve("kind.u8"), kind);
        OffHeap.writeInts(nd.resolve("owner.i32"), owner);
        OffHeap.writeInts(nd.resolve("name.i32"), name);
        OffHeap.writeLongs(nd.resolve("span.i64"), span);
        OffHeap.writeInts(nd.resolve("chunk.i32"), chunk);
        return 17L * nodeCount;
    }

    /** 설계서 5.2: span.i64 = fileId << 32 | 바이트 오프셋. 위치 없는 노드(줄 번호 0)는 메서드 선언 위치. */
    private static long spanOf(M m, Node nd) {
        boolean has = nd.hasSpan() && nd.getSpan().getLine() > 0;
        int fid = has ? nd.getSpan().getFileId() : m.ir.getSpan().getLine() > 0 ? m.ir.getSpan().getFileId() : m.fileId;
        int off = has ? nd.getSpan().getStart() : m.ir.getSpan().getStart();
        return ((long) fid << 32) | (off & 0xffffffffL);
    }

    /** 자바 파라미터 순서 i → FORMAL_IN gid (인스턴스 메서드는 this가 0번이라 한 칸 밀림). */
    private static int paramGid(M h, int javaIndex) {
        int i = javaIndex + (h.ir.getHasThis() ? 1 : 0);
        return i < h.fins.length ? h.fins[i] : -1;
    }

    private void writeMethods(Path out) {
        int n = methods.size();
        int[] start = new int[n], end = new int[n], sig = new int[n], fo = new int[n], file = new int[n];
        byte[] flags = new byte[n];
        EdgeList fins = new EdgeList(new String[0]);
        for (M m : methods) {
            start[m.idx] = m.start;
            end[m.idx] = m.start + m.nIface;
            sig[m.idx] = strings.intern(m.ir.getSignature());
            fo[m.idx] = m.fo;
            file[m.idx] = m.fileId;
            flags[m.idx] = (byte) ((m.ir.getSynthetic() ? 1 : 0) | (m.ir.getIsAbstract() ? 2 : 0) | (m.endpointHandler ? 4 : 0));
            for (int f : m.fins) fins.add(m.idx, f);
        }
        Path md = out.resolve(Layout.METHODS);
        OffHeap.writeInts(md.resolve("start.i32"), start);
        OffHeap.writeInts(md.resolve("end.i32"), end);
        OffHeap.writeInts(md.resolve("sig.i32"), sig);
        OffHeap.writeInts(md.resolve("fo.i32"), fo);
        OffHeap.writeInts(md.resolve("file.i32"), file);
        OffHeap.writeBytes(md.resolve("flags.u8"), flags);
        CsrData.build(n, fins, false).write(md, "fin");
    }

    private void writeSummaryTable(Path iface, Summaries sum) {
        List<int[]> rows = sum.rows();
        OffHeap.write(iface.resolve(Layout.SUMMARY_TBL), 4L * (1 + 4L * rows.size()), seg -> {
            seg.setAtIndex(OffHeap.INT, 0, rows.size());
            for (int i = 0; i < rows.size(); i++)
                for (int j = 0; j < 4; j++) seg.setAtIndex(OffHeap.INT, 1 + 4L * i + j, rows.get(i)[j]);
        });
    }

    /** l2/chunks.idx + chunks.bin. 반환: [L2 노드 수, DEF_USE 수, CONTROL 수, 바이트]. */
    private long[] writeL2(Path out) throws IOException {
        Path l2 = out.resolve(Layout.L2);
        Files.createDirectories(l2);
        long[] idx = new long[methods.size() * 2];
        long pos = 0, l2Nodes = 0, du = 0, ct = 0;
        try (OutputStream o = Files.newOutputStream(l2.resolve(Layout.CHUNKS_BIN))) {
            for (M m : methods) {
                long[] span = new long[m.n];
                int[] kind = new int[m.n], name = new int[m.n];
                for (int l = 0; l < m.n; l++) {
                    Node nd = m.ir.getNodes(m.oldOf[l]);
                    span[l] = spanOf(m, nd);
                    kind[l] = nd.getKindValue();
                    name[l] = strings.intern(nd.getName());
                }
                EdgeList d = new EdgeList(new String[]{"conf"});
                EdgeList c = new EdgeList(new String[]{"conf", "label"}, "caseVal");
                for (Edge e : m.ir.getEdgesList()) {
                    if (e.getKind() == EdgeKind.DEF_USE) d.add(m.newLid[e.getSrc()], m.newLid[e.getDst()], e.getConfValue());
                    else if (e.getKind() == EdgeKind.CONTROL)
                        c.add(m.newLid[e.getSrc()], m.newLid[e.getDst()], new int[]{e.getConfValue(), e.getLabelValue()},
                                e.getCaseValue().isEmpty() ? -1 : strings.intern(e.getCaseValue()));
                }
                byte[] bytes = L2Chunk.encode(m.n, m.nIface, span, kind, name, d, c);
                o.write(bytes);
                idx[2 * m.idx] = pos;
                idx[2 * m.idx + 1] = bytes.length;
                pos += bytes.length;
                l2Nodes += m.n - m.nIface;
                du += d.size;
                ct += c.size;
            }
        }
        OffHeap.writeLongs(l2.resolve(Layout.CHUNKS_IDX), idx);
        return new long[]{l2Nodes, du, ct, pos};
    }

    private void writeIndices(Path out) {
        Path idx = out.resolve(Layout.IDX);
        SortedIndex.Builder col = new SortedIndex.Builder();
        SortedIndex.Builder ep = new SortedIndex.Builder();
        for (var e : globals.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("C:")) {
                String rest = k.substring(2);                                   // DS:SCHEMA.TABLE.COL
                String ds = rest.substring(0, rest.indexOf(':'));
                String path = rest.substring(rest.indexOf(':') + 1);            // SCHEMA.TABLE.COL (스키마 없으면 .TABLE.COL)
                String[] p = path.split("\\.", -1);
                String table = p[p.length - 2], column = p[p.length - 1], schema = p.length > 2 ? p[p.length - 3] : "";
                col.put(table + "." + column, e.getValue());
                if (!schema.isEmpty()) col.put(schema + "." + table + "." + column, e.getValue());
                col.put(ds + ":" + (schema.isEmpty() ? "" : schema + ".") + table + "." + column, e.getValue());
            } else if (k.startsWith("E:")) {
                String key = k.substring(2);
                ep.put(key, e.getValue());
                ep.put("* " + key.substring(key.indexOf(' ') + 1), e.getValue());
            }
        }
        col.write(idx.resolve(Layout.COLUMN_IDX));
        ep.write(idx.resolve(Layout.ENDPOINT_IDX));
        SortedIndex.Builder epp = new SortedIndex.Builder();
        for (EndpointIR e : endpoints) {
            M h = bySig.get(e.getMethodSignature());
            if (h == null) continue;
            for (EndpointParam p : e.getParamsList()) {
                int fi = paramGid(h, p.getFormalIndex());
                if (fi < 0) continue;
                epp.put(e.getHttpMethod() + " " + e.getPath() + "#" + p.getName(), fi);
                epp.put("* " + e.getPath() + "#" + p.getName(), fi);
            }
        }
        epp.write(idx.resolve(Layout.ENDPOINT_PARAM_IDX));
        SortedIndex.Builder mi = new SortedIndex.Builder();
        for (M m : methods) {
            String sig = m.ir.getSignature();
            mi.put(sig, m.idx);
            String cls = m.ir.getClassName();
            mi.put(cls + "#" + m.ir.getName(), m.idx);
            mi.put(cls.substring(cls.lastIndexOf('.') + 1) + "#" + m.ir.getName(), m.idx);
        }
        mi.write(idx.resolve(Layout.METHOD_IDX));
        // 호출 그래프 (callee → caller, site = 호출 결과 gid) : Q3
        EdgeList call = new EdgeList(new String[]{"conf"}, "site");
        for (M m : methods)
            for (Call c : m.calls) {
                for (Target t : c.targets) call.add(m.idx, t.callee().idx, new int[]{t.conf()}, c.aout);
                for (M d : c.declared) call.add(m.idx, d.idx, new int[]{c.conf}, c.aout);
            }
        CsrData.build(methods.size(), call, false).write(idx, Layout.CALL + ".fwd");
        CsrData.build(methods.size(), call, true).write(idx, Layout.CALL + ".rev");
    }

    public static void rethrow(IOException e) {
        throw new UncheckedIOException(e);
    }
}
