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
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 골든셋 테스트 (VR-02, VR-04, 설계서 11장).
 * <pre>
 * golden/cases/&lt;case&gt;/src/…           입력 소스
 * golden/cases/&lt;case&gt;/expected.yaml    질의별 정답 집합("유형|대상"), 태그, CONTROL 간선 정답
 * </pre>
 * 질의마다 recall/precision을 계산하고 태그별(static/dynamic/…)로 집계해 build/golden/summary.md에 기록합니다.
 */
class GoldenTest {
    static final Path ROOT = Path.of(System.getProperty("golden.root", "golden/cases"));
    static final Path OUT = Path.of(System.getProperty("golden.out", "build/golden"));
    /** 태그 → [TP, FN, FP] */
    static final Map<String, long[]> METRICS = new TreeMap<>();
    static final List<String> ROWS = new ArrayList<>();

    @TestFactory
    Stream<DynamicNode> cases() throws Exception {
        List<DynamicNode> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(ROOT)) {
            for (Path dir : s.filter(d -> Files.exists(d.resolve("expected.yaml"))).sorted().toList()) out.add(caseNode(dir));
        }
        return out.stream();
    }

    @SuppressWarnings("unchecked")
    private DynamicNode caseNode(Path dir) throws Exception {
        Map<String, Object> exp;
        try (Reader r = Files.newBufferedReader(dir.resolve("expected.yaml"))) {
            exp = new Yaml().load(r);
        }
        String name = dir.getFileName().toString();
        Path snap = OUT.resolve("index").resolve(name);
        List<Path> catalogs = new ArrayList<>();
        for (String c : (List<String>) exp.getOrDefault("catalog", List.of())) catalogs.add(dir.resolve(c));
        Path src = dir.resolve((String) exp.getOrDefault("src", "src")).normalize();
        Path rules = exp.containsKey("rules") ? dir.resolve((String) exp.get("rules")) : null;
        List<String> caseTags = (List<String>) exp.getOrDefault("tags", List.of());
        List<DynamicNode> tests = new ArrayList<>();
        Snapshot[] holder = new Snapshot[1];
        tests.add(DynamicTest.dynamicTest("index", () -> {
            Indexer.run(new Indexer.Config(List.of(src), catalogs, null, rules, 64, snap));
            holder[0] = Snapshot.open(snap);
        }));
        for (Map<String, Object> q : (List<Map<String, Object>>) exp.getOrDefault("queries", List.of())) {
            tests.add(DynamicTest.dynamicTest((String) q.get("id"), () -> runQuery(name, holder[0], q, caseTags)));
        }
        for (Map<String, Object> c : (List<Map<String, Object>>) exp.getOrDefault("control", List.of())) {
            tests.add(DynamicTest.dynamicTest("control " + c.get("method"), () -> checkControl(holder[0], c)));
        }
        if (exp.containsKey("stats")) {
            tests.add(DynamicTest.dynamicTest("stats", () -> {
                Map<String, Object> st = (Map<String, Object>) holder[0].meta.get("stats");
                for (var e : ((Map<String, Object>) exp.get("stats")).entrySet()) {
                    String want = String.valueOf(e.getValue());
                    String got = String.valueOf(st.get(e.getKey()));
                    if (want.startsWith(">=")) assertTrue(Long.parseLong(got) >= Long.parseLong(want.substring(2)), e.getKey() + "=" + got);
                    else assertEquals(want, got, e.getKey());
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("close", () -> {
            if (holder[0] != null) holder[0].close();
        }));
        return DynamicContainer.dynamicContainer(name, tests);
    }

    @SuppressWarnings("unchecked")
    private void runQuery(String caseName, Snapshot g, Map<String, Object> q, List<String> caseTags) {
        Map<String, Object> o = (Map<String, Object>) q.getOrDefault("options", Map.of());
        QueryEngine.Options opt = new QueryEngine.Options(Kinds.conf(String.valueOf(o.getOrDefault("minConf", "HEURISTIC"))),
                Boolean.TRUE.equals(o.get("implicit")), Boolean.TRUE.equals(o.get("control")), 500);
        List<String> args = (List<String>) q.get("args");
        QueryEngine e = new QueryEngine(g);
        QueryResult r = switch ((String) q.get("kind")) {
            case "column" -> e.columnImpact(args.getFirst(), opt);
            case "flow" -> e.paramFlow(args.get(0), args.get(1), opt);
            case "method" -> e.methodImpact(args.getFirst(), opt);
            default -> throw new IllegalArgumentException(String.valueOf(q.get("kind")));
        };
        Set<String> actual = new TreeSet<>();
        for (QueryResult.Item i : r.items) actual.add(i.type() + "|" + i.target());
        Set<String> expected = new TreeSet<>((List<String>) q.getOrDefault("expect", List.of()));
        Set<String> absent = new TreeSet<>((List<String>) q.getOrDefault("absent", List.of()));
        boolean exact = !"contains".equals(q.get("mode"));
        Set<String> tp = new TreeSet<>(expected);
        tp.retainAll(actual);
        Set<String> fn = new TreeSet<>(expected);
        fn.removeAll(actual);
        Set<String> fp = new TreeSet<>(actual);
        if (exact) fp.removeAll(expected);
        else {
            fp.clear();
            for (String a : absent) if (actual.contains(a)) fp.add(a);
        }
        List<String> tags = new ArrayList<>(caseTags);
        tags.addAll((List<String>) q.getOrDefault("tags", List.of()));
        tags.add("all");
        synchronized (METRICS) {
            for (String t : tags) {
                long[] m = METRICS.computeIfAbsent(t, k -> new long[3]);
                m[0] += tp.size();
                m[1] += fn.size();
                m[2] += fp.size();
            }
            ROWS.add("| " + caseName + " | " + q.get("id") + " | " + String.join(",", tags.subList(0, tags.size() - 1)) + " | "
                    + tp.size() + " | " + fn.size() + " | " + fp.size() + " | " + r.stats.get("micros") + " |");
        }
        String msg = "\n누락(FN): " + fn + "\n오탐(FP): " + fp + "\n실제: " + actual;
        assertTrue(fn.isEmpty() && fp.isEmpty(), msg);
    }

    /** FR-CF 골든: 메서드 chunk의 CONTROL 간선 (조건 -라벨-> 값). */
    @SuppressWarnings("unchecked")
    private void checkControl(Snapshot g, Map<String, Object> c) {
        int[] ms = g.methodIdx.lookup((String) c.get("method"));
        assertTrue(ms.length > 0, "메서드 없음 " + c.get("method"));
        L2Chunk ch = g.chunk(ms[0]);
        Set<String> actual = new TreeSet<>();
        Csr f = ch.controlFwd;
        for (int v = 0; v < ch.nodes; v++)
            for (int i = f.begin(v); i < f.end(v); i++) {
                int label = f.label(i);
                String lab = Kinds.LABEL_NAMES[label] + (f.caseVal(i) >= 0 ? " " + g.strings.get(f.caseVal(i)) : "");
                actual.add(g.strings.get(ch.name(v)) + " -" + lab + "-> " + g.strings.get(ch.name(f.target(i))));
            }
        Set<String> expected = new TreeSet<>((List<String>) c.get("edges"));
        assertEquals(expected, actual);
    }

    @AfterAll
    static void summary() throws Exception {
        Files.createDirectories(OUT);
        StringBuilder sb = new StringBuilder("# 골든셋 결과 (VR-02)\n\n| 태그 | TP | FN | FP | Recall | Precision |\n|---|---|---|---|---|---|\n");
        Map<String, Object> json = new LinkedHashMap<>();
        for (var e : METRICS.entrySet()) {
            long[] m = e.getValue();
            double rec = m[0] + m[1] == 0 ? 1 : (double) m[0] / (m[0] + m[1]);
            double pre = m[0] + m[2] == 0 ? 1 : (double) m[0] / (m[0] + m[2]);
            sb.append(String.format("| %s | %d | %d | %d | %.3f | %.3f |%n", e.getKey(), m[0], m[1], m[2], rec, pre));
            json.put(e.getKey(), Map.of("tp", m[0], "fn", m[1], "fp", m[2], "recall", rec, "precision", pre));
        }
        sb.append("\n| 케이스 | 질의 | 태그 | TP | FN | FP | μs |\n|---|---|---|---|---|---|---|\n");
        ROWS.stream().sorted().forEach(r -> sb.append(r).append('\n'));
        Files.writeString(OUT.resolve("summary.md"), sb.toString());
        Files.writeString(OUT.resolve("summary.json"), com.ids.qtrack.next.store.Json.write(json));
    }
}
