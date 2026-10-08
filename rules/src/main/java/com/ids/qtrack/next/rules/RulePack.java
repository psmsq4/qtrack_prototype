package com.ids.qtrack.next.rules;

import io.github.treesitter.jtreesitter.Language;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Query;
import io.github.treesitter.jtreesitter.QueryCursor;
import io.github.treesitter.jtreesitter.QueryMatch;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * Rule Pack: {@code rules/<framework>/<version>.yaml} + 같은 디렉터리의 {@code .scm} 조각 (설계서 3.3).
 * <pre>
 * rules:
 *   - id: spring.request-mapping
 *     match: |            # 인라인 query, 또는 query: request-mapping.scm
 *       (method_declaration ...) @m
 *     emit:
 *       endpoint: { method: "@m", httpMethod: "…식…", path: "…식…" }
 * </pre>
 * emit 종류는 엔진이 아는 닫힌 집합입니다. 새 어노테이션 대응은 YAML·.scm 추가로 끝나야 하며,
 * emit 종류가 모자라 엔진을 고쳐야 하면 설계 결함으로 기록합니다 (FR-IN-08).
 */
public final class RulePack implements AutoCloseable {
    public static final Set<String> EMIT_KINDS = Set.of("endpoint", "endpointParam", "classAttr", "bean", "inject",
            "mapper", "sql", "jdbc");
    private static final Set<String> CLASS_LEVEL = Set.of("classAttr", "bean", "mapper", "inject");

