package com.ids.qtrack.next.build;

import com.ids.qtrack.next.build.GraphBuilder.Call;
import com.ids.qtrack.next.build.GraphBuilder.M;
import com.ids.qtrack.next.build.GraphBuilder.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * 함수 요약 (설계서 6장, D-03). 호출 그래프의 SCC를 역위상 순서(호출당하는 쪽 먼저)로 처리하고,
 * SCC 안에서는 요약이 더 바뀌지 않을 때까지 반복합니다 (재귀·상호 재귀에서도 정확, FR-GR-06).
 * <ul>
 *   <li>요약 항목: (FORMAL_IN | FIELD) → (FORMAL_OUT | FIELD). 필드는 field-based 전역 노드라 부수 효과로 기록합니다.</li>
 *   <li>항목마다 도달 경로가 거친 플래그 조합(bit0 implicit, bit1 control) 집합 combos(4비트)와 가장 확정적인 신뢰도.</li>
 *   <li>종료: combos는 추가만, 신뢰도는 작아지기만 하므로(단조) 반드시 멈춥니다.</li>
 * </ul>
 */
final class Summaries {
    interface Sink {
        void edge(int src, int dst, int masks, int conf);
    }

    /** (combos, conf). */
    static final class Entry {
        int combos, conf;

        Entry(int combos, int conf) {
            this.combos = combos;
            this.conf = conf;
        }
    }

    private final List<M> methods;
    private final IntPredicate isField;
    /** summary[m]: src → dst → Entry */
    private final List<Map<Integer, Map<Integer, Entry>>> summary = new ArrayList<>();
    int sccCount, recursiveSccs, iterations;

    Summaries(List<M> methods, int ifaceTotal, IntPredicate isField) {
        this.methods = methods;
        this.isField = isField;
        for (int i = 0; i < methods.size(); i++) summary.add(new HashMap<>());
    }

    Summaries(List<M> methods, int ifaceTotal) {
        this(methods, ifaceTotal, g -> false);
    }

    // ───────────────────────────── SCC (Tarjan, 반복형) ─────────────────────────────

