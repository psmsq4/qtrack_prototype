package com.ids.qtrack.next.cli;

import com.ids.qtrack.next.build.GraphBuilder;
import com.ids.qtrack.next.catalog.Catalog;
import com.ids.qtrack.next.catalog.CatalogLoader;
import com.ids.qtrack.next.catalog.DataSourceMap;
import com.ids.qtrack.next.ir.FileIR;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.Span;
import com.ids.qtrack.next.java.JavaExtractor;
import com.ids.qtrack.next.mybatis.MyBatisExtractor;
import com.ids.qtrack.next.rules.RulePack;
import com.ids.qtrack.next.sql.PreparedSql;
import com.ids.qtrack.next.sql.SqlMethodBuilder.BindSlot;
import com.ids.qtrack.next.sql.SqlMethodFactory;
import com.ids.qtrack.next.sql.SqlText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 인덱싱 파이프라인 (설계서 2.2): 추출 → IR(protobuf) 기록 → IR 읽기 → 그래프 빌드.
 * 엔진은 디스크의 IR만 읽으므로 추출기를 다른 언어로 바꿔도 됩니다 (FR-IR-01).
 */
public final class Indexer {
    public record Config(List<Path> sources, List<Path> catalogs, Path datasourceMap, Path rulesDir, int limit, Path out) {}

