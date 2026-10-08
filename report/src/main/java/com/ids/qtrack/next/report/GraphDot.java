package com.ids.qtrack.next.report;

import com.ids.qtrack.next.query.QueryResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 전체 흐름 그래프 → Graphviz DOT. 메서드마다 클러스터, 전역 노드(컬럼·필드·엔드포인트)는 클러스터 밖.
 * PREDICATE는 마름모, CONTROL·CONTROL_FLOW는 점선, implicit은 회색 점선, 시작점은 굵은 테두리.
 */
public final class GraphDot {
    private GraphDot() {}

    public static String render(QueryResult.FlowGraph g, String title) {
        StringBuilder sb = new StringBuilder("digraph flow {\n");
        sb.append("  graph [rankdir=LR, fontname=\"Noto Sans CJK KR\", fontsize=11, label=\"").append(esc(title))
                .append("\", labelloc=t, compound=true];\n");
        sb.append("  node [shape=box, style=\"rounded,filled\", fillcolor=\"#ffffff\", fontname=\"Noto Sans CJK KR\", fontsize=10];\n");
        sb.append("  edge [fontname=\"Noto Sans CJK KR\", fontsize=9, color=\"#555555\"];\n");
        Map<String, List<QueryResult.GNode>> byMethod = new LinkedHashMap<>();
        for (QueryResult.GNode n : g.nodes.values()) byMethod.computeIfAbsent(n.method(), k -> new ArrayList<>()).add(n);
        int ci = 0;
        for (var e : byMethod.entrySet()) {
            boolean cluster = !e.getKey().isEmpty() && g.level > 0 && !e.getValue().getFirst().kind().equals("METHOD");
            if (cluster) {
                sb.append("  subgraph cluster_").append(ci++).append(" {\n    label=\"").append(esc(shortSig(e.getKey())))
                        .append("\"; style=rounded; color=\"#9aa4af\"; fontsize=10;\n");
            }
            for (QueryResult.GNode n : e.getValue()) sb.append(cluster ? "    " : "  ").append(node(n)).append('\n');
            if (cluster) sb.append("  }\n");
        }
        for (QueryResult.GEdge e : g.edges) {
            List<String> attrs = new ArrayList<>();
            String label = e.kind() + (e.label().isEmpty() ? "" : " " + e.label());
            attrs.add("label=\"" + esc(label) + "\"");
            if (e.kind().equals("CONTROL") || e.kind().equals("CONTROL_FLOW")) attrs.add("style=dashed, color=\"#9a6700\", fontcolor=\"#9a6700\"");
            else if (e.implicit()) attrs.add("style=dotted, color=\"#8c959f\"");
            else if (e.kind().equals("ARG_IN") || e.kind().equals("RET_OUT") || e.kind().equals("CALL")) attrs.add("color=\"#0b6bcb\", fontcolor=\"#0b6bcb\"");
            else if (e.kind().equals("MAPS_TO") || e.kind().equals("BIND_TO") || e.kind().equals("COL_DERIVES")) attrs.add("color=\"#1a7f37\", fontcolor=\"#1a7f37\"");
            if (e.conf().equals("HEURISTIC")) attrs.add("penwidth=0.6");
            sb.append("  \"").append(esc(e.from())).append("\" -> \"").append(esc(e.to())).append("\" [")
                    .append(String.join(", ", attrs)).append("];\n");
        }
        return sb.append("}\n").toString();
    }

    private static String node(QueryResult.GNode n) {
        String shape = switch (n.kind()) {
            case "PREDICATE" -> "shape=diamond, style=filled, fillcolor=\"#fff8c5\"";
            case "COLUMN" -> "shape=cylinder, style=filled, fillcolor=\"#dafbe1\"";
            case "FIELD" -> "shape=component, style=filled, fillcolor=\"#f3f4f6\"";
            case "ENDPOINT" -> "shape=house, style=filled, fillcolor=\"#ddf4ff\"";
            case "BIND", "UNKNOWN" -> "style=\"rounded,filled\", fillcolor=\"#dafbe1\"";
            case "METHOD" -> "style=\"rounded,filled\", fillcolor=\"#ddf4ff\"";
            case "LOCAL" -> "style=\"rounded,filled\", fillcolor=\"#f6f8fa\"";
            default -> "style=\"rounded,filled\", fillcolor=\"#ffffff\"";
        };
        String label = n.kind() + "\\n" + esc(trim(n.name())) + (n.line() > 0 ? "\\nL" + n.line() : "");
        return "\"" + esc(n.key()) + "\" [label=\"" + label + "\", " + shape + (n.start() ? ", penwidth=2.5, color=\"#cf222e\"" : "")
                + (n.conditions().isEmpty() ? "" : ", tooltip=\"" + esc(n.conditions()) + "\"") + "];";
    }

    private static String trim(String s) {
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }

    static String shortSig(String sig) {
        int h = sig.indexOf('#');
        String cls = h < 0 ? sig : sig.substring(0, h);
        return cls.substring(cls.lastIndexOf('.') + 1) + (h < 0 ? "" : sig.substring(h));
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** dot 실행 파일로 SVG를 만듭니다. 없거나 실패하면 null. */
    public static String svg(Path dotFile) {
        try {
            Process p = new ProcessBuilder("dot", "-Tsvg", dotFile.toAbsolutePath().toString()).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) return null;
            String s = new String(out, java.nio.charset.StandardCharsets.UTF_8);
            int k = s.indexOf("<svg");
            return k < 0 ? null : s.substring(k);
        } catch (Exception e) {
            return null;
        }
    }
}