    public record Rule(String id, String source, String query, Map<String, Map<String, Expr>> emit) {
        int phase() {
            return emit.keySet().stream().allMatch(CLASS_LEVEL::contains) ? 0 : 1;
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Query> compiled = new LinkedHashMap<>();

    public List<Rule> rules() {
        return rules;
    }

    /** 내장 기본 Rule Pack (클래스패스 rules/index.txt). */
    public static RulePack builtin() {
        RulePack p = new RulePack();
        try (InputStream idx = RulePack.class.getResourceAsStream("/rules/index.txt")) {
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String f = line.trim();
                if (f.isEmpty() || f.startsWith("#")) continue;
                p.addYaml("classpath:/rules/" + f, read("/rules/" + f), name -> read("/rules/" + parent(f) + name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    /** 디렉터리 아래 모든 *.yaml을 더합니다. */
    public RulePack addDirectory(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path f : s.filter(x -> x.toString().endsWith(".yaml") || x.toString().endsWith(".yml")).sorted().toList()) {
                addYaml(f.toString(), Files.readString(f), name -> {
                    try {
                        return Files.readString(f.resolveSibling(name));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        return this;
    }

    private static String parent(String f) {
        int k = f.lastIndexOf('/');
        return k < 0 ? "" : f.substring(0, k + 1);
    }

    private static String read(String res) {
        try (InputStream in = RulePack.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalArgumentException("리소스 없음: " + res);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    public void addYaml(String source, String yaml, java.util.function.Function<String, String> resolver) {
        Map<String, Object> y = new Yaml().load(yaml);
        for (Map<String, Object> r : (List<Map<String, Object>>) y.getOrDefault("rules", List.of())) {
            String id = (String) r.get("id");
            String q = r.containsKey("match") ? (String) r.get("match") : resolver.apply((String) r.get("query"));
            Map<String, Map<String, Expr>> emit = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) r.get("emit")).entrySet()) {
                if (!EMIT_KINDS.contains(e.getKey()))
                    throw new IllegalArgumentException(id + ": 알 수 없는 emit 종류 " + e.getKey() + " (설계 결함으로 기록)");
                Map<String, Expr> attrs = new LinkedHashMap<>();
                for (var a : ((Map<String, Object>) e.getValue()).entrySet()) attrs.put(a.getKey(), Expr.parse(String.valueOf(a.getValue())));
                emit.put(e.getKey(), attrs);
            }
            rules.removeIf(x -> x.id.equals(id));                // 같은 id는 나중 파일이 덮어씀 (버전별 디렉터리)
            rules.add(new Rule(id, source, q, emit));
        }
        rules.sort(Comparator.comparingInt(Rule::phase));
    }

    /** 특정 규칙과 클래스 수준(phase 0) 규칙만 남긴 팩 (규칙 단위 골든 테스트, VR-03). */
    public RulePack only(String ruleId) {
        RulePack p = new RulePack();
        for (Rule r : rules) if (r.id.equals(ruleId) || r.phase() == 0) p.rules.add(r);
        return p;
    }

    /** only(ruleId)에서 그 규칙만 뺀 팩 (기준선). */
    public RulePack baselineFor(String ruleId) {
        RulePack p = new RulePack();
        for (Rule r : rules) if (!r.id.equals(ruleId) && r.phase() == 0) p.rules.add(r);
        return p;
    }

    private Query query(Language lang, Rule r) {
        return compiled.computeIfAbsent(r.id, k -> {
            // jtreesitter는 query 길이를 문자 수로 넘기므로 비ASCII(한글 주석 등)가 있으면 잘립니다. 주석 줄을 제거합니다.
            String q = r.query.lines().filter(l -> !l.stripLeading().startsWith(";")).reduce("", (x, y) -> x + y + "\n");
            if (!q.chars().allMatch(ch -> ch < 128))
                throw new IllegalArgumentException("규칙 " + r.id + ": query에는 ASCII만 쓸 수 있습니다 (주석 제외)");
            try {
                return new Query(lang, q);
            } catch (Exception e) {
                throw new IllegalArgumentException("규칙 " + r.id + " query 오류: " + e.getMessage(), e);
            }
        });
    }

    /** 한 파일 트리에 모든 규칙을 적용합니다. */
    public Facts apply(Node root) {
        Facts f = new Facts();
        Language lang = TreeSitterJava.language();
        for (Rule r : rules) {
            Query q = query(lang, r);
            try (QueryCursor cur = new QueryCursor(q)) {
                List<QueryMatch> matches = cur.findMatches(root).toList();
                for (QueryMatch m : matches) emit(r, m, f);
            }
        }
        return f;
    }

    private void emit(Rule r, QueryMatch m, Facts f) {
        Expr.Ctx ctx = new Expr.Ctx() {
            @Override
            public Optional<Node> capture(String name) {
                List<Node> l = m.findNodes(name);
                return l.isEmpty() ? Optional.empty() : Optional.of(l.getFirst());
            }

            @Override
            public String classAttr(Node n, String key) {
                Node c = enclosingClass(n);
                if (c == null) return "";
                return f.classAttrs.getOrDefault(c.getStartByte(), Map.of()).getOrDefault(key, "");
            }
        };
        for (var e : r.emit.entrySet()) {
            Map<String, Expr> a = e.getValue();
            switch (e.getKey()) {
                case "endpoint" -> {
                    Node mn = node(a.get("method"), ctx);
                    if (mn == null) continue;
                    f.endpoints.add(new Facts.Endpoint(mn.getStartByte(), val(a, "httpMethod", ctx, "ANY"),
                            val(a, "path", ctx, "/"), r.id));
                }
                case "endpointParam" -> {
                    Node p = node(a.get("param"), ctx);
                    if (p == null) continue;
                    f.endpointParams.put(p.getStartByte(), new Facts.EndpointParam(p.getStartByte(),
                            val(a, "name", ctx, ""), val(a, "source", ctx, ""), r.id));
                }
                case "classAttr" -> {
                    Node c = node(a.get("class"), ctx);
                    if (c == null) continue;
                    f.classAttrs.computeIfAbsent(c.getStartByte(), k -> new LinkedHashMap<>())
                            .put(val(a, "key", ctx, "attr"), val(a, "value", ctx, ""));
                }
                case "bean" -> {
                    Node c = node(a.get("class"), ctx);
                    if (c == null) continue;
                    f.beans.put(c.getStartByte(), val(a, "name", ctx, ""));
                }
                case "mapper" -> {
                    Node c = node(a.get("class"), ctx);
                    if (c != null) f.mappers.add(c.getStartByte());
                }
                case "inject" -> {
                    Node t = node(a.get("target"), ctx);
                    if (t == null) continue;
                    String q = val(a, "qualifier", ctx, "");
                    f.injects.put(t.getStartByte(), new Facts.Inject(t.getStartByte(), q.isEmpty() ? null : q, r.id));
                }
                case "sql" -> {
                    Node mn = node(a.get("method"), ctx);
                    if (mn == null) continue;
                    f.sqls.put(mn.getStartByte(), new Facts.Sql(mn.getStartByte(), val(a, "text", ctx, ""),
                            val(a, "type", ctx, "select"), r.id));
                }
                case "jdbc" -> {
                    Node c = node(a.get("call"), ctx);
                    if (c == null) continue;
                    f.jdbcs.put(c.getStartByte(), new Facts.Jdbc(c.getStartByte(), Integer.parseInt(val(a, "sqlArg", ctx, "0")),
                            Integer.parseInt(val(a, "bindArgsFrom", ctx, "1")), r.id));
                }
                default -> throw new IllegalStateException(e.getKey());
            }
            f.hit(r.id);
        }
    }

    private static String val(Map<String, Expr> a, String key, Expr.Ctx ctx, String def) {
        Expr e = a.get(key);
        if (e == null) return def;
        String v = Expr.eval(e, ctx);
        return v.isEmpty() ? def : v;
    }

    private static Node node(Expr e, Expr.Ctx ctx) {
        return e instanceof Expr.Cap c ? ctx.capture(c.name()).orElse(null) : null;
    }

    public static Node enclosingClass(Node n) {
        Optional<Node> p = Optional.of(n);
        while (p.isPresent()) {
            String t = p.get().getType();
            if (t.equals("class_declaration") || t.equals("interface_declaration") || t.equals("enum_declaration")
                    || t.equals("record_declaration")) return p.get();
            p = p.get().getParent();
        }
        return null;
    }

    @Override
    public void close() {
        compiled.values().forEach(Query::close);
        compiled.clear();
    }
}