    public static Map<String, Object> run(Config cfg) throws Exception {
        long t0 = System.nanoTime();
        Map<String, Object> st = new TreeMap<>();
        List<Path> javaFiles = new ArrayList<>(), xmlFiles = new ArrayList<>(), sqlFiles = new ArrayList<>();
        for (Path src : cfg.sources) {
            try (Stream<Path> s = Files.walk(src)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".java")) javaFiles.add(p);
                    else if (n.endsWith(".xml") && isMapper(p)) xmlFiles.add(p);
                    else if (n.endsWith(".sql")) sqlFiles.add(p);
                }
            }
        }
        DataSourceMap ds = cfg.datasourceMap == null ? DataSourceMap.defaults() : DataSourceMap.load(cfg.datasourceMap);
        Catalog catalog = CatalogLoader.load(cfg.catalogs, ds);
        RulePack rules = RulePack.builtin();
        if (cfg.rulesDir != null) rules.addDirectory(cfg.rulesDir);

        int fid = 0;
        Map<Integer, Path> paths = new LinkedHashMap<>();
        List<JavaExtractor.Source> sources = new ArrayList<>();
        for (Path p : javaFiles) {
            paths.put(fid, p);
            sources.add(new JavaExtractor.Source(fid++, p.toAbsolutePath().toString(), Files.readString(p, StandardCharsets.UTF_8)));
        }
        // 1) Java (Tier A + D)
        long tj = System.nanoTime();
        JavaExtractor jx = new JavaExtractor(rules);
        jx.index(sources);
        List<FileIR> javaIr = new ArrayList<>();
        for (JavaExtractor.Source s : sources) javaIr.add(jx.extract(s));
        st.put("javaFiles", javaFiles.size());
        st.put("javaMethods", jx.methods);
        st.put("javaErrorNodes", jx.errorNodes);
        st.put("javaLoweringFailures", jx.lowerFailures);
        st.put("ruleHits", jx.ruleHits);
        st.put("javaMillis", (System.nanoTime() - tj) / 1_000_000);

        // 2) MyBatis XML + SQL 파일 + Java 내장/어노테이션 SQL (Tier C)
        Map<Integer, List<PreparedSql>> prepared = new LinkedHashMap<>();
        MyBatisExtractor mx = new MyBatisExtractor(cfg.limit);
        for (Path p : xmlFiles) {
            int id = fid++;
            paths.put(id, p);
            try {
                prepared.put(id, mx.extract(p, id));
            } catch (Exception e) {
                prepared.put(id, List.of());
                mx.diagnostics.add(p + ": XML 파싱 실패 " + e.getMessage());
            }
        }
        for (Path p : sqlFiles) {
            int id = fid++;
            paths.put(id, p);
            prepared.put(id, sqlFile(p, id));
        }
        for (PreparedSql ps : jx.sqls) prepared.computeIfAbsent(ps.span().getFileId(), k -> new ArrayList<>()).add(ps);
        SqlMethodFactory fac = new SqlMethodFactory(catalog, ds);
        for (List<PreparedSql> l : prepared.values()) for (PreparedSql p : l) fac.registerCtas(p);   // CTAS 선등록
        List<FileIR> sqlIr = new ArrayList<>();
        int dynamicStatements = 0, dynamicFailures = 0, staticFailures = 0;
        for (var e : prepared.entrySet()) {
            List<MethodIR> ms = new ArrayList<>();
            for (PreparedSql p : e.getValue()) {
                MethodIR m = fac.build(p);
                ms.add(m);
                if (p.dynamic()) {
                    dynamicStatements++;
                    dynamicFailures += m.getSql().getParseFailures() > 0 ? 1 : 0;
                } else staticFailures += m.getSql().getParseFailures() > 0 ? 1 : 0;
            }
            Path p = paths.get(e.getKey());
            String lang = p.toString().endsWith(".xml") ? "mybatis" : p.toString().endsWith(".sql") ? "sql" : "java-sql";
            sqlIr.add(FileIR.newBuilder().setFileId(e.getKey()).setPath(p.toAbsolutePath().toString()).setLanguage(lang)
                    .addAllMethods(ms).addAllDiagnostics(lang.equals("mybatis") ? List.of() : List.of()).build());
        }
        st.put("mapperFiles", xmlFiles.size());
        st.put("sqlFiles", sqlFiles.size());
        st.put("sqlStatements", fac.statements);
        st.put("sqlVariants", fac.variants);
        st.put("sqlVariantParseFailures", fac.parseFailures);
        st.put("sqlStatementsCapped", fac.cappedStatements);
        st.put("sqlDynamicStatements", dynamicStatements);
        st.put("sqlStatementsWithFailure.static", staticFailures);
        st.put("sqlStatementsWithFailure.dynamic", dynamicFailures);
        st.put("catalogTables", catalog.tableCount());

        // 3) IR 기록 → 다시 읽기 (계약 경계)
        Path irDir = cfg.out.resolve("ir");
        GraphBuilder.writeIr(irDir.resolve("java.pb"), javaIr);
        GraphBuilder.writeIr(irDir.resolve("sql.pb"), sqlIr);
        long irBytes = Files.size(irDir.resolve("java.pb")) + Files.size(irDir.resolve("sql.pb"));
        st.put("irBytes", irBytes);
        GraphBuilder gb = new GraphBuilder();
        for (FileIR f : GraphBuilder.readIr(irDir)) gb.add(f);
        for (String d : mx.diagnostics) gb.diagnostics().add(d);
        st.put("extractMillis", (System.nanoTime() - t0) / 1_000_000);
        return gb.build(cfg.out, new GraphBuilder.Options(cfg.limit, st));
    }

    private static boolean isMapper(Path p) {
        try {
            String head = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            return head.contains("<mapper");
        } catch (IOException e) {
            return false;
        }
    }

    /** 독립 SQL 파일: 문장마다 합성 메서드 (배치·ETL SQL의 INSERT…SELECT / CTAS 등). */
    static List<PreparedSql> sqlFile(Path p, int fileId) throws IOException {
        String text = Files.readString(p);
        List<PreparedSql> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        int line = 1, startLine = 1, startIdx = 0, n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') q = !q;
            if (c == '\n') line++;
            if (c == ';' && !q) {
                add(out, cur.toString(), p, fileId, startLine, byteOffset(text, startIdx), ++n);
                cur.setLength(0);
                startLine = line;
                startIdx = i + 1;
            } else {
                if (cur.toString().isBlank() && !Character.isWhitespace(c)) {
                    startLine = line;
                    startIdx = i;
                }
                cur.append(c);
            }
        }
        add(out, cur.toString(), p, fileId, startLine, byteOffset(text, startIdx), ++n);
        return out;
    }

    private static int byteOffset(String text, int charIdx) {
        return text.substring(0, Math.min(charIdx, text.length())).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void add(List<PreparedSql> out, String sql, Path p, int fileId, int line, int offset, int n) {
        String s = sql.strip();
        if (s.isEmpty() || s.lines().allMatch(l -> l.isBlank() || l.strip().startsWith("--"))) return;
        int[] cnt = new int[1];
        String numbered = SqlText.numberQuestionMarks(s, 0, cnt);
        Span span = Span.newBuilder().setFileId(fileId).setStart(offset).setEnd(offset).setLine(line).build();
        List<String> params = new ArrayList<>();
        List<BindSlot> slots = new ArrayList<>();
        for (int i = 0; i < cnt[0]; i++) {
            params.add("p" + (i + 1));
            slots.add(new BindSlot(i, "?" + (i + 1), "p" + (i + 1), "", span, false));
        }
        String name = "stmt" + n + "@L" + line;
        String cls = p.getFileName().toString();
        out.add(new PreparedSql(cls + "#" + name + "()", cls, name, span, "", null, null, Map.of(), params, slots,
                List.of(numbered), false, false));
    }
}
