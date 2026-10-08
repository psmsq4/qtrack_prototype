package com.ids.qtrack.next.java;

import io.github.treesitter.jtreesitter.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 1차 패스: 프로젝트 전체의 클래스·필드·메서드 선언 색인 (Tier B 없이 import·선언 타입 수준의 해석, 요구사항서 8장).
 */
public final class JavaIndex {
    public static final class MethodInfo {
        public String cls, name, returnType, signature;
        public List<String> paramTypes = new ArrayList<>(), paramNames = new ArrayList<>();
        public boolean isStatic, isAbstract, isConstructor;
        public int start;                           // 선언 시작 바이트 (2차 패스에서 CST 노드와 짝짓기)
    }

    public static final class FieldInfo {
        public String name, type;
        public boolean isStatic;
        public String constant;                     // static final String 상수 값 (내장 SQL 해석용)
    }

    public static final class ClassInfo {
        public String fqn, simple, pkg, kind, superRaw, superFqn, outer;
        public List<String> interfacesRaw = new ArrayList<>(), interfaces = new ArrayList<>();
        public final Map<String, FieldInfo> fields = new LinkedHashMap<>();
        public final List<MethodInfo> methods = new ArrayList<>();
        public FileCtx file;
    }

    /** 파일 단위 이름 해석 문맥. */
    public static final class FileCtx {
        public String pkg = "";
        public final Map<String, String> imports = new HashMap<>();
        public final List<String> wildcards = new ArrayList<>();
        public final List<String> classes = new ArrayList<>();
    }

    public final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, List<ClassInfo>> bySimple = new HashMap<>();
    private final Map<String, Set<String>> methodNames = new HashMap<>();
    private final Map<String, List<String>> implementors = new HashMap<>();

    private static final Set<String> JAVA_LANG = Set.of("String", "Object", "Integer", "Long", "Short", "Byte", "Double",
            "Float", "Boolean", "Character", "Number", "Math", "System", "StringBuilder", "StringBuffer", "Exception",
            "RuntimeException", "Thread", "Iterable", "CharSequence", "Comparable", "Enum", "Void", "Class", "Error",
            "IllegalArgumentException", "IllegalStateException", "NullPointerException", "Throwable", "Record");
    private static final Set<String> PRIMITIVES = Set.of("int", "long", "short", "byte", "char", "boolean", "double",
            "float", "void");

    // ───────────────────────────── 1차 패스 수집 ─────────────────────────────

    public FileCtx addFile(Node root) {
        FileCtx f = new FileCtx();
        for (Node c : root.getNamedChildren()) {
            switch (c.getType()) {
                case "package_declaration" -> f.pkg = c.getNamedChildren().stream()
                        .filter(x -> x.getType().contains("identifier")).findFirst().map(JavaIndex::text).orElse("");
                case "import_declaration" -> {
                    String t = text(c).replaceFirst("^import\\s+", "").replaceFirst(";\\s*$", "").trim();
                    boolean isStatic = t.startsWith("static ");
                    if (isStatic) t = t.substring(7).trim();
                    if (t.endsWith(".*")) {
                        if (!isStatic) f.wildcards.add(t.substring(0, t.length() - 2));
                    } else if (!isStatic) f.imports.put(t.substring(t.lastIndexOf('.') + 1), t);
                }
                default -> { }
            }
        }
        collectTypes(root, f, null);
        return f;
    }

    private void collectTypes(Node parent, FileCtx f, ClassInfo outer) {
        for (Node c : parent.getNamedChildren()) {
            String t = c.getType();
            if (t.equals("ERROR")) {                              // 오류 노드 안에서도 선언을 찾는다 (FR-IN-01)
                collectTypes(c, f, outer);
                continue;
            }
            if (isTypeDecl(t)) collectType(c, f, outer);
        }
    }

