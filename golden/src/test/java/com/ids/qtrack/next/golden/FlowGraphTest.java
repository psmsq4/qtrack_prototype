package com.ids.qtrack.next.golden;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ids.qtrack.next.cli.Indexer;
import com.ids.qtrack.next.query.QueryEngine;
import com.ids.qtrack.next.query.QueryResult;
import com.ids.qtrack.next.store.Snapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI --graph: 1 = 탐색한 상주 그래프 전체, 2 = LOCAL_FLOW·CONTROL_FLOW(L2)·SUMMARY까지 펼침. */
class FlowGraphTest {
    @TempDir
    Path tmp;

    static Set<String> edges(QueryResult.FlowGraph g) {
        Set<String> s = new TreeSet<>();
        for (QueryResult.GEdge e : g.edges) {
            QueryResult.GNode a = g.nodes.get(e.from()), b = g.nodes.get(e.to());
            s.add(a.kind() + " " + a.name() + " -" + e.kind() + (e.label().isEmpty() ? "" : " " + e.label()) + "-> " + b.kind() + " " + b.name());
        }
        return s;
    }

    QueryResult flow(String caseName, String ep, String param, QueryEngine.Options o) throws Exception {
        Path snap = tmp.resolve(caseName);
        Indexer.run(new Indexer.Config(List.of(GoldenTest.ROOT.resolve(caseName + "/src")), List.of(), null, null, 64, snap));
        try (Snapshot g = Snapshot.open(snap)) {
            return new QueryEngine(g).paramFlow(ep, param, o);
        }
    }

    @Test
    void controlLevel2ShowsAllBranches() throws Exception {
        QueryEngine.Options o = new QueryEngine.Options(2, false, true, 500, 2);
        Set<String> e = edges(flow("control", "POST /grade", "score", o).graph);
        // 경로 펼침(--paths)은 최단 경로 하나(grade#1)만 보이지만, 전체 흐름에는 모든 분기가 있다
        assertTrue(e.contains("PREDICATE score > 90 -CONTROL TRUE-> LOCAL grade#1"), e.toString());
        assertTrue(e.contains("PREDICATE score > 70 -CONTROL TRUE-> LOCAL grade#2"), e.toString());
        assertTrue(e.contains("PREDICATE score > 70 -CONTROL FALSE-> LOCAL grade#3"), e.toString());
        assertTrue(e.contains("FORMAL_IN grade -BIND_TO-> BIND #{grade}"), e.toString());
        assertFalse(e.stream().anyMatch(x -> x.contains("-CONTROL_FLOW->")), "graph 2에는 펼치지 않은 CONTROL_FLOW가 없어야 함");
    }

    @Test
    void level1KeepsResidentEdgesLevel2ExpandsSummary() throws Exception {
        Set<String> e1 = edges(flow("recursion", "POST /rec", "v", new QueryEngine.Options(2, false, false, 500, 1)).graph);
        assertTrue(e1.contains("ACTUAL_IN walk(·)[0] -SUMMARY-> ACTUAL_OUT walk() 결과"), e1.toString());
        assertFalse(e1.stream().anyMatch(x -> x.contains("-DEF_USE->")), "graph 1은 L2를 펼치지 않음");
        Set<String> e2 = edges(flow("recursion", "POST /rec", "v", new QueryEngine.Options(2, false, false, 500, 2)).graph);
        assertFalse(e2.stream().anyMatch(x -> x.contains("-SUMMARY->") || x.contains("-LOCAL_FLOW->")), e2.toString());
        // SUMMARY → 호출된 메서드 안: walk의 기저 return s 와 재귀 호출(자기 자신으로 ARG_IN)
        assertTrue(e2.contains("FORMAL_IN s -DEF_USE-> LOCAL return@L19"), e2.toString());
        assertTrue(e2.contains("FORMAL_OUT 반환 -RET_OUT-> ACTUAL_OUT walk() 결과"), e2.toString());
        assertTrue(e2.contains("ACTUAL_IN walk(·)[0] -ARG_IN-> FORMAL_IN s"), e2.toString());
        assertTrue(e2.contains("LOCAL e#1 -DEF_USE-> ACTUAL_IN save(·)[0]"), e2.toString());
    }
}
