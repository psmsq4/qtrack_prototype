package com.ids.qtrack.next.cli;

import com.ids.qtrack.next.query.QueryEngine;
import com.ids.qtrack.next.query.QueryResult;
import com.ids.qtrack.next.report.HtmlReport;
import com.ids.qtrack.next.report.ParquetExport;
import com.ids.qtrack.next.store.Json;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.Snapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * qtrack-next CLI (설계서 8장, FR-RP-01).
 * <pre>
 * qtrack-next index  --src &lt;dir&gt; [--catalog &lt;ddl|csv&gt;] [--datasource-map &lt;yaml&gt;] [--rules &lt;dir&gt;] --out index/&lt;snapshot&gt;
 * qtrack-next query column CUSTOMER.CUST_GRADE --index &lt;snap&gt; [--min-conf RESOLVED] [--implicit] [--control] [--out &lt;dir&gt;]
 * qtrack-next query flow   "GET /api/orders" custId --index &lt;snap&gt;
 * qtrack-next query method "com.x.OrderService#calc(java.lang.String)" --index &lt;snap&gt;
 * qtrack-next report html  --query &lt;result dir&gt;... --out report/
 * </pre>
 */
@Command(name = "qtrack-next", mixinStandardHelpOptions = true, version = "qtrack-next 0.1.0",
        subcommands = {Main.Index.class, Main.Query.class, Main.Report.class, Main.Export.class, Main.Stats.class})
public final class Main implements Runnable {
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    @Command(name = "index", description = "소스를 추출해 인덱스 스냅샷을 만든다")
    static final class Index implements Callable<Integer> {
        @Option(names = "--src", required = true, description = "소스 디렉터리 (여러 번 지정 가능)") List<Path> src;
        @Option(names = "--catalog", description = "카탈로그 DDL(.sql/.ddl) 또는 CSV (FR-SQ-01)") List<Path> catalogs = new ArrayList<>();
        @Option(names = "--datasource-map", description = "데이터소스 매핑 YAML (FR-SQ-05)") Path dsMap;
        @Option(names = "--rules", description = "추가 Rule Pack 디렉터리") Path rules;
        @Option(names = "--limit", defaultValue = "64", description = "동적 SQL 전개 상한") int limit;
        @Option(names = "--out", required = true, description = "스냅샷 디렉터리") Path out;

        @Override
        public Integer call() throws Exception {
            Map<String, Object> meta = Indexer.run(new Indexer.Config(src, catalogs, dsMap, rules, limit, out));
            System.out.println("스냅샷: " + out.toAbsolutePath());
            System.out.println("노드 " + meta.get("nodes") + " (인터페이스 " + meta.get("interfaceNodes") + ", 전역 "
                    + meta.get("globalNodes") + ", L2 " + meta.get("l2Nodes") + "), 메서드 " + meta.get("methods"));
            System.out.println("간선 " + meta.get("edges"));
            System.out.println("메모리 " + meta.get("memory"));
            return 0;
        }
    }

    static abstract class QueryBase implements Callable<Integer> {
        @Option(names = "--index", required = true, description = "스냅샷 디렉터리") Path index;
        @Option(names = "--min-conf", defaultValue = "HEURISTIC", description = "최소 신뢰도 EXACT|RESOLVED|HEURISTIC") String minConf;
        @Option(names = "--implicit", description = "SQL 필터 경유(암묵적) 흐름 포함 (FR-SQ-06)") boolean implicit;
        @Option(names = "--control", description = "제어 의존(CONTROL_FLOW) 경유 포함 (FR-CF-06)") boolean control;
        @Option(names = "--out", description = "결과 디렉터리 (result.json, impact.parquet, path_step.parquet)") Path out;
        @Option(names = "--paths", description = "경로를 콘솔에 출력") boolean showPaths;

        QueryEngine.Options opts() {
            return new QueryEngine.Options(Kinds.conf(minConf), implicit, control, 500);
        }

        abstract QueryResult run(QueryEngine q);

        @Override
        public Integer call() throws Exception {
            try (Snapshot s = Snapshot.open(index)) {
                QueryResult r = run(new QueryEngine(s));
                print(r);
                if (out != null) {
                    ParquetExport.write(r, out);
                    System.out.println("결과: " + out.toAbsolutePath());
                }
                return r.stats.containsKey("error") ? 2 : 0;
            }
        }

