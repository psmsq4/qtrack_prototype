package com.ids.qtrack.next.query;

import com.ids.qtrack.next.query.QueryResult.GEdge;
import com.ids.qtrack.next.query.QueryResult.GNode;
import com.ids.qtrack.next.store.Csr;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.L2Chunk;
import com.ids.qtrack.next.store.Snapshot;
import com.ids.qtrack.next.store.SourceLines;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 질의의 전체 흐름 그래프 (CLI {@code --graph}).
 * <ul>
 *   <li>1: 2단계 탐색이 실제로 지난 상주 간선 전부 (최단 경로 하나가 아니라 모든 갈래)</li>
 *   <li>2: 1을 펼친 그래프 —
 *     LOCAL_FLOW/CONTROL_FLOW → 해당 메서드 L2 chunk의 DEF_USE(+CONTROL) 부분 그래프 (LOCAL·PREDICATE 포함),
 *     SUMMARY → ARG_IN → 호출된 메서드 안의 흐름 → RET_OUT (필드 부수 효과면 STORE/LOAD). 안쪽의 LOCAL_FLOW·SUMMARY도
 *     같은 방식으로 다시 펼치며, 재귀 호출은 같은 (메서드, 입구, 출구)를 두 번 펼치지 않습니다.</li>
 * </ul>
 * 펼칠 때도 질의와 같은 신뢰도·플래그(--implicit, --control) 조건을 씁니다.
 */
final class FlowGraph {
    private static final int MAX_DEPTH = 32;

    private final QueryEngine qe;
    private final Snapshot g;
    private final SourceLines lines;
    private final QueryEngine.Options o;
    private QueryResult.FlowGraph out;
    private final Set<String> edgeKeys = new HashSet<>();
    private final java.util.Map<String, Set<Integer>> expanded = new java.util.HashMap<>();

    /** 내부 간선. a, b: 노드 키. kind: 간선 종류, label/caseVal: CONTROL. */
    private record E(String a, String b, int kind, int conf, int flags, int label, int caseVal, String via, int depth) {}

    FlowGraph(QueryEngine qe, Snapshot g, SourceLines lines, QueryEngine.Options o) {
        this.qe = qe;
        this.g = g;
        this.lines = lines;
        this.o = o;
    }

    QueryResult.FlowGraph build(List<QueryEngine.Slice> slices) {
        out = new QueryResult.FlowGraph(o.graph());
        Set<Integer> starts = new LinkedHashSet<>();
        for (QueryEngine.Slice s : slices) starts.addAll(s.starts);
        for (int st : starts) gnode(st, true);
        Deque<E> work = new ArrayDeque<>();
        for (QueryEngine.Slice s : slices) {
            for (int v : s.order) gnode(v, false);
            for (int[] e : s.edges) work.add(new E(key(e[0]), key(e[1]), e[2], e[3], e[4], -1, -1, "", 0));
        }
        while (!work.isEmpty()) {
            E e = work.poll();
            if (o.graph() >= 2 && expand(e, work)) continue;
            emit(e);
        }
        return out;
    }

    // ───────────────────────────── 노드 ─────────────────────────────

    private static String key(int gid) {
        return "g" + gid;
    }

    /** chunk 안 lid → 노드 키 (인터페이스 lid는 상주 gid 키로 통일). */
    private String lkey(int method, int lid) {
        L2Chunk c = g.chunk(method);
        return lid < c.iface ? key(g.methodStart(method) + lid) : "l" + method + ":" + lid;
    }

    private void gnode(int gid, boolean start) {
        String k = key(gid);
        GNode prev = out.nodes.get(k);
        if (prev != null && (prev.start() || !start)) return;
        int owner = g.owner(gid);
        long sp = g.span(gid);
        String cond = owner >= 0 && g.chunkStart(gid) >= 0 ? qe.conditions(owner, gid - g.chunkStart(gid)) : "";
        out.nodes.put(k, new GNode(k, gid, owner >= 0 ? g.methodSig(owner) : "", owner >= 0 ? gid - g.chunkStart(gid) : -1,
                Kinds.NODE_NAMES[g.kind(gid)], g.name(gid), lines.file(sp), lines.line(sp), cond, start));
    }

