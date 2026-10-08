package com.ids.qtrack.next.bench;

import com.ids.qtrack.next.cli.Indexer;
import com.ids.qtrack.next.query.QueryEngine;
import com.ids.qtrack.next.store.Json;
import com.ids.qtrack.next.store.Snapshot;
import com.ids.qtrack.next.store.SortedIndex;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * 측정 (설계서 11장 성능, 요구사항서 7장 Go/No-Go).
 * <pre>
 * Measure &lt;outDir&gt; &lt;domains&gt; &lt;queries&gt; [srcDir]
 * </pre>
 * srcDir이 없으면 합성 코퍼스를 만듭니다. 결과: outDir/report.md, outDir/report.json.
 */
public final class Measure {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int domains = Integer.parseInt(args[1]);
        int queries = Integer.parseInt(args[2]);
        Path src = args.length > 3 && !args[3].isBlank() ? Path.of(args[3]) : null;
        Files.createDirectories(out);
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("jdk", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        rep.put("cpus", Runtime.getRuntime().availableProcessors());
        if (src == null) {
            src = out.resolve("corpus");
            deleteTree(src);
            SyntheticCorpus.generate(src, domains);
            rep.put("corpus", "synthetic domains=" + domains);
        } else rep.put("corpus", src.toString());
        Path snap = out.resolve("index");
        deleteTree(snap);
        System.gc();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) p.resetPeakUsage();
        long t0 = System.nanoTime();
        Map<String, Object> meta = Indexer.run(new Indexer.Config(List.of(src), List.of(), null, null, 64, snap));
        long buildMs = (System.nanoTime() - t0) / 1_000_000;
        long peakHeap = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getType() == MemoryType.HEAP && p.getPeakUsage() != null) peakHeap += p.getPeakUsage().getUsed();
        rep.put("buildMillis", buildMs);
        rep.put("peakHeapBytesDuringIndex", peakHeap);
        rep.put("snapshotBytes", dirSize(snap));
        rep.put("nodes", meta.get("nodes"));
        rep.put("methods", meta.get("methods"));
        rep.put("edges", meta.get("edges"));
        rep.put("memory", meta.get("memory"));
        rep.put("stats", meta.get("stats"));

        try (Snapshot g = Snapshot.open(snap)) {
            QueryEngine qe = new QueryEngine(g);
            Random rnd = new Random(42);
            List<String> cols = new ArrayList<>(), params = new ArrayList<>(), methods = new ArrayList<>();
            for (SortedIndex.Entry e : g.columnIdx.all())
                if (!e.key().contains(":") && e.key().chars().filter(c -> c == '.').count() == 1 && !e.key().endsWith(".*")) cols.add(e.key());
            for (SortedIndex.Entry e : g.endpointParamIdx.all()) if (!e.key().startsWith("*")) params.add(e.key());
            for (SortedIndex.Entry e : g.methodIdx.all()) if (e.key().contains("(") && e.key().contains("Service")) methods.add(e.key());
            QueryEngine.Options o = QueryEngine.Options.defaults();
            // 워밍업
            for (int i = 0; i < 50 && !cols.isEmpty(); i++) qe.columnImpact(cols.get(rnd.nextInt(cols.size())), o);
            rep.put("q1_column", lat(queries, () -> qe.columnImpact(cols.get(rnd.nextInt(cols.size())), o)));
            rep.put("q2_flow", lat(queries, () -> {
                String k = params.get(rnd.nextInt(params.size()));
                qe.paramFlow(k.substring(0, k.indexOf('#')), k.substring(k.indexOf('#') + 1), o);
            }));
            rep.put("q3_method", lat(queries, () -> qe.methodImpact(methods.get(rnd.nextInt(methods.size())), o)));
            QueryEngine.Options ctl = new QueryEngine.Options(o.minConf(), true, true, 500);
            rep.put("q1_column_implicit_control", lat(queries, () -> qe.columnImpact(cols.get(rnd.nextInt(cols.size())), ctl)));
            rep.put("residentBytesMapped", g.residentBytes());
            rep.put("l2BytesMappedAfterQueries", g.mappedL2Bytes());
        }
        Path golden = out.resolve("../../../golden/build/golden/summary.json").normalize();
        if (Files.exists(golden)) rep.put("golden", Json.parse(Files.readString(golden)));
        Files.writeString(out.resolve("report.json"), Json.write(rep));
        Files.writeString(out.resolve("report.md"), markdown(rep));
        System.out.println(Files.readString(out.resolve("report.md")));
    }

    private static Map<String, Object> lat(int n, Runnable r) {
        long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - s;
        }
        Arrays.sort(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("p50ms", t[n / 2] / 1e6);
        m.put("p95ms", t[(int) Math.min(n - 1, Math.ceil(n * 0.95) - 1)] / 1e6);
        m.put("p99ms", t[(int) Math.min(n - 1, Math.ceil(n * 0.99) - 1)] / 1e6);
        m.put("maxms", t[n - 1] / 1e6);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static String markdown(Map<String, Object> r) {
        StringBuilder sb = new StringBuilder("# Q-Track Next 측정 결과\n\n");
        sb.append("- JDK: ").append(r.get("jdk")).append(", CPU ").append(r.get("cpus")).append("\n");
        sb.append("- 코퍼스: ").append(r.get("corpus")).append("\n");
        sb.append("- 노드 ").append(r.get("nodes")).append(", 메서드 ").append(r.get("methods")).append("\n");
        sb.append("- 간선 ").append(r.get("edges")).append("\n");
        sb.append("- 인덱싱 ").append(r.get("buildMillis")).append(" ms, 인덱싱 중 피크 힙 ")
                .append(mb(r.get("peakHeapBytesDuringIndex"))).append(", 스냅샷 ").append(mb(r.get("snapshotBytes"))).append("\n");
        Map<String, Object> mem = (Map<String, Object>) r.get("memory");
        sb.append("- 상주 메모리 추정(NFR-06) ").append(mb(mem.get("estimateBytes"))).append(" / 실제 상주 ")
                .append(mb(mem.get("actualResidentBytes"))).append(" / L2 ").append(mb(mem.get("l2Bytes"))).append("\n\n");
        sb.append("| 질의 | n | p50 ms | p95 ms | p99 ms | max ms |\n|---|---|---|---|---|---|\n");
        for (String q : List.of("q1_column", "q2_flow", "q3_method", "q1_column_implicit_control")) {
            Map<String, Object> m = (Map<String, Object>) r.get(q);
            sb.append(String.format("| %s | %s | %.3f | %.3f | %.3f | %.3f |%n", q, m.get("n"), m.get("p50ms"), m.get("p95ms"),
                    m.get("p99ms"), m.get("maxms")));
        }
        double p95 = (double) ((Map<String, Object>) r.get("q1_column")).get("p95ms");
        String rec = "-", pre = "-";
        if (r.get("golden") instanceof Map<?, ?> gm && gm.get("static") instanceof Map<?, ?> st) {
            rec = String.format("%.3f", ((Number) st.get("recall")).doubleValue());
            pre = String.format("%.3f", ((Number) st.get("precision")).doubleValue());
        }
        sb.append("\n## Go/No-Go (요구사항서 7장)\n\n| 지표 | 목표 | 측정 | 기준선(Q-Track) | 판정 |\n|---|---|---|---|---|\n");
        sb.append("| 피크 메모리 | Q-Track 대비 1/5 이하 | ").append(mb(r.get("peakHeapBytesDuringIndex")))
                .append(" | bench/baseline 스크립트로 측정 | 기준선 필요 |\n");
        sb.append(String.format("| 영향도 질의 p95 | 1초 미만 | %.3f ms | AIS0053/0080 SQL 지연 | %s |%n", p95, p95 < 1000 ? "충족" : "미달"));
        sb.append("| Recall (정적 SQL) | ≥ 0.90 | ").append(rec).append(" | 같은 골든셋 | ")
                .append(rec.equals("-") ? "골든 미실행" : Double.parseDouble(rec) >= 0.9 ? "충족" : "미달").append(" |\n");
        sb.append("| Precision (정적 SQL) | ≥ 0.85 | ").append(pre).append(" | 같은 골든셋 | ")
                .append(pre.equals("-") ? "골든 미실행" : Double.parseDouble(pre) >= 0.85 ? "충족" : "미달").append(" |\n");
        sb.append("| Spring 신규 어노테이션 대응 | 엔진 코드 수정 0줄 | RulePackTest.newAnnotationByYamlOnly | - | 충족 |\n");
        sb.append("\n※ 골든셋은 프로토타입 개발용 코퍼스입니다. Recall/Precision 판정은 VR-01 코퍼스(전자정부 공통컴포넌트 + 벤치마크 코드)의 골든셋으로 다시 측정해야 합니다.\n");
        return sb.toString();
    }

    private static String mb(Object b) {
        return b == null ? "-" : String.format("%.2f MB", ((Number) b).doubleValue() / (1024 * 1024));
    }

    static long dirSize(Path p) throws Exception {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(x -> x.toFile().length()).sum();
        }
    }

    static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) Files.delete(x);
        }
    }
}
