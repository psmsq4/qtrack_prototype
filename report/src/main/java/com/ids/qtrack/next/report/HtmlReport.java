package com.ids.qtrack.next.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 질의 결과(Parquet)를 DuckDB로 읽어 집계하고 HTML 리포트를 만듭니다 (FR-RP-02).
 * 경로의 각 단계마다 소스 위치와 원문 줄, 조건 사슬을 표시합니다.
 */
public final class HtmlReport {
    private HtmlReport() {}

    private final static Map<String, List<String>> FILE_CACHE = new HashMap<>();

    public static Path render(List<Path> queryDirs, Path outDir) throws IOException, SQLException {
        Files.createDirectories(outDir);
        StringBuilder body = new StringBuilder();
        try (Connection c = ParquetExport.duckdb(); Statement st = c.createStatement()) {
            for (Path q : queryDirs) section(st, q, body);
        }
        Path index = outDir.resolve("index.html");
        Files.writeString(index, page(body.toString()));
        return index;
    }

    private static void section(Statement st, Path q, StringBuilder out) throws SQLException {
        String impact = ParquetExport.esc(q.resolve("impact.parquet"));
        String steps = ParquetExport.esc(q.resolve("path_step.parquet"));
        String title = "";
        try (ResultSet rs = st.executeQuery("SELECT any_value(query), count(*) FROM read_parquet('" + impact + "')")) {
            if (rs.next()) title = rs.getString(1) == null ? q.getFileName().toString() : rs.getString(1);
        }
        out.append("<section><h2>").append(h(title)).append("</h2>");
        // 집계 (DuckDB)
        out.append("<div class=\"tiles\">");
        try (ResultSet rs = st.executeQuery("SELECT type, count(*) n, count(*) FILTER (WHERE confidence='EXACT') e,"
                + " count(*) FILTER (WHERE implicit) i, count(*) FILTER (WHERE control) c FROM read_parquet('" + impact
                + "') GROUP BY type ORDER BY n DESC")) {
            while (rs.next())
                out.append("<div class=\"tile\"><div class=\"n\">").append(rs.getInt(2)).append("</div><div>")
                        .append(h(rs.getString(1))).append("</div><div class=\"sub\">EXACT ").append(rs.getInt(3))
                        .append(" · implicit ").append(rs.getInt(4)).append(" · control ").append(rs.getInt(5)).append("</div></div>");
        }
        out.append("</div>");
        Map<Integer, List<String[]>> paths = new LinkedHashMap<>();
        try (ResultSet rs = st.executeQuery("SELECT path_id, seq, kind, name, file, line, via, conditions FROM read_parquet('"
                + steps + "') ORDER BY path_id, seq")) {
            while (rs.next())
                paths.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(new String[]{rs.getString(3),
                        rs.getString(4), rs.getString(5), String.valueOf(rs.getInt(6)), rs.getString(7), rs.getString(8)});
        }
        out.append("<table><thead><tr><th>거리</th><th>유형</th><th>영향 대상</th><th>신뢰도</th><th>표시</th><th>위치</th></tr></thead><tbody>");
        try (ResultSet rs = st.executeQuery("SELECT target, type, distance, confidence, implicit, control, path_id, file, line"
                + " FROM read_parquet('" + impact + "') ORDER BY distance, type, target")) {
            while (rs.next()) {
                String flags = (rs.getBoolean(5) ? "<span class=\"tag imp\">implicit</span>" : "")
                        + (rs.getBoolean(6) ? "<span class=\"tag ctl\">control</span>" : "");
                int pid = rs.getInt(7);
                out.append("<tr><td class=\"num\">").append(rs.getInt(3)).append("</td><td>").append(h(rs.getString(2)))
                        .append("</td><td><code>").append(h(rs.getString(1))).append("</code>");
                List<String[]> p = paths.get(pid);
                if (p != null && pid >= 0) {
                    out.append("<details><summary>경로 ").append(p.size()).append("단계</summary><ol class=\"path\">");
                    for (String[] s : p) {
                        out.append("<li><span class=\"kind\">").append(h(s[0])).append("</span> <code>").append(h(s[1]))
                                .append("</code>");
                        if (s[4] != null && !s[4].isEmpty()) out.append(" <span class=\"via\">via ").append(h(s[4])).append("</span>");
                        if (s[2] != null && !s[2].isEmpty())
                            out.append("<div class=\"loc\">").append(h(shortPath(s[2]))).append(":").append(s[3]).append("</div>");
                        String src = sourceLine(s[2], Integer.parseInt(s[3]));
                        if (!src.isEmpty()) out.append("<pre>").append(h(src)).append("</pre>");
                        if (s[5] != null && !s[5].isEmpty()) out.append("<div class=\"cond\">조건: ").append(h(s[5])).append("</div>");
                        out.append("</li>");
                    }
                    out.append("</ol></details>");
                }
                out.append("</td><td class=\"conf ").append(rs.getString(4).toLowerCase()).append("\">").append(h(rs.getString(4)))
                        .append("</td><td>").append(flags).append("</td><td class=\"loc\">")
                        .append(rs.getString(8) == null || rs.getString(8).isEmpty() ? "" : h(shortPath(rs.getString(8))) + ":" + rs.getInt(9))
                        .append("</td></tr>");
            }
        }
        out.append("</tbody></table>");
        if (java.nio.file.Files.exists(q.resolve("graph_edge.parquet"))) flowSection(st, q, out);
        out.append("</section>");
    }