        void print(QueryResult r) {
            System.out.println("# " + r.kind + " " + r.query + "  " + r.options);
            if (r.stats.containsKey("error")) System.out.println("오류: " + r.stats.get("error"));
            System.out.printf("%-5s %-14s %-10s %-9s %s%n", "거리", "유형", "신뢰도", "표시", "영향 대상");
            for (QueryResult.Item i : r.items) {
                String fl = (i.implicit() ? "imp " : "") + (i.control() ? "ctl" : "");
                System.out.printf("%-5d %-14s %-10s %-9s %s%n", i.distance(), i.type(), i.confidence(), fl, i.target());
                if (showPaths && i.pathId() >= 0)
                    for (QueryResult.Step s : r.path(i.pathId()))
                        System.out.println("        " + s.kind() + " " + s.name() + (s.line() > 0 ? "  @" + Path.of(s.file()).getFileName() + ":" + s.line() : "")
                                + (s.conditions().isEmpty() ? "" : "  [" + s.conditions() + "]"));
            }
            System.out.println("stats " + r.stats);
        }
    }

    @Command(name = "query", description = "Q1~Q3 질의", subcommands = {Query.Column.class, Query.Flow.class, Query.Method.class})
    static final class Query implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }

        @Command(name = "column", description = "Q1 컬럼 영향도: 컬럼 → SQL → Mapper → Service → Endpoint")
        static final class Column extends QueryBase {
            @Parameters(index = "0", description = "TABLE.COLUMN | SCHEMA.TABLE.COLUMN | DS:SCHEMA.TABLE.COLUMN") String column;

            QueryResult run(QueryEngine q) {
                return q.columnImpact(column, opts());
            }
        }

        @Command(name = "flow", description = "Q2 파라미터 흐름: Endpoint 파라미터 → … → 컬럼")
        static final class Flow extends QueryBase {
            @Parameters(index = "0", description = "\"GET /api/orders\" 또는 /api/orders") String endpoint;
            @Parameters(index = "1", description = "파라미터 이름") String param;

            QueryResult run(QueryEngine q) {
                return q.paramFlow(endpoint, param, opts());
            }
        }

        @Command(name = "method", description = "Q3 메서드 변경 영향: 역방향 호출 그래프 + DI")
        static final class Method extends QueryBase {
            @Parameters(index = "0", description = "pkg.Class#method(Type,…) 또는 Class#method") String method;

            QueryResult run(QueryEngine q) {
                return q.methodImpact(method, opts());
            }
        }
    }

    @Command(name = "report", description = "리포트", subcommands = {Report.Html.class})
    static final class Report implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }

        @Command(name = "html", description = "질의 결과(Parquet) → HTML 리포트 (FR-RP-02)")
        static final class Html implements Callable<Integer> {
            @Option(names = "--query", required = true, description = "질의 결과 디렉터리 (여러 번)") List<Path> queries;
            @Option(names = "--out", required = true) Path out;

            @Override
            public Integer call() throws Exception {
                System.out.println("리포트: " + HtmlReport.render(queries, out).toAbsolutePath());
                return 0;
            }
        }
    }

    @Command(name = "export", description = "인덱스 노드 표를 Parquet으로 (node.parquet)")
    static final class Export implements Callable<Integer> {
        @Option(names = "--index", required = true) Path index;
        @Option(names = "--out", required = true) Path out;

        @Override
        public Integer call() throws Exception {
            try (Snapshot s = Snapshot.open(index)) {
                ParquetExport.writeNodes(s, out.resolve("node.parquet"));
            }
            System.out.println("내보냄: " + out.resolve("node.parquet").toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "stats", description = "스냅샷 통계 (노드·간선 수, 메모리 추정, 빌드 통계)")
    static final class Stats implements Callable<Integer> {
        @Option(names = "--index", required = true) Path index;

        @Override
        public Integer call() throws Exception {
            try (Snapshot s = Snapshot.open(index)) {
                Map<String, Object> m = new java.util.LinkedHashMap<>(s.meta);
                m.remove("diagnostics");
                m.put("residentBytesMapped", s.residentBytes());
                System.out.println(Json.write(m));
            }
            if (Files.exists(index.resolve("meta.json"))) return 0;
            return 1;
        }
    }
}