    private void lnode(int method, int lid) {
        L2Chunk c = g.chunk(method);
        if (lid < c.iface) {
            gnode(g.methodStart(method) + lid, false);
            return;
        }
        String k = lkey(method, lid);
        if (out.nodes.containsKey(k)) return;
        long sp = c.span(lid);
        out.nodes.put(k, new GNode(k, -1, g.methodSig(method), lid, Kinds.NODE_NAMES[c.kind(lid)], g.strings.get(c.name(lid)),
                lines.file(sp), lines.line(sp), qe.conditions(method, lid), false));
    }

    private void emit(E e) {
        String k = e.a + ">" + e.b + ":" + e.kind + ":" + e.label + ":" + e.caseVal;
        if (!edgeKeys.add(k)) return;
        String label = e.label < 0 ? "" : Kinds.LABEL_NAMES[e.label] + (e.caseVal >= 0 ? " " + g.strings.get(e.caseVal) : "");
        out.edges.add(new GEdge(e.a, e.b, Kinds.EDGE_NAMES[e.kind], Kinds.CONF_NAMES[Math.min(2, e.conf)], label,
                (e.flags & Kinds.F_IMPLICIT) != 0, (e.flags & Kinds.F_CONTROL) != 0 || e.kind == Kinds.CONTROL, e.via));
    }

    private static int gid(String key) {
        return key.startsWith("g") ? Integer.parseInt(key.substring(1)) : -1;
    }

    // ───────────────────────────── 펼치기 (--graph 2) ─────────────────────────────

    private boolean expand(E e, Deque<E> work) {
        if (e.depth > MAX_DEPTH) return false;
        int a = gid(e.a), b = gid(e.b);
        if (a < 0 || b < 0) return false;
        if (e.kind == Kinds.LOCAL_FLOW || e.kind == Kinds.CONTROL_FLOW) return expandLocal(e, a, b);
        if (e.kind == Kinds.SUMMARY) return expandSummary(e, a, b, work);
        return false;
    }

    /** 같은 메서드 안 a → b 를 L2 DEF_USE(+CONTROL) 부분 그래프로: a에서 갈 수 있고 b로 갈 수 있는 노드만. */
    private boolean expandLocal(E e, int a, int b) {
        int m = g.owner(a);
        if (m < 0 || m != g.owner(b)) return false;
        L2Chunk c = g.chunk(m);
        if (c.eDefUse == 0 && c.eControl == 0) return false;
        int la = a - g.methodStart(m), lb = b - g.methodStart(m);
        boolean ctl = e.kind == Kinds.CONTROL_FLOW;
        List<Csr> fwd = ctl ? List.of(c.defUseFwd, c.controlFwd) : List.of(c.defUseFwd);
        List<Csr> rev = ctl ? List.of(c.defUseRev, c.controlRev) : List.of(c.defUseRev);
        boolean[] from = reach(c.nodes, la, fwd), to = reach(c.nodes, lb, rev);
        if (!from[lb]) return false;
        String via = Kinds.EDGE_NAMES[e.kind];
        for (int v = 0; v < c.nodes; v++) {
            if (!from[v] || !to[v]) continue;
            for (int k = 0; k < fwd.size(); k++) {
                Csr cs = fwd.get(k);
                for (int i = cs.begin(v); i < cs.end(v); i++) {
                    int w = cs.target(i);
                    if (!from[w] || !to[w]) continue;
                    lnode(m, v);
                    lnode(m, w);
                    boolean isCtl = k == 1;
                    emit(new E(lkey(m, v), lkey(m, w), isCtl ? Kinds.CONTROL : Kinds.DEF_USE, cs.conf(i), isCtl ? Kinds.F_CONTROL : 0,
                            isCtl ? cs.label(i) : -1, isCtl ? cs.caseVal(i) : -1, via, e.depth));
                }
            }
        }
        return true;
    }