    void compute() {
        int n = methods.size();
        int[] index = new int[n], low = new int[n];
        boolean[] on = new boolean[n];
        java.util.Arrays.fill(index, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        int[] counter = {0};
        for (int root = 0; root < n; root++) {
            if (index[root] >= 0) continue;
            Deque<int[]> work = new ArrayDeque<>();                  // (node, next succ position)
            work.push(new int[]{root, 0});
            index[root] = low[root] = counter[0]++;
            stack.push(root);
            on[root] = true;
            while (!work.isEmpty()) {
                int[] top = work.peek();
                int v = top[0];
                List<Integer> succ = succ(v);
                if (top[1] < succ.size()) {
                    int w = succ.get(top[1]++);
                    if (index[w] < 0) {
                        index[w] = low[w] = counter[0]++;
                        stack.push(w);
                        on[w] = true;
                        work.push(new int[]{w, 0});
                    } else if (on[w]) low[v] = Math.min(low[v], index[w]);
                } else {
                    work.pop();
                    if (!work.isEmpty()) low[work.peek()[0]] = Math.min(low[work.peek()[0]], low[v]);
                    if (low[v] == index[v]) {
                        List<Integer> scc = new ArrayList<>();
                        int w;
                        do {
                            w = stack.pop();
                            on[w] = false;
                            scc.add(w);
                        } while (w != v);
                        solve(scc);                                   // Tarjan은 역위상 순서로 SCC를 내놓음
                    }
                }
            }
        }
    }

    private List<Integer> succ(int v) {
        List<Integer> s = new ArrayList<>();
        for (Call c : methods.get(v).calls) for (Target t : c.targets) s.add(t.callee().idx);
        return s;
    }

    private void solve(List<Integer> scc) {
        sccCount++;
        Set<Integer> members = new HashSet<>(scc);
        boolean recursive = scc.size() > 1 || succ(scc.getFirst()).contains(scc.getFirst());
        if (recursive) recursiveSccs++;
        for (int m : scc) summary.get(m).clear();                     // SCC 전체를 비우고 시작 (D-04와 같은 원칙)
        Deque<Integer> worklist = new ArrayDeque<>(scc);
        Set<Integer> queued = new HashSet<>(scc);
        while (!worklist.isEmpty()) {
            int mi = worklist.poll();
            queued.remove(mi);
            iterations++;
            Map<Integer, Map<Integer, Entry>> next = summarize(methods.get(mi));
            if (equal(next, summary.get(mi))) continue;
            summary.set(mi, next);
            if (!recursive) continue;
            for (Call c : methods.get(mi).callers) {                  // SCC 안의 호출자만 다시 계산
                int caller = c.caller.idx;
                if (members.contains(caller) && queued.add(caller)) worklist.add(caller);
            }
        }
    }

    private static boolean equal(Map<Integer, Map<Integer, Entry>> a, Map<Integer, Map<Integer, Entry>> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (var e : a.entrySet()) {
            Map<Integer, Entry> x = e.getValue(), y = b.get(e.getKey());
            if (!x.keySet().equals(y.keySet())) return false;
            for (var d : x.entrySet()) {
                Entry p = d.getValue(), q = y.get(d.getKey());
                if (p.combos != q.combos || p.conf != q.conf) return false;
            }
        }
        return true;
    }

    // ───────────────────────────── 메서드 하나의 요약 ─────────────────────────────

    /** 메서드 안 도달: LOCAL_FLOW ∪ CONTROL_FLOW ∪ BIND_TO ∪ STORE/LOAD + 현재 요약으로 만든 SUMMARY. */
    private Map<Integer, Map<Integer, Entry>> summarize(M m) {
        Map<Integer, List<long[]>> adj = new HashMap<>();
        for (long[] e : m.intra) adj.computeIfAbsent((int) e[0], k -> new ArrayList<>()).add(e);
        Map<Integer, List<Call>> byAin = new HashMap<>();
        for (Call c : m.calls) for (int a : c.ains) byAin.computeIfAbsent(a, k -> new ArrayList<>()).add(c);
        Set<Integer> sources = new LinkedHashSet<>();
        for (int f : m.fins) sources.add(f);
        for (long[] e : m.intra) if (isField.test((int) e[0])) sources.add((int) e[0]);
        for (Call c : m.calls)
            for (Target t : c.targets)
                for (int src : summary.get(t.callee().idx).keySet()) if (isField.test(src)) sources.add(src);
        Map<Integer, Map<Integer, Entry>> res = new HashMap<>();
        for (int s : sources) {
            Map<Long, Integer> best = new HashMap<>();
            List<List<Long>> buckets = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            best.put((long) s << 2, 0);
            buckets.getFirst().add((long) s << 2);
            for (int c = 0; c < 3; c++) {
                List<Long> b = buckets.get(c);
                for (int i = 0; i < b.size(); i++) {
                    long st = b.get(i);
                    int v = (int) (st >> 2), fl = (int) (st & 3);
                    if (best.get(st) < c) continue;
                    if (v != s && (v == m.fo || isField.test(v))) {
                        Entry e = res.computeIfAbsent(s, k -> new HashMap<>()).computeIfAbsent(v, k -> new Entry(0, 3));
                        e.combos |= 1 << fl;
                        e.conf = Math.min(e.conf, c);
                        if (isField.test(v)) continue;                // 필드는 싱크 (문맥 무관 전역 노드)
                    }
                    List<int[]> next = new ArrayList<>();             // (w, conf, flagsAdd)
                    for (long[] e : adj.getOrDefault(v, List.of())) next.add(new int[]{(int) e[1], (int) e[2], (int) e[3]});
                    for (Call call : byAin.getOrDefault(v, List.of()))
                        for (Target t : call.targets)
                            for (int[] ap : t.argPairs()) if (ap[0] == v) apply(t, ap[1], Math.max(ap[2], t.conf()), next);
                    if (v == s && isField.test(s))
                        for (Call call : m.calls) for (Target t : call.targets) apply(t, s, t.conf(), next);
                    for (int[] w : next) {
                        int nc = Math.max(c, w[1]);
                        int set = w[2] >= 16 ? w[2] >> 4 : 1 << (w[2] & 3);   // 요약: 조합 집합, 일반 간선: 단일 조합
                        for (int k = 0; k < 4; k++) {
                            if ((set & (1 << k)) == 0) continue;
                            long ns = ((long) w[0] << 2) | (fl | k);
                            Integer old = best.get(ns);
                            if (old != null && old <= nc) continue;
                            best.put(ns, nc);
                            buckets.get(nc).add(ns);
                        }
                    }
                }
            }
        }
        return res;
    }

    /**
     * 호출 대상 t의 요약 중 src에서 시작하는 항목을 호출 지점의 노드로 옮겨 next에 더합니다.
     * flagsAdd: 하위 2비트 = 단일 조합, 비트 4~7 = 여러 조합 집합(SUMMARY masks).
     */
    private void apply(Target t, int src, int conf, List<int[]> next) {
        Map<Integer, Entry> entries = summary.get(t.callee().idx).get(src);
        if (entries == null) return;
        for (var e : entries.entrySet()) {
            int dst = e.getKey();
            int flagsAdd = e.getValue().combos << 4;
            int c = Math.max(conf, e.getValue().conf);
            if (dst == t.callee().fo) for (int[] r : t.retPairs()) next.add(new int[]{r[1], Math.max(c, r[2]), flagsAdd});
            else if (isField.test(dst)) next.add(new int[]{dst, c, flagsAdd});
        }
    }

    // ───────────────────────────── 출력 ─────────────────────────────

    /** 극소 조합만 남긴 masks (설계서 6장 플래그 마스크). */
    static int minimal(int combos) {
        int out = combos;
        for (int k = 0; k < 4; k++) {
            if ((combos & (1 << k)) == 0) continue;
            for (int j = 0; j < 4; j++)
                if (j != k && (combos & (1 << j)) != 0 && (j & ~k) == 0) { out &= ~(1 << k); break; }
        }
        return out;
    }

    /** 호출 지점마다 SUMMARY 간선 (인자 → 결과, 인자 → 필드, 필드 → 결과). */
    void materialize(Sink sink) {
        for (M m : methods) {
            for (Call c : m.calls) {
                for (Target t : c.targets) {
                    Map<Integer, Map<Integer, Entry>> sm = summary.get(t.callee().idx);
                    for (var se : sm.entrySet()) {
                        int src = se.getKey();
                        List<int[]> srcs = new ArrayList<>();                 // (gid, conf)
                        if (isField.test(src)) srcs.add(new int[]{src, t.conf()});
                        else for (int[] ap : t.argPairs()) if (ap[1] == src) srcs.add(new int[]{ap[0], Math.max(ap[2], t.conf())});
                        for (var de : se.getValue().entrySet()) {
                            int dst = de.getKey();
                            List<int[]> dsts = new ArrayList<>();
                            if (dst == t.callee().fo) for (int[] r : t.retPairs()) dsts.add(new int[]{r[1], r[2]});
                            else if (isField.test(dst)) dsts.add(new int[]{dst, 0});
                            Entry e = de.getValue();
                            for (int[] s : srcs)
                                for (int[] d : dsts)
                                    if (s[0] != d[0])
                                        sink.edge(s[0], d[0], minimal(e.combos), Math.max(e.conf, Math.max(s[1], d[1])));
                        }
                    }
                }
            }
        }
    }

    /** summary.tbl 행: (method, src, dst, masks). */
    List<int[]> rows() {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < methods.size(); i++)
            for (var se : summary.get(i).entrySet())
                for (var de : se.getValue().entrySet())
                    out.add(new int[]{i, se.getKey(), de.getKey(), minimal(de.getValue().combos)});
        return out;
    }

    Map<Integer, Map<Integer, Entry>> of(int method) {
        return summary.get(method);
    }
}
