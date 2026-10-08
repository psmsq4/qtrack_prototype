package com.ids.qtrack.next.query;

import com.ids.qtrack.next.store.Csr;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.L2Chunk;
import com.ids.qtrack.next.store.OffHeap;
import com.ids.qtrack.next.store.Snapshot;
import com.ids.qtrack.next.store.SortedIndex;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 질의 엔진 (설계서 7장). 경로 탐색은 상주 그래프만 쓰고(FR-QR-06), 시작점은 색인에서 바로 찾습니다(FR-QR-07).
 * <pre>
 * COMMON = LOCAL_FLOW | SUMMARY | BIND_TO | MAPS_TO | STORE | LOAD | COL_DERIVES [| CONTROL_FLOW (--control)]
 * 흐름(Q2)   정방향: 1단계 COMMON + RET_OUT,     2단계 COMMON + ARG_IN
 * 영향도(Q1) 역방향: 1단계 COMMON + ARG_IN⁻¹,   2단계 COMMON + RET_OUT⁻¹
 * </pre>
 */
public final class QueryEngine {
    public record Options(int minConf, boolean implicit, boolean control, int maxPaths) {
        public static Options defaults() {
            return new Options(Kinds.HEURISTIC, false, false, 500);
        }

        int allowed() {
            return (implicit ? Kinds.F_IMPLICIT : 0) | (control ? Kinds.F_CONTROL : 0);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();
    private final Snapshot g;

    public QueryEngine(Snapshot g) {
        this.g = g;
    }

    static int bit(int kind) {
        return 1 << kind;
    }

    static int common(Options o) {
        int m = bit(Kinds.LOCAL_FLOW) | bit(Kinds.SUMMARY) | bit(Kinds.BIND_TO) | bit(Kinds.MAPS_TO) | bit(Kinds.STORE)
                | bit(Kinds.LOAD) | bit(Kinds.COL_DERIVES);
        return o.control ? m | bit(Kinds.CONTROL_FLOW) : m;
    }

    // ───────────────────────────── 2단계 탐색 (설계서 7.2) ─────────────────────────────

    /** 방문 상태: parent/dist/경로 속성을 off-heap 배열에 기록합니다 (설계서 7.2 거리와 경로 복원). */
    final class Slice implements AutoCloseable {
        final boolean reverse;
        final Arena arena = Arena.ofConfined();
        final MemorySegment parent, dist, meta;           // meta: [kind+1, weakestConf, flags] 노드당 3바이트
        final List<Integer> order = new ArrayList<>();
        final Set<Integer> starts = new LinkedHashSet<>();

        Slice(boolean reverse) {
            this.reverse = reverse;
            int n = g.nodeCount();
            parent = arena.allocate(4L * Math.max(1, n));
            dist = arena.allocate(4L * Math.max(1, n));
            meta = arena.allocate(3L * Math.max(1, n));
            parent.fill((byte) 0xff);
            dist.fill((byte) 0xff);
        }

        boolean seen(int v) {
            return dist.getAtIndex(OffHeap.INT, v) >= 0;
        }

        int dist(int v) {
            return dist.getAtIndex(OffHeap.INT, v);
        }

        int parent(int v) {
            return parent.getAtIndex(OffHeap.INT, v);
        }

        int viaKind(int v) {
            return Byte.toUnsignedInt(meta.get(OffHeap.BYTE, 3L * v)) - 1;
        }

        int conf(int v) {
            return meta.get(OffHeap.BYTE, 3L * v + 1);
        }

        int flags(int v) {
            return meta.get(OffHeap.BYTE, 3L * v + 2);
        }

        void visit(int v, int p, int d, int kind, int conf, int flags) {
            parent.setAtIndex(OffHeap.INT, v, p);
            dist.setAtIndex(OffHeap.INT, v, d);
            meta.set(OffHeap.BYTE, 3L * v, (byte) (kind + 1));
            meta.set(OffHeap.BYTE, 3L * v + 1, (byte) conf);
            meta.set(OffHeap.BYTE, 3L * v + 2, (byte) flags);
            order.add(v);
        }

        @Override
        public void close() {
            arena.close();
        }
    }

    Slice slice(Set<Integer> starts, boolean reverse, int p1, int p2, Options o) {
        Slice s = new Slice(reverse);
        for (int st : starts) {
            if (st < 0 || st >= g.nodeCount() || s.seen(st)) continue;
            s.visit(st, -1, 0, -1, 0, 0);
            s.starts.add(st);
        }
        bfs(s, new ArrayDeque<>(s.order), p1, o);
        bfs(s, new ArrayDeque<>(s.order), p2, o);                 // 1단계에서 도달한 모든 노드에서 출발
        return s;
    }

    private void bfs(Slice s, Deque<Integer> q, int mask, Options o) {
        int allowed = o.allowed();
        while (!q.isEmpty()) {
            int v = q.poll();
            for (int m = mask; m != 0; m &= m - 1) {
                int kind = Integer.numberOfTrailingZeros(m);
                Csr c = g.csr(kind, s.reverse);
                for (int i = c.begin(v), e = c.end(v); i < e; i++) {
                    int conf = c.conf(i);
                    if (conf > o.minConf) continue;                  // EXACT=0 < RESOLVED=1 < HEURISTIC=2 (D-07)
                    int fl;
                    if (kind == Kinds.SUMMARY) {
                        fl = usableCombo(c.masks(i), allowed);
                        if (fl < 0) continue;
                    } else {
                        fl = c.flags(i);
                        if ((fl & ~allowed) != 0) continue;
                    }
                    int w = c.target(i);
                    if (s.seen(w)) continue;
                    s.visit(w, v, s.dist(v) + 1, kind, Math.max(s.conf(v), conf), s.flags(v) | fl);
                    q.add(w);
                }
            }
        }
    }

    /** masks의 bit k = 조합 k만으로 도달. 허용 집합의 부분집합인 조합 중 가장 작은 것, 없으면 -1 (설계서 6장 usable). */
    static int usableCombo(int masks, int allowed) {
        for (int k : new int[]{0, 1, 2, 3})
            if ((masks & (1 << k)) != 0 && (k & ~allowed) == 0) return k;
        return -1;
    }

    // ───────────────────────────── Q1 컬럼 영향도 ─────────────────────────────

    public QueryResult columnImpact(String column, Options o) {
        long t0 = System.nanoTime();
        QueryResult r = new QueryResult("q" + SEQ.incrementAndGet(), "column", column);
        put(r, o);
        Set<Integer> starts = columnStarts(column);
        r.stats.put("startNodes", starts.size());
        if (starts.isEmpty()) {
            r.stats.put("error", "컬럼을 찾을 수 없음: " + column);
            return r;
        }
        int common = common(o);
        try (Slice rev = slice(starts, true, common | bit(Kinds.ARG_IN), common | bit(Kinds.RET_OUT), o);
             Slice fwd = slice(starts, false, common | bit(Kinds.RET_OUT), common | bit(Kinds.ARG_IN), o)) {
            Map<String, QueryResult.Item> items = new LinkedHashMap<>();
            Map<Integer, int[]> pathOf = new HashMap<>();
            collect(rev, r, items, pathOf, o);
            collect(fwd, r, items, pathOf, o);
            // 컬럼을 직접 참조하는 SQL은 흐름 플래그와 관계없이 거리 1로 포함
            for (int st : starts) for (boolean rv : new boolean[]{false, true}) {
                Csr c = g.csr(Kinds.MAPS_TO, rv);
                for (int i = c.begin(st), e = c.end(st); i < e; i++) {
                    int w = c.target(i);
                    int owner = g.owner(w);
                    if (owner < 0) continue;
                    String target = g.methodSig(owner);
                    String key = "SQL|" + target;
                    if (items.containsKey(key)) continue;
                    long sp = g.span(w);
                    items.put(key, new QueryResult.Item(target, "SQL", 1, Kinds.CONF_NAMES[c.conf(i)],
                            (c.flags(i) & Kinds.F_IMPLICIT) != 0, false, -1, w, file(sp), line(sp)));
                }
            }
            r.items.addAll(sorted(items.values()));
            r.stats.put("visited", rev.order.size() + fwd.order.size());
        }
        finish(r, t0);
        return r;
    }

    private Set<Integer> columnStarts(String column) {
        String key = column.trim().toUpperCase(Locale.ROOT);
        String ds = null;
        if (key.contains(":")) {
            ds = key.substring(0, key.indexOf(':'));
            key = key.substring(key.indexOf(':') + 1);
        }
        Set<Integer> starts = new LinkedHashSet<>();
        String lookupKey = ds == null ? key : ds + ":" + key;
        for (int v : g.columnIdx.lookup(lookupKey)) starts.add(v);
        int dot = key.lastIndexOf('.');
        if (dot > 0) {
            String tablePart = key.substring(0, dot);
            String colPart = key.substring(dot + 1);
            String star = (ds == null ? "" : ds + ":") + tablePart + ".*";
            for (int v : g.columnIdx.lookup(star)) starts.add(v);         // TABLE.* 도 시작점 (FR-SQ-03)
            if (colPart.equals("*"))
                for (SortedIndex.Entry e : g.columnIdx.prefix((ds == null ? "" : ds + ":") + tablePart + "."))
                    starts.add(e.value());
        }
        return starts;
    }

    // ───────────────────────────── Q2 파라미터 흐름 ─────────────────────────────

    public QueryResult paramFlow(String endpoint, String param, Options o) {
        long t0 = System.nanoTime();
        QueryResult r = new QueryResult("q" + SEQ.incrementAndGet(), "flow", endpoint + " " + param);
        put(r, o);
        String ep = endpoint.trim();
        String key = (ep.contains(" ") ? ep : "* " + ep) + "#" + param;
        Set<Integer> starts = new LinkedHashSet<>();
        for (int v : g.endpointParamIdx.lookup(key)) starts.add(v);
        if (starts.isEmpty() && ep.contains(" "))
            for (int v : g.endpointParamIdx.lookup("* " + ep.substring(ep.indexOf(' ') + 1) + "#" + param)) starts.add(v);
        r.stats.put("startNodes", starts.size());
        if (starts.isEmpty()) {
            r.stats.put("error", "엔드포인트 파라미터를 찾을 수 없음: " + key);
            return r;
        }
        int common = common(o);
        try (Slice fwd = slice(starts, false, common | bit(Kinds.RET_OUT), common | bit(Kinds.ARG_IN), o)) {
            Map<String, QueryResult.Item> items = new LinkedHashMap<>();
            collect(fwd, r, items, new HashMap<>(), o);
            r.items.addAll(sorted(items.values()));
            r.stats.put("visited", fwd.order.size());
        }
        finish(r, t0);
        return r;
    }

    // ───────────────────────────── Q3 메서드 변경 영향 ─────────────────────────────

    public QueryResult methodImpact(String method, Options o) {
        long t0 = System.nanoTime();
        QueryResult r = new QueryResult("q" + SEQ.incrementAndGet(), "method", method);
        put(r, o);
        Set<Integer> ms = new LinkedHashSet<>();
        for (int v : g.methodIdx.lookup(method.trim())) ms.add(v);
        r.stats.put("startMethods", ms.size());
        if (ms.isEmpty()) {
            r.stats.put("error", "메서드를 찾을 수 없음: " + method);
            return r;
        }
        int n = g.methodCount();
        int[] dist = new int[n], parent = new int[n], site = new int[n], conf = new int[n];
        Arrays.fill(dist, -1);
        Deque<Integer> q = new ArrayDeque<>();
        for (int m : ms) {
            dist[m] = 0;
            parent[m] = -1;
            q.add(m);
        }
        Csr call = g.callRev;
        while (!q.isEmpty()) {                                          // 역방향 호출 그래프 (DI 해석 포함)
            int m = q.poll();
            for (int i = call.begin(m), e = call.end(m); i < e; i++) {
                if (call.conf(i) > o.minConf) continue;
                int caller = call.target(i);
                if (dist[caller] >= 0) continue;
                dist[caller] = dist[m] + 1;
                parent[caller] = m;
                site[caller] = call.site(i);
                conf[caller] = Math.max(conf[m], call.conf(i));
                q.add(caller);
            }
        }
        int pid = 0;
        for (int m = 0; m < n; m++) {
            if (dist[m] <= 0) continue;
            long sp = site[m] >= 0 ? g.span(site[m]) : -1L << 32;
            String type = dist[m] == 1 ? "직접 호출자" : "간접 호출자";
            r.items.add(new QueryResult.Item(g.methodSig(m), type, dist[m], Kinds.CONF_NAMES[conf[m]], false, false,
                    pid, site[m], file(sp), line(sp)));
            int seq = 0;
            for (int x = m; x >= 0; x = parent[x]) {
                long xs = parent[x] >= 0 && site[x] >= 0 ? g.span(site[x]) : g.methodFormalOut(x) >= 0 ? g.span(g.methodFormalOut(x)) : -1L << 32;
                r.steps.add(new QueryResult.Step(pid, seq++, site[x], "METHOD", g.methodSig(x), file(xs), line(xs),
                        parent[x] >= 0 ? "CALL" : "", ""));
            }
            pid++;
            if ((g.methodFlags(m) & 4) != 0) {                       // 엔드포인트 핸들러 → Endpoint
                for (int ep : endpointsOf(m)) {
                    long es = g.span(ep);
                    r.items.add(new QueryResult.Item(g.name(ep), "Endpoint", dist[m] + 1, Kinds.CONF_NAMES[conf[m]],
                            false, false, pid - 1, ep, file(es), line(es)));
                }
            }
        }
        for (int m : ms)
            if ((g.methodFlags(m) & 4) != 0)
                for (int ep : endpointsOf(m))
                    r.items.add(new QueryResult.Item(g.name(ep), "Endpoint", 1, "EXACT", false, false, -1, ep,
                            file(g.span(ep)), line(g.span(ep))));
        r.items.sort((a, b) -> a.distance() != b.distance() ? Integer.compare(a.distance(), b.distance()) : a.target().compareTo(b.target()));
        finish(r, t0);
        return r;
    }

    private Set<Integer> endpointsOf(int m) {
        Set<Integer> out = new LinkedHashSet<>();
        int fo = g.methodFormalOut(m);
        if (fo >= 0) {
            Csr c = g.csr(Kinds.RET_OUT, false);
            for (int i = c.begin(fo); i < c.end(fo); i++) if (g.kind(c.target(i)) == Kinds.ENDPOINT) out.add(c.target(i));
        }
        Csr a = g.csr(Kinds.ARG_IN, true);
        for (int fi : g.methodFormalIns(m))
            for (int i = a.begin(fi); i < a.end(fi); i++) if (g.kind(a.target(i)) == Kinds.ENDPOINT) out.add(a.target(i));
        return out;
    }

    // ───────────────────────────── 결과 모델 ─────────────────────────────

    private void collect(Slice s, QueryResult r, Map<String, QueryResult.Item> items, Map<Integer, int[]> pathOf, Options o) {
        for (int v : s.order) {
            if (s.starts.contains(v)) continue;
            int kind = g.kind(v), owner = g.owner(v);
            String target, type;
            switch (kind) {
                case Kinds.ENDPOINT -> { target = g.name(v); type = "Endpoint"; }
                case Kinds.COLUMN -> { target = g.name(v); type = "컬럼"; }
                case Kinds.FIELD -> { target = g.name(v); type = "필드"; }
                case Kinds.BIND -> { target = g.methodSig(owner) + " " + g.name(v); type = "SQL 바인드"; }
                case Kinds.UNKNOWN -> { target = g.methodSig(owner) + " " + g.name(v); type = "UNKNOWN"; }
                default -> {
                    if (owner < 0) continue;
                    boolean synthetic = (g.methodFlags(owner) & 1) != 0;
                    boolean handler = (g.methodFlags(owner) & 4) != 0;
                    if (kind == Kinds.FORMAL_IN && handler) { target = g.methodSig(owner) + " (" + g.name(v) + ")"; type = "Endpoint 입력"; }
                    else { target = g.methodSig(owner); type = synthetic ? "SQL" : "메서드"; }
                }
            }
            String key = type + "|" + target;
            QueryResult.Item prev = items.get(key);
            if (prev != null && prev.distance() <= s.dist(v)) continue;
            int pid = prev != null ? prev.pathId() : items.size();
            if (prev == null && items.size() >= o.maxPaths) pid = -1;
            long sp = g.span(v);
            items.put(key, new QueryResult.Item(target, type, s.dist(v), Kinds.CONF_NAMES[s.conf(v)],
                    (s.flags(v) & Kinds.F_IMPLICIT) != 0, (s.flags(v) & Kinds.F_CONTROL) != 0, pid, v, file(sp), line(sp)));
            if (pid >= 0) {
                final int fpid = pid;
                r.steps.removeIf(x -> x.pathId() == fpid);
                r.steps.addAll(expand(s, v, pid));
            }
        }
    }

    private static List<QueryResult.Item> sorted(java.util.Collection<QueryResult.Item> c) {
        List<QueryResult.Item> l = new ArrayList<>(c);
        l.sort((a, b) -> a.distance() != b.distance() ? Integer.compare(a.distance(), b.distance())
                : !a.type().equals(b.type()) ? a.type().compareTo(b.type()) : a.target().compareTo(b.target()));
        return l;
    }

    // ───────────────────────────── 경로 펼침 (설계서 7.3) ─────────────────────────────

    /** 시작점 → v 경로를 데이터 흐름 순서로 만들고, 같은 메서드 안의 LOCAL_FLOW/CONTROL_FLOW 구간만 L2 chunk를 열어 펼칩니다. */
    List<QueryResult.Step> expand(Slice s, int v, int pid) {
        List<Integer> chain = new ArrayList<>();
        for (int x = v; x >= 0; x = s.parent(x)) chain.add(x);
        // 역방향 탐색이면 v → … → 시작점이 곧 흐름 순서. 정방향이면 뒤집는다.
        if (!s.reverse) java.util.Collections.reverse(chain);
        List<QueryResult.Step> steps = new ArrayList<>();
        int seq = 0;
        for (int i = 0; i < chain.size(); i++) {
            int a = chain.get(i);
            int child = i == 0 ? -1 : s.reverse ? chain.get(i - 1) : a;
            String via = child < 0 ? "" : s.viaKind(child) < 0 ? "" : Kinds.EDGE_NAMES[s.viaKind(child)];
            if (i > 0) {
                int prev = chain.get(i - 1);
                int k = s.viaKind(child);
                if ((k == Kinds.LOCAL_FLOW || k == Kinds.CONTROL_FLOW) && g.owner(prev) >= 0 && g.owner(prev) == g.owner(a)) {
                    for (int lid : l2Path(g.owner(a), prev - g.chunkStart(prev), a - g.chunkStart(a), k == Kinds.CONTROL_FLOW)) {
                        L2Chunk c = g.chunk(g.owner(a));
                        long sp = c.span(lid);
                        steps.add(new QueryResult.Step(pid, seq++, -1, Kinds.NODE_NAMES[c.kind(lid)], g.strings.get(c.name(lid)),
                                file(sp), line(sp), "DEF_USE", conditions(g.owner(a), lid)));
                    }
                    via = "DEF_USE";
                }
            }
            long sp = g.span(a);
            String cond = g.owner(a) >= 0 && g.chunkStart(a) >= 0 ? conditions(g.owner(a), a - g.chunkStart(a)) : "";
            steps.add(new QueryResult.Step(pid, seq++, a, Kinds.NODE_NAMES[g.kind(a)], g.name(a), file(sp), line(sp), via, cond));
        }
        return steps;
    }

    /** chunk 안에서 from → to 사이의 중간 노드(lid). DEF_USE (+ CONTROL) BFS. */
    private List<Integer> l2Path(int method, int from, int to, boolean withControl) {
        L2Chunk c = g.chunk(method);
        int[] par = new int[c.nodes];
        Arrays.fill(par, -2);
        par[from] = -1;
        Deque<Integer> q = new ArrayDeque<>(List.of(from));
        while (!q.isEmpty() && par[to] == -2) {
            int v = q.poll();
            for (Csr e : withControl ? List.of(c.defUseFwd, c.controlFwd) : List.of(c.defUseFwd))
                for (int i = e.begin(v); i < e.end(v); i++) {
                    int w = e.target(i);
                    if (par[w] != -2) continue;
                    par[w] = v;
                    q.add(w);
                }
        }
        List<Integer> mid = new ArrayList<>();
        if (par[to] == -2) return mid;
        for (int x = par[to]; x >= 0 && x != from; x = par[x]) mid.add(x);
        java.util.Collections.reverse(mid);
        return mid;
    }

    /**
     * 값 lid가 어떤 조건의 어떤 분기에 종속되는지 (FR-CF-06). CONTROL 역방향으로 바로 위 조건을 찾고,
     * 그 조건의 상위 조건을 따라 올라가 바깥쪽부터 "a > 10 = FALSE → b == 1 = TRUE" 형태로 만듭니다.
     */
    public String conditions(int method, int lid) {
        L2Chunk c = g.chunk(method);
        if (lid < 0 || lid >= c.nodes || c.eControl == 0) return "";
        List<String> chain = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        int cur = lid;
        while (seen.add(cur)) {
            Csr rev = c.controlRev;
            int b = rev.begin(cur), e = rev.end(cur);
            if (b == e) break;
            List<String> alts = new ArrayList<>();
            int next = -1;
            for (int i = b; i < e; i++) {
                int p = rev.target(i);
                if (p == cur) continue;                              // 반복 조건의 자기 종속은 표시하지 않음
                int label = rev.label(i);
                String lab = Kinds.LABEL_NAMES[label] + (label == Kinds.L_CASE && rev.caseVal(i) >= 0 ? " " + g.strings.get(rev.caseVal(i)) : "");
                alts.add("`" + g.strings.get(c.name(p)) + "` = " + lab);
                if (next < 0) next = p;
            }
            if (alts.isEmpty()) break;
            chain.add(alts.size() == 1 ? alts.getFirst() : "(" + String.join(" | ", alts) + ")");
            cur = next;
        }
        java.util.Collections.reverse(chain);
        return String.join(" → ", chain);
    }

    // ───────────────────────────── 유틸 ─────────────────────────────

    private String file(long span) {
        int f = (int) (span >>> 32);
        return span < 0 || f < 0 ? "" : g.file(f);
    }

    private static int line(long span) {
        return span < 0 ? 0 : (int) (span & 0xffffffffL);
    }

    private static void put(QueryResult r, Options o) {
        r.options.put("minConf", Kinds.CONF_NAMES[o.minConf]);
        r.options.put("implicit", o.implicit);
        r.options.put("control", o.control);
    }

    private void finish(QueryResult r, long t0) {
        r.stats.put("items", r.items.size());
        r.stats.put("micros", (System.nanoTime() - t0) / 1000);
        r.stats.put("l2ChunksOpened", g.chunksOpened());
    }
}