    private static boolean[] reach(int n, int start, List<Csr> csrs) {
        boolean[] seen = new boolean[n];
        Deque<Integer> q = new ArrayDeque<>(List.of(start));
        seen[start] = true;
        while (!q.isEmpty()) {
            int v = q.poll();
            for (Csr c : csrs)
                for (int i = c.begin(v); i < c.end(v); i++) {
                    int w = c.target(i);
                    if (!seen[w]) {
                        seen[w] = true;
                        q.add(w);
                    }
                }
        }
        return seen;
    }

    /**
     * SUMMARY a → b (인자 → 결과, 인자 → 필드, 필드 → 결과)를 호출된 메서드 안의 흐름으로.
     * 입구: a가 ACTUAL_IN이면 ARG_IN으로 닿는 FORMAL_IN, a가 FIELD면 그 필드. 출구: b가 ACTUAL_OUT이면 RET_OUT 앞의 FORMAL_OUT, FIELD면 그 필드.
     */
    private boolean expandSummary(E e, int a, int b, Deque<E> work) {
        boolean aField = g.kind(a) == Kinds.FIELD, bField = g.kind(b) == Kinds.FIELD;
        Set<Integer> methods = new TreeSet<>();
        List<int[]> argIn = new ArrayList<>(), retOut = new ArrayList<>();      // {actual, formal, conf}
        if (!aField) for (int[] x : neighbors(Kinds.ARG_IN, a, false)) { argIn.add(x); methods.add(g.owner(x[0])); }
        if (!bField) for (int[] x : neighbors(Kinds.RET_OUT, b, true)) { retOut.add(x); methods.add(g.owner(x[0])); }
        boolean any = false;
        for (int m : methods) {
            if (m < 0) continue;
            Set<Integer> entry = new LinkedHashSet<>(), exit = new LinkedHashSet<>();
            if (aField) entry.add(a);
            else for (int[] x : argIn) if (g.owner(x[0]) == m) entry.add(x[0]);
            if (bField) exit.add(b);
            else for (int[] x : retOut) if (g.owner(x[0]) == m) exit.add(x[0]);
            if (entry.isEmpty() || exit.isEmpty()) continue;
            String guard = m + ":" + entry + ":" + exit;
            Set<Integer> keep = expanded.get(guard);
            boolean first = keep == null;
            List<int[]> intra = List.of();
            if (first) {
                intra = intraEdges(m, entry);
                Set<Integer> fwd = closure(entry, intra, true), bwd = closure(exit, intra, false);
                keep = new HashSet<>(fwd);
                keep.retainAll(bwd);
                expanded.put(guard, keep);
            }
            if (keep.isEmpty()) continue;
            any = true;
            String via = "SUMMARY";
            // 이미 펼친 본문(재귀·같은 호출의 반복)이면 그 본문으로 들어가고 나오는 ARG_IN/RET_OUT만 잇는다
            if (!first) {
                for (int[] x : argIn) if (keep.contains(x[0])) emit(new E(e.a, key(x[0]), Kinds.ARG_IN, x[2], 0, -1, -1, via, e.depth + 1));
                for (int[] x : retOut) if (keep.contains(x[0])) emit(new E(key(x[0]), e.b, Kinds.RET_OUT, x[2], 0, -1, -1, via, e.depth + 1));
                continue;
            }
            for (int[] x : argIn) if (keep.contains(x[0])) { gnode(x[0], false); push(work, new E(e.a, key(x[0]), Kinds.ARG_IN, x[2], 0, -1, -1, via, e.depth + 1)); }
            for (int[] x : retOut) if (keep.contains(x[0])) { gnode(x[0], false); push(work, new E(key(x[0]), e.b, Kinds.RET_OUT, x[2], 0, -1, -1, via, e.depth + 1)); }
            for (int[] x : intra)
                if (keep.contains(x[0]) && keep.contains(x[1])) {
                    gnode(x[0], false);
                    gnode(x[1], false);
                    push(work, new E(key(x[0]), key(x[1]), x[2], x[3], x[4], -1, -1, via, e.depth + 1));
                }
        }
        return any;
    }

    private static void push(Deque<E> work, E e) {
        work.add(e);
    }