    private void collectType(Node c, FileCtx f, ClassInfo outer) {
        String t = c.getType();
        {
            ClassInfo ci = new ClassInfo();
            ci.simple = c.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
            ci.outer = outer == null ? null : outer.fqn;
            ci.fqn = outer != null ? outer.fqn + "." + ci.simple : (f.pkg.isEmpty() ? ci.simple : f.pkg + "." + ci.simple);
            ci.pkg = f.pkg;
            ci.kind = t.replace("_declaration", "");
            ci.file = f;
            c.getChildByFieldName("superclass").ifPresent(s -> ci.superRaw = erasure(s.getNamedChildren().getFirst()));
            for (Node x : c.getNamedChildren()) {
                if (x.getType().equals("super_interfaces") || x.getType().equals("extends_interfaces")) {
                    for (Node tl : x.getNamedChildren())
                        for (Node ty : tl.getNamedChildren()) ci.interfacesRaw.add(erasure(ty));
                }
            }
            classes.put(ci.fqn, ci);
            bySimple.computeIfAbsent(ci.simple, k -> new ArrayList<>()).add(ci);
            f.classes.add(ci.fqn);
            Optional<Node> body = c.getChildByFieldName("body");
            if (body.isEmpty()) return;
            if (t.equals("record_declaration")) {
                c.getChildByFieldName("parameters").ifPresent(ps -> {
                    for (Node p : ps.getNamedChildren()) {
                        if (!p.getType().equals("formal_parameter")) continue;
                        FieldInfo fi = new FieldInfo();
                        fi.name = p.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
                        fi.type = p.getChildByFieldName("type").map(JavaIndex::erasure).orElse("?");
                        ci.fields.put(fi.name, fi);
                    }
                });
            }
            members(body.get(), f, ci);
        }
    }

    static boolean isTypeDecl(String t) {
        return t.equals("class_declaration") || t.equals("interface_declaration") || t.equals("enum_declaration")
                || t.equals("record_declaration");
    }

    private void members(Node body, FileCtx f, ClassInfo ci) {
        for (Node m : body.getNamedChildren()) {
            switch (m.getType()) {
                case "enum_body_declarations", "ERROR" -> members(m, f, ci);
                case "field_declaration", "constant_declaration" -> {
                    String type = m.getChildByFieldName("type").map(JavaIndex::erasure).orElse("?");
                    boolean st = hasModifier(m, "static") || ci.kind.equals("interface");
                    boolean fin = hasModifier(m, "final") || ci.kind.equals("interface");
                    for (Node d : m.getChildrenByFieldName("declarator")) {
                        FieldInfo fi = new FieldInfo();
                        fi.name = d.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
                        fi.type = type;
                        fi.isStatic = st;
                        if (st && fin) d.getChildByFieldName("value").ifPresent(v -> fi.constant = constString(v));
                        ci.fields.put(fi.name, fi);
                    }
                }
                case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> {
                    MethodInfo mi = new MethodInfo();
                    mi.cls = ci.fqn;
                    mi.start = m.getStartByte();
                    mi.isConstructor = !m.getType().equals("method_declaration");
                    mi.name = mi.isConstructor ? "<init>" : m.getChildByFieldName("name").map(JavaIndex::text).orElse("?");
                    mi.returnType = mi.isConstructor ? ci.fqn : m.getChildByFieldName("type").map(JavaIndex::erasure).orElse("void");
                    m.getChildByFieldName("parameters").ifPresent(ps -> {
                        for (Node p : ps.getNamedChildren()) {
                            if (p.getType().equals("formal_parameter")) {
                                mi.paramTypes.add(p.getChildByFieldName("type").map(JavaIndex::erasure).orElse("?"));
                                mi.paramNames.add(p.getChildByFieldName("name").map(JavaIndex::text).orElse("?"));
                            } else if (p.getType().equals("spread_parameter")) {
                                Node ty = p.getNamedChildren().stream().filter(x -> !x.getType().equals("modifiers")).findFirst().orElse(p);
                                mi.paramTypes.add(erasure(ty) + "[]");
                                String n = p.getNamedChildren().stream().filter(x -> x.getType().equals("variable_declarator"))
                                        .findFirst().flatMap(x -> x.getChildByFieldName("name")).map(JavaIndex::text).orElse("?");
                                mi.paramNames.add(n);
                            }
                        }
                    });
                    mi.isStatic = hasModifier(m, "static");
                    mi.isAbstract = m.getChildByFieldName("body").isEmpty() && !mi.isConstructor;
                    ci.methods.add(mi);
                    methodNames.computeIfAbsent(mi.name, k -> new LinkedHashSet<>()).add(ci.fqn);
                }
                default -> {
                    if (isTypeDecl(m.getType())) collectType(m, f, ci);   // 중첩 타입
                }
            }
        }
    }