    /** 전체 흐름 (--graph 1|2): Graphviz SVG + 메서드별 간선 표. */
    private static void flowSection(Statement st, Path q, StringBuilder out) throws SQLException {
        String nodes = ParquetExport.esc(q.resolve("graph_node.parquet"));
        String edges = ParquetExport.esc(q.resolve("graph_edge.parquet"));
        int level = 0, nn = 0, ne = 0;
        try (ResultSet rs = st.executeQuery("SELECT max(level), count(*) FROM read_parquet('" + nodes + "')")) {
            if (rs.next()) {
                level = rs.getInt(1);
                nn = rs.getInt(2);
            }
        }
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + edges + "')")) {
            if (rs.next()) ne = rs.getInt(1);
        }
        out.append("<h3>전체 흐름 (graph ").append(level).append(")</h3><div class=\"muted\">")
                .append(level >= 2 ? "LOCAL_FLOW·CONTROL_FLOW는 L2 DEF_USE·CONTROL로, SUMMARY는 호출된 메서드 안의 흐름(ARG_IN → … → RET_OUT)으로 펼침. "
                        : "탐색이 지나간 상주 간선 전체. ")
                .append("노드 ").append(nn).append(" · 간선 ").append(ne)
                .append(" · 굵은 빨간 테두리 = 시작점, 마름모 = 조건(PREDICATE), 점선 = 제어 의존</div>");
        Path dot = q.resolve("graph.dot");
        String svg = java.nio.file.Files.exists(dot) ? GraphDot.svg(dot) : null;
        if (svg != null) out.append("<div class=\"graph\">").append(svg).append("</div>");
        else out.append("<div class=\"muted\">그래프 그림: Graphviz(dot)가 없어 생략 — <code>dot -Tsvg graph.dot</code> 로 직접 렌더링</div>");
        out.append("<details").append(svg == null ? " open" : "").append("><summary>간선 목록 (메서드별)</summary>");
        out.append("<table class=\"flow\"><thead><tr><th>출발</th><th>간선</th><th>도착</th></tr></thead><tbody>");
        String cur = null;
        try (ResultSet rs = st.executeQuery("SELECT a.method, a.kind, a.name, a.line, a.conditions, a.is_start, e.kind, e.label,"
                + " e.confidence, e.implicit, e.expanded_from, b.kind, b.name, b.line, b.conditions, b.method"
                + " FROM read_parquet('" + edges + "') e"
                + " JOIN read_parquet('" + nodes + "') a ON e.from_key = a.key"
                + " JOIN read_parquet('" + nodes + "') b ON e.to_key = b.key"
                + " ORDER BY a.method, a.line, a.key, b.line")) {
            while (rs.next()) {
                String m = rs.getString(1) == null || rs.getString(1).isEmpty() ? "(전역 노드)" : rs.getString(1);
                if (!m.equals(cur)) {
                    out.append("<tr class=\"grp\"><td colspan=\"3\"><code>").append(h(m)).append("</code></td></tr>");
                    cur = m;
                }
                String edge = rs.getString(7) + (rs.getString(8) == null || rs.getString(8).isEmpty() ? "" : " " + rs.getString(8));
                String meta = rs.getString(9) + (rs.getBoolean(10) ? " · implicit" : "")
                        + (rs.getString(11) == null || rs.getString(11).isEmpty() ? "" : " · ⊂" + rs.getString(11));
                String other = rs.getString(16) == null || rs.getString(16).isEmpty() || rs.getString(16).equals(rs.getString(1))
                        ? "" : "<div class=\"loc\">" + h(rs.getString(16)) + "</div>";
                out.append("<tr><td>").append(cell(rs.getBoolean(6), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)))
                        .append("</td><td><span class=\"kind\">").append(h(edge)).append("</span><div class=\"loc\">").append(h(meta))
                        .append("</div></td><td>").append(cell(false, rs.getString(12), rs.getString(13), rs.getInt(14), rs.getString(15)))
                        .append(other).append("</td></tr>");
            }
        }
        out.append("</tbody></table></details>");
    }

    private static String cell(boolean start, String kind, String name, int line, String cond) {
        return (start ? "<b>★ </b>" : "") + "<span class=\"kind\">" + h(kind) + "</span> <code>" + h(name) + "</code>"
                + (line > 0 ? " <span class=\"loc\">L" + line + "</span>" : "")
                + (cond == null || cond.isEmpty() ? "" : "<div class=\"cond\">" + h(cond) + "</div>");
    }

    private static String sourceLine(String file, int line) {
        if (file == null || file.isEmpty() || line <= 0) return "";
        List<String> lines = FILE_CACHE.computeIfAbsent(file, f -> {
            try {
                return Files.readAllLines(Path.of(f));
            } catch (IOException | RuntimeException e) {
                return List.of();
            }
        });
        return line <= lines.size() ? lines.get(line - 1).strip() : "";
    }

    private static String shortPath(String p) {
        String[] parts = p.split("/");
        return parts.length <= 3 ? p : "…/" + parts[parts.length - 3] + "/" + parts[parts.length - 2] + "/" + parts[parts.length - 1];
    }

    static String h(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String page(String body) {
        return """
                <!doctype html>
                <html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Q-Track Next 영향도 리포트</title>
                <style>
                :root{--bg:#fbfbfa;--fg:#1f2328;--muted:#656d76;--line:#d8dee4;--card:#fff;--accent:#0b6bcb;--exact:#1a7f37;--resolved:#9a6700;--heuristic:#cf222e;--code:#f3f4f6}
                @media (prefers-color-scheme: dark){:root{--bg:#0f1115;--fg:#e6edf3;--muted:#9aa4af;--line:#30363d;--card:#161b22;--accent:#58a6ff;--exact:#3fb950;--resolved:#d29922;--heuristic:#f85149;--code:#1f242c}}
                body{margin:0;padding:24px 16px;background:var(--bg);color:var(--fg);font:14px/1.5 system-ui,-apple-system,"Noto Sans KR",sans-serif}
                h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:28px 0 12px}
                .muted{color:var(--muted)}
                .tiles{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}
                .tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:8px 12px;min-width:120px}
                .tile .n{font-size:20px;font-weight:600}.tile .sub{color:var(--muted);font-size:12px}
                table{width:100%;border-collapse:collapse;background:var(--card);border:1px solid var(--line)}
                th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
                th{font-weight:600;color:var(--muted);font-size:12px}
                td.num{text-align:right;font-variant-numeric:tabular-nums}
                code,pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
                pre{background:var(--code);padding:4px 8px;border-radius:4px;margin:4px 0;white-space:pre-wrap}
                .conf.exact{color:var(--exact)}.conf.resolved{color:var(--resolved)}.conf.heuristic{color:var(--heuristic)}
                .tag{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:0 6px;font-size:11px;margin-right:4px}
                .loc{color:var(--muted);font-size:12px}.via{color:var(--muted);font-size:11px}.kind{font-size:11px;color:var(--accent)}
                .cond{font-size:12px;color:var(--resolved)}
                ol.path{margin:6px 0 0 18px;padding:0}ol.path li{margin:4px 0}
                details summary{cursor:pointer;color:var(--accent);font-size:12px}
                h3{font-size:14px;margin:20px 0 6px}
                .graph{overflow:auto;max-height:80vh;background:#fff;border:1px solid var(--line);border-radius:8px;padding:8px;margin:8px 0}
                .graph svg{max-width:none;height:auto}
                table.flow tr.grp td{background:var(--code);font-weight:600}
                @media (max-width:640px){td:nth-child(5),th:nth-child(5),td:nth-child(6),th:nth-child(6){display:none}}
                </style></head><body>
                <h1>Q-Track Next 영향도 리포트</h1>
                <div class="muted">신뢰도: EXACT &lt; RESOLVED &lt; HEURISTIC · implicit = SQL 필터 경유 · control = 제어 의존 경유</div>
                """ + body + "\n</body></html>\n";
    }
}
