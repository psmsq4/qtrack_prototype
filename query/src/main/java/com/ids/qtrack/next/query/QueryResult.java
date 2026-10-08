package com.ids.qtrack.next.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 질의 결과 (설계서 7.4, FR-QR-08). */
public final class QueryResult {
    /** 영향 대상 하나. confidence는 경로상 가장 약한 간선. */
    public record Item(String target, String type, int distance, String confidence, boolean implicit, boolean control,
                       int pathId, int node, String file, int line) {}

    /** 펼친 경로의 한 단계 (path_step). gid = -1이면 L2 지역 노드. conditions = 조건 사슬 (FR-CF-06). */
    public record Step(int pathId, int seq, int gid, String kind, String name, String file, int line, String via,
                       String conditions) {}

    /** 전체 흐름 그래프의 노드. key: 상주 노드 "g&lt;gid&gt;", L2 노드 "l&lt;method&gt;:&lt;lid&gt;", Q3 메서드 "m&lt;method&gt;". */
    public record GNode(String key, int gid, String method, int lid, String kind, String name, String file, int line,
                        String conditions, boolean start) {}

    /** 전체 흐름 그래프의 간선 (데이터 흐름 방향). via: 펼치기 전 간선 종류 (graph 2에서 펼쳐진 경우). */
    public record GEdge(String from, String to, String kind, String conf, String label, boolean implicit, boolean control,
                        String via) {}

    /** --graph 1: 탐색이 지나간 상주 그래프 전체, --graph 2: LOCAL_FLOW·CONTROL_FLOW·SUMMARY를 모두 펼친 그래프. */
    public static final class FlowGraph {
        public final int level;
        public final Map<String, GNode> nodes = new LinkedHashMap<>();
        public final List<GEdge> edges = new ArrayList<>();

        public FlowGraph(int level) {
            this.level = level;
        }
    }

    public FlowGraph graph;
    public final String queryId;
    public final String kind;
    public final String query;
    public final Map<String, Object> options = new LinkedHashMap<>();
    public final List<Item> items = new ArrayList<>();
    public final List<Step> steps = new ArrayList<>();
    public final Map<String, Object> stats = new LinkedHashMap<>();

    public QueryResult(String queryId, String kind, String query) {
        this.queryId = queryId;
        this.kind = kind;
        this.query = query;
    }

    public List<Step> path(int pathId) {
        return steps.stream().filter(s -> s.pathId() == pathId).toList();
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queryId", queryId);
        m.put("kind", kind);
        m.put("query", query);
        m.put("options", options);
        m.put("stats", stats);
        List<Object> its = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("target", i.target());
            x.put("type", i.type());
            x.put("distance", i.distance());
            x.put("confidence", i.confidence());
            x.put("implicit", i.implicit());
            x.put("control", i.control());
            x.put("pathId", i.pathId());
            x.put("file", i.file());
            x.put("line", i.line());
            its.add(x);
        }
        m.put("items", its);
        List<Object> st = new ArrayList<>();
        for (Step s : steps) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("pathId", s.pathId());
            x.put("seq", s.seq());
            x.put("gid", s.gid());
            x.put("kind", s.kind());
            x.put("name", s.name());
            x.put("file", s.file());
            x.put("line", s.line());
            x.put("via", s.via());
            x.put("conditions", s.conditions());
            st.add(x);
        }
        m.put("steps", st);
        if (graph != null) {
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("level", graph.level);
            List<Object> ns = new ArrayList<>();
            for (GNode n : graph.nodes.values()) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", n.key());
                x.put("gid", n.gid());
                x.put("method", n.method());
                x.put("lid", n.lid());
                x.put("kind", n.kind());
                x.put("name", n.name());
                x.put("file", n.file());
                x.put("line", n.line());
                x.put("conditions", n.conditions());
                x.put("start", n.start());
                ns.add(x);
            }
            gm.put("nodes", ns);
            List<Object> es = new ArrayList<>();
            for (GEdge e : graph.edges) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("from", e.from());
                x.put("to", e.to());
                x.put("kind", e.kind());
                x.put("conf", e.conf());
                x.put("label", e.label());
                x.put("implicit", e.implicit());
                x.put("control", e.control());
                x.put("via", e.via());
                es.add(x);
            }
            gm.put("edges", es);
            m.put("graph", gm);
        }
        return m;
    }
}
