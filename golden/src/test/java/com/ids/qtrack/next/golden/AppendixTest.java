package com.ids.qtrack.next.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ids.qtrack.next.cli.Indexer;
import com.ids.qtrack.next.query.QueryEngine;
import com.ids.qtrack.next.query.QueryResult;
import com.ids.qtrack.next.store.Csr;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.L2Chunk;
import com.ids.qtrack.next.store.Snapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 설계서 부록 A 대조표: 노드 14개와 간선(D-01 적용 후 이름으로 비교), 스냅샷 재기동 (구현 순서 1~4단계 완료 기준). */
class AppendixTest {
    @TempDir
    static Path tmp;
    static Path snap;

    @BeforeAll
    static void index() throws Exception {
        snap = tmp.resolve("appendix");
        Indexer.run(new Indexer.Config(List.of(GoldenTest.ROOT.resolve("appendix/src")), List.of(), null, null, 64, snap));
    }

    static String method(Snapshot g, int m) {
        String sig = g.methodSig(m);
        if ((g.methodFlags(m) & 1) != 0) return sig.substring(sig.indexOf('#') + 1) + "(xml)";
        return sig.substring(sig.indexOf('#') + 1, sig.indexOf('('));
    }

    static String node(Snapshot g, int v) {
        return g.owner(v) < 0 ? g.name(v) : method(g, g.owner(v)) + ":" + g.name(v);
    }

    static final Set<String> CHUNKS = Set.of("list", "find", "select(xml)");

    static boolean inAppendix(Snapshot g, int v) {
        return g.owner(v) < 0 ? g.name(v).equals("ORDERS.CUST_ID") : CHUNKS.contains(method(g, g.owner(v)));
    }

    static Set<String> edges(Snapshot g, int kind) {
        Set<String> s = new TreeSet<>();
        Csr c = g.csr(kind, false);
        for (int v = 0; v < g.nodeCount(); v++)
            for (int i = c.begin(v); i < c.end(v); i++) {
                int w = c.target(i);
                if (!inAppendix(g, v) || !inAppendix(g, w)) continue;
                String attr = kind == Kinds.SUMMARY ? " masks=" + c.masks(i)
                        : kind == Kinds.MAPS_TO ? " " + Kinds.CLAUSE_NAMES[c.clause(i)]
                        : (c.flags(i) & Kinds.F_IMPLICIT) != 0 ? " implicit" : "";
                s.add(node(g, v) + " -> " + node(g, w) + attr);
            }
        return s;
    }

    @Test
    void nodesMatchAppendix() {
        try (Snapshot g = Snapshot.open(snap)) {
            Set<String> nodes = new TreeSet<>();
            for (int v = 0; v < g.nodeCount(); v++) if (inAppendix(g, v)) nodes.add(Kinds.NODE_NAMES[g.kind(v)] + " " + node(g, v));
            for (int m = 0; m < g.methodCount(); m++) {
                if (!CHUNKS.contains(method(g, m))) continue;
                L2Chunk c = g.chunk(m);
                for (int l = c.iface; l < c.nodes; l++)
                    nodes.add(Kinds.NODE_NAMES[c.kind(l)] + " " + method(g, m) + ":" + g.strings.get(c.name(l)));
            }
            assertEquals(new TreeSet<>(List.of(
                    "FORMAL_IN list:custId", "LOCAL list:trim() 결과", "LOCAL list:id#1", "ACTUAL_IN list:find(·)[0]",
                    "ACTUAL_OUT list:find() 결과", "FORMAL_OUT list:반환",
                    "FORMAL_IN find:id", "ACTUAL_IN find:select(·)[0]", "ACTUAL_OUT find:select() 결과", "FORMAL_OUT find:반환",
                    "FORMAL_IN select(xml):id", "BIND select(xml):#{id}", "FORMAL_OUT select(xml):결과",
                    "COLUMN ORDERS.CUST_ID")), nodes);
            assertEquals(14, nodes.size());
        }
    }

    @Test
    void edgesMatchAppendix() {
        try (Snapshot g = Snapshot.open(snap)) {
            assertEquals(Set.of("list:custId -> list:find(·)[0]", "list:find() 결과 -> list:반환",
                    "find:id -> find:select(·)[0]", "find:select() 결과 -> find:반환",
                    "select(xml):#{id} -> select(xml):결과 implicit"), edges(g, Kinds.LOCAL_FLOW));
            assertEquals(Set.of("list:find(·)[0] -> find:id", "find:select(·)[0] -> select(xml):id"), edges(g, Kinds.ARG_IN));
            assertEquals(Set.of("find:반환 -> list:find() 결과", "select(xml):결과 -> find:select() 결과"), edges(g, Kinds.RET_OUT));
            // D-06: 두 SUMMARY 모두 implicit만으로 도달 (masks bit1 = 조합 {implicit})
            assertEquals(Set.of("list:find(·)[0] -> list:find() 결과 masks=2", "find:select(·)[0] -> find:select() 결과 masks=2"),
                    edges(g, Kinds.SUMMARY));
            assertEquals(Set.of("select(xml):id -> select(xml):#{id}"), edges(g, Kinds.BIND_TO));
            assertTrue(edges(g, Kinds.MAPS_TO).contains("select(xml):#{id} -> ORDERS.CUST_ID WHERE"));
            // L2 DEF_USE (list): 0→1→2→3, 4→5
            int list = -1;
            for (int m = 0; m < g.methodCount(); m++) if (method(g, m).equals("list")) list = m;
            L2Chunk c = g.chunk(list);
            Set<String> du = new TreeSet<>();
            for (int v = 0; v < c.nodes; v++)
                for (int i = c.defUseFwd.begin(v); i < c.defUseFwd.end(v); i++)
                    du.add(g.strings.get(c.name(v)) + " -> " + g.strings.get(c.name(c.defUseFwd.target(i))));
            assertEquals(Set.of("custId -> trim() 결과", "trim() 결과 -> id#1", "id#1 -> find(·)[0]", "find() 결과 -> 반환"), du);
        }
    }

    /** 구현 순서 4단계 완료 기준: 재기동(다시 mmap) 후 질의 결과가 같다. L2는 경로를 펼칠 때만 열린다. */
    @Test
    void reopenGivesSameResult() {
        QueryEngine.Options o = QueryEngine.Options.defaults();
        List<String> first, second;
        try (Snapshot g = Snapshot.open(snap)) {
            assertEquals(0, g.chunksOpened());
            QueryResult r = new QueryEngine(g).columnImpact("ORDERS.CUST_ID", o);
            first = r.items.stream().map(i -> i.type() + "|" + i.target() + "|" + i.distance() + "|" + i.confidence()).toList();
        }
        try (Snapshot g = Snapshot.open(snap)) {
            QueryResult r = new QueryEngine(g).columnImpact("ORDERS.CUST_ID", o);
            second = r.items.stream().map(i -> i.type() + "|" + i.target() + "|" + i.distance() + "|" + i.confidence()).toList();
            assertTrue(((Map<?, ?>) g.meta.get("memory")).containsKey("estimateBytes"));
        }
        assertEquals(first, second);
    }
}
