package com.ids.qtrack.next.mybatis;

import com.ids.qtrack.next.ir.Span;
import com.ids.qtrack.next.sql.PreparedSql;
import com.ids.qtrack.next.sql.SqlMethodBuilder.BindSlot;
import com.ids.qtrack.next.sql.SqlText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis mapper XML 추출기 (설계서 3.4, FR-IN-03/04).
 * <ul>
 *   <li>{@code <mapper namespace="X">}의 {@code <select|insert|update|delete id="m">} → 합성 메서드 {@code X#m}</li>
 *   <li>{@code <include>}: 인라인 치환 ({@code <property>}의 {@code ${name}} 치환 포함)</li>
 *   <li>{@code <if>}/{@code <choose>}: 분기마다 포함/제외, {@code <foreach>}: 1회 전개,
 *       {@code <where>}/{@code <set>}/{@code <trim>}: 접두사·접미사 정리</li>
 *   <li>전개 조합 수가 상한(기본 64)을 넘으면 각 {@code <if>}를 "모두 포함" 1벌로 축약하고 신뢰도를 HEURISTIC으로 낮춥니다.</li>
 * </ul>
 */
public final class MyBatisExtractor {
    public static final int DEFAULT_LIMIT = 64;
    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete");
    private static final Pattern PARAM = Pattern.compile("([#$])\\{([^}]*)}");

    private final int limit;
    public final List<String> diagnostics = new ArrayList<>();

    public MyBatisExtractor(int limit) {
        this.limit = limit;
    }

    public record ResultMap(String type, Map<String, String> columnToProperty) {}

    /** mapper XML이면 문장 목록, 아니면 빈 목록. */
    public List<PreparedSql> extract(Path file, int fileId) throws Exception {
        XNode root = XNode.parse(file);
        if (root == null || !root.name.equals("mapper")) return List.of();
        String ns = root.attr("namespace") == null ? file.getFileName().toString() : root.attr("namespace");
        Map<String, XNode> fragments = new HashMap<>();
        Map<String, ResultMap> resultMaps = new HashMap<>();
        for (XNode n : root.elements()) {
            if (n.name.equals("sql") && n.attr("id") != null) {
                fragments.put(n.attr("id"), n);
                fragments.put(ns + "." + n.attr("id"), n);
            } else if (n.name.equals("resultMap") && n.attr("id") != null) {
                Map<String, String> props = new LinkedHashMap<>();
                collectResultProps(n, props);
                resultMaps.put(n.attr("id"), new ResultMap(n.attr("type"), props));
            }
        }
        List<PreparedSql> out = new ArrayList<>();
        for (XNode st : root.elements()) {
            if (!STATEMENTS.contains(st.name) || st.attr("id") == null) continue;
            out.add(statement(ns, st, fragments, resultMaps, fileId));
        }
        return out;
    }

    private static void collectResultProps(XNode n, Map<String, String> props) {
        for (XNode c : n.elements()) {
            if ((c.name.equals("id") || c.name.equals("result")) && c.attr("column") != null && c.attr("property") != null)
                props.put(c.attr("column").toUpperCase(Locale.ROOT), c.attr("property"));
            collectResultProps(c, props);
        }
    }

    private PreparedSql statement(String ns, XNode st, Map<String, XNode> fragments, Map<String, ResultMap> resultMaps,
                                  int fileId) {
        String id = st.attr("id");
        XNode t = st.copy();
        inlineIncludes(t, fragments, Map.of(), 0);
        Numbering num = new Numbering(fileId);
        num.walk(t, new HashMap<>(), new HashMap<>());
        boolean dynamic = isDynamic(t);
        long count = count(t.children);
        boolean capped = count > limit;
        Set<String> variants = new LinkedHashSet<>();
        if (!capped) {
            for (String v : gen(t.children, -1)) variants.add(SqlText.compact(v));
        } else {
            int branches = Math.max(1, maxBranches(t));
            for (int i = 0; i < branches; i++) for (String v : gen(t.children, i)) variants.add(SqlText.compact(v));
            diagnostics.add(ns + "." + id + ": 전개 조합 " + count + " > 상한 " + limit + " → 축약(HEURISTIC)");
        }
        String resultType = st.attr("resultType");
        Map<String, String> props = Map.of();
        if (st.attr("resultMap") != null && resultMaps.containsKey(st.attr("resultMap"))) {
            ResultMap rm = resultMaps.get(st.attr("resultMap"));
            resultType = rm.type;
            props = rm.columnToProperty;
        }
        Set<String> params = new LinkedHashSet<>();
        for (BindSlot s : num.slots) params.add(s.root());
        Span span = Span.newBuilder().setFileId(fileId).setStart(Math.max(0, st.offset)).setEnd(Math.max(0, st.offset))
                .setLine(st.line).build();
        return new PreparedSql(ns + "#" + id, ns, id, span, st.name, st.attr("parameterType"), resultType, props,
                new ArrayList<>(params), num.slots, new ArrayList<>(variants), dynamic, capped);
    }

    // ───────────────────────────── include ─────────────────────────────

    private void inlineIncludes(XNode n, Map<String, XNode> fragments, Map<String, String> props, int depth) {
        for (int i = 0; i < n.children.size(); i++) {
            Object o = n.children.get(i);
            if (o instanceof XNode.Text t && !props.isEmpty()) {
                String s = t.text();
                for (var e : props.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
                n.children.set(i, new XNode.Text(s, t.offset(), t.line()));
            }
            if (!(o instanceof XNode x)) continue;
            if (x.name.equals("include")) {
                XNode frag = fragments.get(x.attr("refid"));
                if (frag == null || depth > 10) {
                    diagnostics.add("include 대상 없음: " + x.attr("refid"));
                    n.children.remove(i--);
                    continue;
                }
                Map<String, String> p = new HashMap<>(props);
                for (XNode pr : x.elements()) if (pr.name.equals("property")) p.put(pr.attr("name"), pr.attr("value"));
                XNode copy = frag.copy();
                inlineIncludes(copy, fragments, p, depth + 1);
                n.children.remove(i);
                n.children.addAll(i, copy.children);
                i += copy.children.size() - 1;
            } else {
                inlineIncludes(x, fragments, props, depth);
            }
        }
    }

    // ───────────────────────────── 바인드 번호 매기기 ─────────────────────────────

    private static final class Numbering {
        final int fileId;
        final List<BindSlot> slots = new ArrayList<>();

        Numbering(int fileId) {
            this.fileId = fileId;
        }

        void walk(XNode n, Map<String, String> items, Map<String, String> binds) {
            Map<String, String> localBinds = new HashMap<>(binds);
            for (int i = 0; i < n.children.size(); i++) {
                Object o = n.children.get(i);
                if (o instanceof XNode.Text t) {
                    n.children.set(i, new XNode.Text(substitute(t, items, localBinds), t.offset(), t.line()));
                } else if (o instanceof XNode x) {
                    if (x.name.equals("bind") && x.attr("name") != null) {
                        localBinds.put(x.attr("name"), firstIdent(x.attr("value")));
                        continue;
                    }
                    Map<String, String> it = items;
                    if (x.name.equals("foreach")) {
                        it = new HashMap<>(items);
                        String coll = x.attr("collection") == null ? "list" : x.attr("collection");
                        if (x.attr("item") != null) it.put(x.attr("item"), coll);
                        if (x.attr("index") != null) it.put(x.attr("index"), coll);
                    }
                    walk(x, it, localBinds);
                }
            }
        }

        private String substitute(XNode.Text t, Map<String, String> items, Map<String, String> binds) {
            Matcher m = PARAM.matcher(t.text());
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                boolean dollar = m.group(1).equals("$");
                String expr = m.group(2).split(",")[0].trim();
                String[] parts = expr.split("\\.", 2);
                String first = parts[0], rest = parts.length > 1 ? parts[1] : "";
                String root;
                if (items.containsKey(first)) {
                    String coll = items.get(first).split("\\.")[0];
                    root = Set.of("list", "array", "collection").contains(coll) ? "_parameter" : coll;
                } else if (binds.containsKey(first)) {
                    root = binds.get(first) == null ? "_parameter" : binds.get(first);
                } else root = first;
                int slot = slots.size();
                int line = Math.max(1, t.line() - (int) t.text().substring(m.start()).chars().filter(c -> c == '\n').count());
                Span span = Span.newBuilder().setFileId(fileId).setStart(Math.max(0, t.offset()))
                        .setEnd(Math.max(0, t.offset())).setLine(line).build();
                slots.add(new BindSlot(slot, expr, root, rest, span, dollar));
                m.appendReplacement(sb, Matcher.quoteReplacement(dollar ? "QT_DOLLAR_" + slot : SqlText.bindName(slot)));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String firstIdent(String v) {
            if (v == null) return null;
            Matcher m = Pattern.compile("'[^']*'|\"[^\"]*\"|([A-Za-z_][A-Za-z0-9_]*)").matcher(v);
            while (m.find()) if (m.group(1) != null && !m.group(1).equals("_parameter")) return m.group(1);
            return null;
        }
    }

    // ───────────────────────────── 전개 ─────────────────────────────

    private static boolean isDynamic(XNode n) {
        for (XNode c : n.elements()) if (Set.of("if", "choose", "foreach", "where", "set", "trim").contains(c.name) || isDynamic(c)) return true;
        return false;
    }

    /** 전개 조합 수 (포화 산술). */
    private static long count(List<Object> children) {
        long c = 1;
        for (Object o : children) {
            if (!(o instanceof XNode x)) continue;
            long k = switch (x.name) {
                case "if" -> 1 + count(x.children);
                case "choose" -> {
                    long s = 0;
                    boolean other = false;
                    for (XNode b : x.elements()) {
                        s += count(b.children);
                        if (b.name.equals("otherwise")) other = true;
                    }
                    yield other ? s : s + 1;
                }
                case "bind", "selectKey" -> 1;
                default -> count(x.children);
            };
            c = Math.min(Long.MAX_VALUE / 4, c * Math.max(1, k));
        }
        return c;
    }

    private static int maxBranches(XNode n) {
        int m = 0;
        for (XNode c : n.elements()) {
            if (c.name.equals("choose")) m = Math.max(m, c.elements().size());
            m = Math.max(m, maxBranches(c));
        }
        return m;
    }

    /** chooseIdx < 0: 모든 조합. ≥ 0: 축약 모드 (if 모두 포함, choose는 해당 번째 분기). */
    private static List<String> gen(List<Object> children, int chooseIdx) {
        List<String> acc = new ArrayList<>(List.of(""));
        for (Object o : children) {
            List<String> alts = o instanceof XNode.Text t ? List.of(t.text()) : genNode((XNode) o, chooseIdx);
            List<String> next = new ArrayList<>();
            for (String a : acc) for (String b : alts) next.add(a + b);
            acc = next;
        }
        return acc;
    }

    private static List<String> genNode(XNode x, int chooseIdx) {
        return switch (x.name) {
            case "if" -> {
                List<String> in = gen(x.children, chooseIdx);
                if (chooseIdx >= 0) yield in;
                List<String> r = new ArrayList<>();
                r.add("");
                r.addAll(in);
                yield r;
            }
            case "choose" -> {
                List<XNode> branches = x.elements();
                boolean other = branches.stream().anyMatch(b -> b.name.equals("otherwise"));
                if (chooseIdx >= 0 && !branches.isEmpty())
                    yield gen(branches.get(Math.min(chooseIdx, branches.size() - 1)).children, chooseIdx);
                List<String> r = new ArrayList<>();
                for (XNode b : branches) r.addAll(gen(b.children, chooseIdx));
                if (!other) r.add("");
                yield r;
            }
            case "foreach" -> {
                String open = x.attrs.getOrDefault("open", ""), close = x.attrs.getOrDefault("close", "");
                yield gen(x.children, chooseIdx).stream().map(s -> " " + open + s + close + " ").toList();
            }
            case "where" -> gen(x.children, chooseIdx).stream().map(s -> trim(s, "WHERE", "AND |OR |AND\n|OR\n", "", "")).toList();
            case "set" -> gen(x.children, chooseIdx).stream().map(s -> trim(s, "SET", "", "", ",")).toList();
            case "trim" -> gen(x.children, chooseIdx).stream().map(s -> trim(s, x.attrs.getOrDefault("prefix", ""),
                    x.attrs.getOrDefault("prefixOverrides", ""), x.attrs.getOrDefault("suffix", ""),
                    x.attrs.getOrDefault("suffixOverrides", ""))).toList();
            case "bind", "selectKey" -> List.of("");
            default -> gen(x.children, chooseIdx);
        };
    }

    static String trim(String body, String prefix, String prefixOverrides, String suffix, String suffixOverrides) {
        String s = body.trim();
        if (s.isEmpty()) return " ";
        for (String p : prefixOverrides.split("\\|")) {
            String pt = p.trim();
            if (pt.isEmpty()) continue;
            if (s.toUpperCase(Locale.ROOT).startsWith(pt.toUpperCase(Locale.ROOT))
                    && (s.length() == pt.length() || !Character.isLetterOrDigit(s.charAt(pt.length())))) {
                s = s.substring(pt.length()).trim();
                break;
            }
        }
        for (String p : suffixOverrides.split("\\|")) {
            String pt = p.trim();
            if (!pt.isEmpty() && s.toUpperCase(Locale.ROOT).endsWith(pt.toUpperCase(Locale.ROOT))) {
                s = s.substring(0, s.length() - pt.length()).trim();
                break;
            }
        }
        return " " + prefix + " " + s + " " + suffix + " ";
    }
}