    /** 모든 파일 수집 후: 상위 타입 해석, 시그니처 확정, 구현 관계 색인. */
    public void link() {
        for (ClassInfo ci : classes.values()) {
            ci.superFqn = ci.superRaw == null ? null : resolve(ci.superRaw, ci);
            ci.interfaces.clear();
            for (String i : ci.interfacesRaw) ci.interfaces.add(resolve(i, ci));
            for (MethodInfo m : ci.methods) {
                List<String> rt = new ArrayList<>();
                for (String p : m.paramTypes) rt.add(resolve(p, ci));
                m.paramTypes = rt;
                m.returnType = resolve(m.returnType, ci);
                m.signature = ci.fqn + "#" + m.name + "(" + String.join(",", rt) + ")";
            }
            for (FieldInfo f : ci.fields.values()) f.type = resolve(f.type, ci);
        }
        for (ClassInfo ci : classes.values()) for (String sup : supertypes(ci.fqn))
            if (!sup.equals(ci.fqn)) implementors.computeIfAbsent(sup, k -> new ArrayList<>()).add(ci.fqn);
    }

    // ───────────────────────────── 조회 ─────────────────────────────

    public boolean isProject(String fqn) {
        return fqn != null && classes.containsKey(fqn);
    }

    public ClassInfo cls(String fqn) {
        return fqn == null ? null : classes.get(fqn);
    }