    /** kind CSR에서 v의 이웃 {이웃, v, conf} (reverse면 역방향). */
    private List<int[]> neighbors(int kind, int v, boolean reverse) {
        List<int[]> r = new ArrayList<>();
        Csr c = g.csr(kind, reverse);
        for (int i = c.begin(v); i < c.end(v); i++) if (c.conf(i) <= o.minConf()) r.add(new int[]{c.target(i), v, c.conf(i)});
        return r;
    }

    /** 메서드 m 안의 상주 간선 (질의와 같은 필터). FIELD는 입구일 때만 출발점, 그 밖에는 도착점(싱크)으로만. */
    private List<int[]> intraEdges(int m, Set<Integer> entry) {
        List<int[]> r = new ArrayList<>();
        int allowed = o.allowed();
        List<Integer> kinds = new ArrayList<>(List.of(Kinds.LOCAL_FLOW, Kinds.SUMMARY, Kinds.BIND_TO, Kinds.STORE, Kinds.LOAD));
        if (o.control()) kinds.add(Kinds.CONTROL_FLOW);
        List<Integer> sources = new ArrayList<>();
        for (int v = g.methodStart(m); v < g.methodEnd(m); v++) sources.add(v);
        for (int v : entry) if (g.kind(v) == Kinds.FIELD) sources.add(v);
        for (int v : sources) {
            for (int k : kinds) {
                Csr c = g.csr(k, false);
                for (int i = c.begin(v); i < c.end(v); i++) {
                    if (c.conf(i) > o.minConf()) continue;
                    int fl;
                    if (k == Kinds.SUMMARY) {
                        fl = QueryEngine.usableCombo(c.masks(i), allowed);
                        if (fl < 0) continue;
                    } else {
                        fl = c.flags(i);
                        if ((fl & ~allowed) != 0) continue;
                    }
                    int w = c.target(i);
                    if (g.owner(w) != m && g.kind(w) != Kinds.FIELD) continue;
                    r.add(new int[]{v, w, k, c.conf(i), fl});
                }
            }
        }
        return r;
    }

    private Set<Integer> closure(Set<Integer> seeds, List<int[]> edges, boolean forward) {
        Set<Integer> seen = new HashSet<>(seeds);
        Deque<Integer> q = new ArrayDeque<>(seeds);
        while (!q.isEmpty()) {
            int v = q.poll();
            if (forward && g.kind(v) == Kinds.FIELD && !seeds.contains(v)) continue;   // 필드는 싱크
            for (int[] e : edges) {
                int from = forward ? e[0] : e[1], to = forward ? e[1] : e[0];
                if (from == v && seen.add(to)) q.add(to);
            }
        }
        return seen;
    }

    // ───────────────────────────── Q3 호출 그래프 ─────────────────────────────

    /** Q3: 메서드 단위 호출 그래프 (호출자 → 피호출자, 간선 위치 = 호출 지점). */
    static QueryResult.FlowGraph callGraph(Snapshot g, SourceLines lines, int level, Set<Integer> starts, List<int[]> calls) {
        QueryResult.FlowGraph out = new QueryResult.FlowGraph(level);
        Set<Integer> ms = new LinkedHashSet<>(starts);
        for (int[] c : calls) {
            ms.add(c[0]);
            ms.add(c[1]);
        }
        for (int m : ms) {
            int fo = g.methodFormalOut(m);
            int[] fins = g.methodFormalIns(m);
            long sp = fins.length > 0 ? g.span(fins[0]) : fo >= 0 ? g.span(fo) : -1L;
            out.nodes.put("m" + m, new GNode("m" + m, -1, g.methodSig(m), -1, "METHOD", g.methodSig(m), lines.file(sp),
                    lines.line(sp), "", starts.contains(m)));
        }
        Set<String> seen = new HashSet<>();
        for (int[] c : calls) {
            if (!seen.add(c[0] + ">" + c[1] + ":" + c[2])) continue;
            long sp = c[2] >= 0 ? g.span(c[2]) : -1L;
            out.edges.add(new GEdge("m" + c[0], "m" + c[1], "CALL", Kinds.CONF_NAMES[c[3]],
                    sp < 0 ? "" : "L" + lines.line(sp), false, false, ""));
        }
        return out;
    }
}
