package com.ids.qtrack.next.java;

import com.ids.qtrack.next.ir.ClassIR;
import com.ids.qtrack.next.ir.EndpointIR;
import com.ids.qtrack.next.ir.EndpointParam;
import com.ids.qtrack.next.ir.FieldDecl;
import com.ids.qtrack.next.ir.FileIR;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.Span;
import com.ids.qtrack.next.java.JavaIndex.ClassInfo;
import com.ids.qtrack.next.java.JavaIndex.MethodInfo;
import com.ids.qtrack.next.rules.Facts;
import com.ids.qtrack.next.rules.RulePack;
import com.ids.qtrack.next.rules.TreeSitterJava;
import com.ids.qtrack.next.sql.PreparedSql;
import com.ids.qtrack.next.sql.SqlMethodBuilder.BindSlot;
import com.ids.qtrack.next.sql.SqlText;
import com.google.protobuf.ByteString;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 추출기 (Tier A + D). 2패스:
 * <ol>
 *   <li>{@link #index}: 모든 파일의 선언 색인 (호출 대상·필드 타입 해석용)</li>
 *   <li>{@link #extract}: 파일마다 Rule Pack 적용 → ClassIR/EndpointIR, 메서드 lowering → MethodIR.
 *       CST는 파일 처리가 끝나면 버립니다 (FR-IN-02).</li>
 * </ol>
 */
public final class JavaExtractor {
    public record Source(int id, String path, String text) {}

    private final RulePack rules;
    private final LibrarySummaries lib = LibrarySummaries.load();
    public final JavaIndex index = new JavaIndex();
    public final List<PreparedSql> sqls = new ArrayList<>();
    public final Map<String, Integer> ruleHits = new TreeMap<>();
    public int errorNodes, methods, lowerFailures;

    public JavaExtractor(RulePack rules) {
        this.rules = rules;
    }

    public void index(List<Source> files) {
        try (Parser p = TreeSitterJava.parser()) {
            for (Source f : files) {
                try (Tree t = p.parse(f.text).orElseThrow()) {
                    index.addFile(t.getRootNode());
                }
            }
        }
        index.link();
    }

    public FileIR extract(Source f) {
        FileIR.Builder out = FileIR.newBuilder().setFileId(f.id).setPath(f.path).setLanguage("java")
                .setSha256(ByteString.copyFrom(sha256(f.text)));
        try (Parser p = TreeSitterJava.parser(); Tree t = p.parse(f.text).orElseThrow()) {
            Node root = t.getRootNode();
            int errs = countErrors(root);
            Facts facts = rules.apply(root);
            facts.ruleHits.forEach((k, v) -> ruleHits.merge(k, v, Integer::sum));
            String pkg = "";
            for (Node c : root.getNamedChildren())
                if (c.getType().equals("package_declaration"))
                    pkg = c.getNamedChildren().stream().filter(x -> x.getType().contains("identifier")).findFirst()
                            .map(JavaIndex::text).orElse("");
            List<String> diags = new ArrayList<>();
            int[] lowerErrs = {0};
            walkTypes(root, pkg.isEmpty() ? null : pkg, null, (decl, fqn) -> {
                ClassInfo ci = index.cls(fqn);
                if (ci == null) return;
                out.addClasses(classIr(decl, ci, facts, f.id));
                for (Node m : members(decl)) {
                    if (!Set.of("method_declaration", "constructor_declaration", "compact_constructor_declaration").contains(m.getType())) continue;
                    MethodInfo mi = ci.methods.stream().filter(x -> x.start == m.getStartByte()).findFirst().orElse(null);
                    if (mi == null) continue;
                    methods++;
                    MethodIR ir;
                    try {
                        MethodLowering ml = new MethodLowering(index, ci, mi, m, facts, f.id, lib, sqls, diags);
                        ir = ml.lower();
                        lowerErrs[0] += ml.errorNodes;
                    } catch (RuntimeException e) {
                        lowerFailures++;
                        diags.add(mi.signature + ": lowering 실패 " + e);
                        ir = stub(mi, ci, f.id, m);
                    }
                    out.addMethods(ir);
                    Facts.Sql sf = facts.sqls.get(m.getStartByte());
                    if (sf != null) sqls.add(annotationSql(ci, mi, m, sf, f.id));
                }
            });
            for (Facts.Endpoint e : facts.endpoints) {
                EndpointIR ep = endpoint(e, facts, root, f.id);
                if (ep != null) out.addEndpoints(ep);
            }
            errorNodes += errs;
            out.setErrorNodes(errs).addAllDiagnostics(diags);
        }
        return out.build();
    }

    // ───────────────────────────── 선언 순회 ─────────────────────────────

    interface TypeVisitor {
        void visit(Node decl, String fqn);
    }

    private static void walkTypes(Node parent, String pkg, String outer, TypeVisitor v) {
        for (Node c : parent.getNamedChildren()) {
            if (c.getType().equals("ERROR")) {
                walkTypes(c, pkg, outer, v);
                continue;
            }
            if (!JavaIndex.isTypeDecl(c.getType())) continue;
            String simple = c.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
            String fqn = outer != null ? outer + "." + simple : pkg == null ? simple : pkg + "." + simple;
            v.visit(c, fqn);
            for (Node m : members(c)) if (JavaIndex.isTypeDecl(m.getType())) walkNested(m, fqn, v);
        }
    }

    private static void walkNested(Node decl, String outer, TypeVisitor v) {
        String fqn = outer + "." + decl.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
        v.visit(decl, fqn);
        for (Node m : members(decl)) if (JavaIndex.isTypeDecl(m.getType())) walkNested(m, fqn, v);
    }

    static List<Node> members(Node decl) {
        List<Node> out = new ArrayList<>();
        decl.getChildByFieldName("body").ifPresent(b -> collectMembers(b, out));
        return out;
    }

    private static void collectMembers(Node body, List<Node> out) {
        for (Node m : body.getNamedChildren()) {
            if (m.getType().equals("enum_body_declarations") || m.getType().equals("ERROR")) collectMembers(m, out);
            else out.add(m);
        }
    }

    private static int countErrors(Node n) {
        int c = n.isError() || n.isMissing() ? 1 : 0;
        if (!n.hasError()) return c;
        for (Node k : n.getChildren()) c += countErrors(k);
        return c;
    }

    // ───────────────────────────── ClassIR ─────────────────────────────

    private ClassIR classIr(Node decl, ClassInfo ci, Facts facts, int fileId) {
        ClassIR.Builder b = ClassIR.newBuilder().setName(ci.fqn).setKind(ci.kind)
                .setSuperClass(ci.superFqn == null ? "" : ci.superFqn).addAllInterfaces(ci.interfaces)
                .setSpan(span(decl, fileId));
        for (Node c : decl.getChildren())
            if (c.getType().equals("modifiers"))
                for (Node a : c.getNamedChildren())
                    if (a.getType().endsWith("annotation"))
                        a.getChildByFieldName("name").ifPresent(n -> b.addAnnotations(JavaIndex.text(n)));
        int key = decl.getStartByte();
        if (facts.beans.containsKey(key)) b.setBean(true).setBeanName(facts.beans.get(key));
        if (facts.mappers.contains(key)) b.setMapper(true);
        b.putAllAttrs(facts.classAttrs.getOrDefault(key, Map.of()));
        // 주입 필드: 필드 주입 + 생성자 주입 (this.f = 주입된 파라미터)
        Map<String, Facts.Inject> injected = new HashMap<>();
        for (Node m : members(decl)) {
            if (m.getType().equals("field_declaration")) {
                Facts.Inject in = facts.injects.get(m.getStartByte());
                if (in != null)
                    for (Node d : m.getChildrenByFieldName("declarator"))
                        d.getChildByFieldName("name").ifPresent(n -> injected.put(JavaIndex.text(n), in));
            } else if (m.getType().equals("constructor_declaration")) {
                Map<String, Facts.Inject> params = new HashMap<>();
                m.getChildByFieldName("parameters").ifPresent(ps -> {
                    for (Node p : ps.getNamedChildren()) {
                        Facts.Inject in = facts.injects.get(p.getStartByte());
                        if (in != null) p.getChildByFieldName("name").ifPresent(n -> params.put(JavaIndex.text(n), in));
                    }
                });
                m.getChildByFieldName("body").ifPresent(body -> ctorAssignments(body, params, injected));
            }
        }
        for (var f : ci.fields.values()) {
            FieldDecl.Builder fd = FieldDecl.newBuilder().setName(f.name).setType(f.type == null ? "" : f.type);
            Facts.Inject in = injected.get(f.name);
            if (in != null) {
                fd.setInjected(true).setRuleId(in.ruleId());
                if (in.qualifier() != null) fd.setQualifier(in.qualifier());
            }
            b.addFields(fd);
        }
        return b.build();
    }

    private static void ctorAssignments(Node n, Map<String, Facts.Inject> params, Map<String, Facts.Inject> injected) {
        if (n.getType().equals("assignment_expression")) {
            Node l = n.getChildByFieldName("left").orElse(null), r = n.getChildByFieldName("right").orElse(null);
            if (l != null && r != null && r.getType().equals("identifier") && params.containsKey(JavaIndex.text(r))) {
                String field = l.getType().equals("field_access")
                        ? l.getChildByFieldName("field").map(JavaIndex::text).orElse(null) : JavaIndex.text(l);
                if (field != null) injected.put(field, params.get(JavaIndex.text(r)));
            }
        }
        for (Node c : n.getNamedChildren()) ctorAssignments(c, params, injected);
    }

    // ───────────────────────────── Endpoint ─────────────────────────────

    private EndpointIR endpoint(Facts.Endpoint e, Facts facts, Node root, int fileId) {
        Node m = root.getDescendant(e.method(), e.method() + 1).orElse(null);
        while (m != null && !m.getType().equals("method_declaration")) m = m.getParent().orElse(null);
        if (m == null) return null;
        Node clsDecl = RulePack.enclosingClass(m);
        MethodInfo mi = null;
        for (ClassInfo ci : index.classes.values())
            for (MethodInfo x : ci.methods)
                if (x.start == m.getStartByte() && ci.file != null && clsDecl != null
                        && ci.simple.equals(clsDecl.getChildByFieldName("name").map(JavaIndex::text).orElse(""))) mi = x;
        if (mi == null) return null;
        EndpointIR.Builder b = EndpointIR.newBuilder().setHttpMethod(e.httpMethod()).setPath(e.path())
                .setMethodSignature(mi.signature).setRuleId(e.ruleId()).setSpan(span(m, fileId));
        List<Node> params = m.getChildByFieldName("parameters").map(Node::getNamedChildren).orElse(List.of())
                .stream().filter(x -> x.getType().equals("formal_parameter")).toList();
        for (int i = 0; i < params.size(); i++) {
            Facts.EndpointParam fp = facts.endpointParams.get(params.get(i).getStartByte());
            String name = fp != null ? fp.name() : params.get(i).getChildByFieldName("name").map(JavaIndex::text).orElse("p" + i);
            b.addParams(EndpointParam.newBuilder().setName(name).setFormalIndex(i).setSource(fp != null ? fp.source() : "implicit"));
        }
        return b.build();
    }

    // ───────────────────────────── MyBatis 어노테이션 SQL ─────────────────────────────

    private static final Pattern PARAM = Pattern.compile("([#$])\\{([^}]*)}");

    private PreparedSql annotationSql(ClassInfo ci, MethodInfo mi, Node m, Facts.Sql sf, int fileId) {
        List<String> paramNames = new ArrayList<>();
        m.getChildByFieldName("parameters").ifPresent(ps -> {
            for (Node p : ps.getNamedChildren()) {
                if (!p.getType().equals("formal_parameter")) continue;
                String n = p.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
                for (Node c : p.getChildren())
                    if (c.getType().equals("modifiers"))
                        for (Node a : c.getNamedChildren())
                            if (a.getType().equals("annotation") && a.getChildByFieldName("name").map(JavaIndex::text).orElse("").equals("Param"))
                                n = a.getChildByFieldName("arguments").map(x -> JavaIndex.text(x).replaceAll("[()\"\\s]", "")).orElse(n);
                paramNames.add(n);
            }
        });
        Matcher mm = PARAM.matcher(sf.text());
        StringBuilder sb = new StringBuilder();
        List<BindSlot> slots = new ArrayList<>();
        Set<String> roots = new LinkedHashSet<>();
        Span sp = span(m, fileId);
        while (mm.find()) {
            boolean dollar = mm.group(1).equals("$");
            String expr = mm.group(2).split(",")[0].trim();
            String[] parts = expr.split("\\.", 2);
            int slot = slots.size();
            slots.add(new BindSlot(slot, expr, parts[0], parts.length > 1 ? parts[1] : "", sp, dollar));
            roots.add(parts[0]);
            mm.appendReplacement(sb, Matcher.quoteReplacement(dollar ? "QT_DOLLAR_" + slot : SqlText.bindName(slot)));
        }
        mm.appendTail(sb);
        List<String> params = paramNames.size() > 1 ? paramNames : new ArrayList<>(roots);
        String ptype = mi.paramTypes.size() == 1 ? mi.paramTypes.getFirst() : null;
        String rtype = mi.returnType;
        return new PreparedSql(mi.signature, ci.fqn, mi.name, sp, sf.type(), ptype,
                index.isProject(rtype) ? rtype : null, Map.of(), params, slots, List.of(sb.toString()), false, false);
    }

    // ───────────────────────────── 기타 ─────────────────────────────

    private static MethodIR stub(MethodInfo mi, ClassInfo ci, int fileId, Node m) {
        MethodIR.Builder b = MethodIR.newBuilder().setSignature(mi.signature).setClassName(ci.fqn).setName(mi.name)
                .addAllParamTypes(mi.paramTypes).addAllParamNames(mi.paramNames).setReturnType(mi.returnType)
                .setIsAbstract(true).setSpan(span(m, fileId));
        List<String> names = new ArrayList<>();
        boolean hasThis = !mi.isStatic && !mi.isConstructor;
        if (hasThis) names.add("this");
        names.addAll(mi.paramNames);
        for (int i = 0; i < names.size(); i++) {
            b.addNodes(com.ids.qtrack.next.ir.Node.newBuilder().setLocalId(i)
                    .setKind(com.ids.qtrack.next.ir.NodeKind.FORMAL_IN).setName(names.get(i)).setSpan(span(m, fileId)));
            b.addFormalIn(i);
        }
        return b.setHasThis(hasThis).build();
    }

    static Span span(Node n, int fileId) {
        return Span.newBuilder().setFileId(fileId).setStart(n.getStartByte()).setEnd(n.getEndByte())
                .setLine(n.getStartPoint().row() + 1).build();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new byte[0];
        }
    }

    static Optional<Node> first(Node n, String type) {
        return n.getNamedChildren().stream().filter(x -> x.getType().equals(type)).findFirst();
    }
}