    /** 자기 자신 포함 모든 상위 타입 (프로젝트 안에서). */
    public List<String> supertypes(String fqn) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> work = new ArrayList<>(List.of(fqn));
        while (!work.isEmpty()) {
            String c = work.removeLast();
            if (!seen.add(c)) continue;
            out.add(c);
            ClassInfo ci = classes.get(c);
            if (ci == null) continue;
            if (ci.superFqn != null) work.add(ci.superFqn);
            work.addAll(ci.interfaces);
        }
        return out;
    }

    public List<String> implementors(String fqn) {
        return implementors.getOrDefault(fqn, List.of());
    }

    /** 이름·인자 수로 메서드 찾기 (상위 타입 포함). */
    public List<MethodInfo> findMethods(String cls, String name, int arity) {
        List<MethodInfo> out = new ArrayList<>();
        for (String c : supertypes(cls)) {
            ClassInfo ci = classes.get(c);
            if (ci == null) continue;
            for (MethodInfo m : ci.methods)
                if (m.name.equals(name) && (m.paramTypes.size() == arity
                        || (!m.paramTypes.isEmpty() && m.paramTypes.getLast().endsWith("[]") && arity >= m.paramTypes.size() - 1)))
                    out.add(m);
            if (!out.isEmpty()) break;
        }
        return out;
    }

    public boolean methodNameExists(String name) {
        return methodNames.containsKey(name);
    }

    public Set<String> classesWithMethod(String name) {
        return methodNames.getOrDefault(name, Set.of());
    }

    /** 필드 선언 위치 (상위 클래스 포함). */
    public FieldInfo field(String cls, String name, String[] declaringOut) {
        for (String c : supertypes(cls)) {
            ClassInfo ci = classes.get(c);
            if (ci != null && ci.fields.containsKey(name)) {
                if (declaringOut != null) declaringOut[0] = c;
                return ci.fields.get(name);
            }
        }
        return null;
    }

    /** 단순/한정 이름 → FQN (import, 같은 패키지, 중첩, wildcard, java.lang 순). 해석 실패 시 원래 이름. */
    public String resolve(String raw, ClassInfo ctx) {
        if (raw == null) return null;
        if (raw.endsWith("[]")) return resolve(raw.substring(0, raw.length() - 2), ctx) + "[]";
        if (PRIMITIVES.contains(raw) || raw.equals("?")) return raw;
        if (classes.containsKey(raw)) return raw;
        String first = raw.contains(".") ? raw.substring(0, raw.indexOf('.')) : raw;
        String rest = raw.contains(".") ? raw.substring(raw.indexOf('.')) : "";
        for (ClassInfo c = ctx; c != null; c = c.outer == null ? null : classes.get(c.outer)) {
            String nested = c.fqn + "." + first;
            if (classes.containsKey(nested)) return nested + rest;
            if (c.simple.equals(first)) return c.fqn + rest;
        }
        FileCtx f = ctx == null ? null : ctx.file;
        if (f != null) {
            if (f.imports.containsKey(first)) return f.imports.get(first) + rest;
            String same = f.pkg.isEmpty() ? first : f.pkg + "." + first;
            if (classes.containsKey(same)) return same + rest;
            for (String w : f.wildcards) if (classes.containsKey(w + "." + first)) return w + "." + first + rest;
        }
        if (JAVA_LANG.contains(first)) return "java.lang." + raw;
        if (raw.contains(".")) return raw;
        List<ClassInfo> cands = bySimple.getOrDefault(raw, List.of());
        if (cands.size() == 1 && f != null && f.wildcards.stream().anyMatch(w -> cands.getFirst().fqn.startsWith(w)))
            return cands.getFirst().fqn;
        return raw;
    }

    // ───────────────────────────── CST 유틸 ─────────────────────────────

    public static String text(Node n) {
        String t = n.getText();
        return t == null ? "" : t;
    }

    /** 타입 노드 → 이레이저 문자열 (제네릭 제거). */
    public static String erasure(Node t) {
        return switch (t.getType()) {
            case "generic_type" -> erasure(t.getNamedChildren().getFirst());
            case "array_type" -> t.getChildByFieldName("element").map(JavaIndex::erasure).orElse("?") + "[]";
            case "scoped_type_identifier" -> text(t).replaceAll("<.*?>", "").replaceAll("\\s+", "");
            case "annotated_type" -> erasure(t.getNamedChildren().getLast());
            default -> text(t).replaceAll("<.*", "").trim();
        };
    }

    public static boolean hasModifier(Node decl, String mod) {
        for (Node c : decl.getChildren())
            if (c.getType().equals("modifiers"))
                for (Node k : c.getChildren()) if (text(k).equals(mod)) return true;
        return false;
    }

    /** 문자열 상수식 (리터럴, 리터럴 + 연결, 텍스트 블록). 아니면 null. */
    public static String constString(Node v) {
        switch (v.getType()) {
            case "string_literal" -> {
                String t = text(v);
                if (t.startsWith("\"\"\"")) return t.substring(3, t.length() - 3).stripIndent();
                return t.substring(1, t.length() - 1).replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");
            }
            case "parenthesized_expression" -> {
                return constString(v.getNamedChildren().getFirst());
            }
            case "binary_expression" -> {
                Node l = v.getChildByFieldName("left").orElse(null), r = v.getChildByFieldName("right").orElse(null);
                String op = v.getChildByFieldName("operator").map(JavaIndex::text).orElse("");
                if (l == null || r == null || !op.equals("+")) return null;
                String a = constString(l), b = constString(r);
                return a == null || b == null ? null : a + b;
            }
            default -> {
                return null;
            }
        }
    }
}
