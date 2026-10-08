package com.ids.qtrack.next.rules;

import io.github.treesitter.jtreesitter.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 규칙 emit 속성값의 작은 식 언어. 엔진 코드를 고치지 않고 YAML만으로 새 규약을 표현하기 위한 것입니다 (FR-IN-08).
 * <pre>
 * expr   := call | 'string' | "string" | @capture | word
 * call   := name '(' [arg {',' arg}] ')'
 * arg    := word '=' expr | expr
 * </pre>
 * 함수: text, name, annValue, annAttr, annOf, classAttr, lookup, coalesce, concatPath, upper, lower, simpleName
 */
public sealed interface Expr {
    record Lit(String value) implements Expr {}

    record Cap(String name) implements Expr {}

    record Call(String fn, List<Expr> args, List<String> keys) implements Expr {}

    /** 평가 문맥. */
    interface Ctx {
        Optional<Node> capture(String name);

        String classAttr(Node anyNodeInClass, String key);
    }

    static Expr parse(String s) {
        P p = new P(s);
        Expr e = p.expr();
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("식 해석 실패: " + s + " @" + p.i);
        return e;
    }

    final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Expr expr() {
            ws();
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                int j = s.indexOf(c, i + 1);
                String v = s.substring(i + 1, j);
                i = j + 1;
                return new Lit(v);
            }
            if (c == '@') {
                int j = ++i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '.')) j++;
                String n = s.substring(i, j);
                i = j;
                return new Cap(n);
            }
            int j = i;
            while (j < s.length() && "(),= ".indexOf(s.charAt(j)) < 0) j++;
            String w = s.substring(i, j);
            i = j;
            ws();
            if (i < s.length() && s.charAt(i) == '(') {
                i++;
                List<Expr> args = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                ws();
                if (s.charAt(i) == ')') {
                    i++;
                    return new Call(w, args, keys);
                }
                while (true) {
                    ws();
                    int save = i;
                    int k = i;
                    while (k < s.length() && (Character.isLetterOrDigit(s.charAt(k)) || s.charAt(k) == '_')) k++;
                    int k2 = k;
                    while (k2 < s.length() && s.charAt(k2) == ' ') k2++;
                    if (k > i && k2 < s.length() && s.charAt(k2) == '=') {
                        keys.add(s.substring(i, k));
                        i = k2 + 1;
                    } else {
                        keys.add(null);
                        i = save;
                    }
                    args.add(expr());
                    ws();
                    char d = s.charAt(i++);
                    if (d == ')') return new Call(w, args, keys);
                }
            }
            return new Lit(w);
        }
    }

    static String eval(Expr e, Ctx ctx) {
        return switch (e) {
            case Lit l -> l.value;
            case Cap c -> ctx.capture(c.name).map(Expr::text).orElse("");
            case Call c -> call(c, ctx);
        };
    }

    private static Node node(Expr e, Ctx ctx) {
        return e instanceof Cap c ? ctx.capture(c.name).orElse(null) : null;
    }

    private static String call(Call c, Ctx ctx) {
        List<Expr> a = c.args;
        return switch (c.fn) {
            case "text" -> eval(a.getFirst(), ctx);
            case "name" -> {
                Node n = node(a.getFirst(), ctx);
                yield n == null ? "" : n.getChildByFieldName("name").map(Expr::text).orElse("");
            }
            case "simpleName" -> {
                String v = eval(a.getFirst(), ctx);
                int k = v.lastIndexOf('.');
                yield k >= 0 ? v.substring(k + 1) : v;
            }
            case "annValue" -> annValue(node(a.getFirst(), ctx), "value", "path");
            case "annAttr" -> annValue(node(a.getFirst(), ctx), eval(a.get(1), ctx));
            case "annText" -> {                                    // 배열 값이면 모든 원소를 공백으로 이어 붙임
                Node ann = node(a.getFirst(), ctx);
                Optional<Node> args = ann == null ? Optional.empty() : ann.getChildByFieldName("arguments");
                StringBuilder sb = new StringBuilder();
                for (Node arg : args.map(Node::getNamedChildren).orElse(List.of())) {
                    boolean pair = arg.getType().equals("element_value_pair");
                    Node v = pair ? arg.getChildByFieldName("value").orElse(null) : arg;
                    if (pair && !arg.getChildByFieldName("key").map(Expr::text).orElse("").equals("value")) continue;
                    if (v != null && v.getType().endsWith("array_initializer"))
                        for (Node item : v.getNamedChildren()) sb.append(literal(item)).append(' ');
                    else sb.append(literal(v)).append(' ');
                }
                yield sb.toString().trim();
            }
            case "annOf" -> {
                Node n = node(a.getFirst(), ctx);
                String want = eval(a.get(1), ctx);
                Node ann = n == null ? null : findAnnotation(n, want);
                yield ann == null ? "" : a.size() > 2 ? annValue(ann, eval(a.get(2), ctx)) : annValue(ann, "value", "path");
            }
            case "classAttr" -> {
                Node n = node(a.getFirst(), ctx);
                yield n == null ? "" : nullToEmpty(ctx.classAttr(n, eval(a.get(1), ctx)));
            }
            case "lookup" -> {
                String key = eval(a.getFirst(), ctx);
                String def = "";
                for (int i = 1; i < a.size(); i++) {
                    String k = c.keys.get(i);
                    if (k == null) def = eval(a.get(i), ctx);
                    else if (k.equals(key)) yield eval(a.get(i), ctx);
                }
                yield def;
            }
            case "coalesce" -> {
                for (Expr x : a) {
                    String v = eval(x, ctx);
                    if (!v.isEmpty()) yield v;
                }
                yield "";
            }
            case "concatPath" -> {
                StringBuilder sb = new StringBuilder();
                for (Expr x : a) {
                    String v = eval(x, ctx).trim();
                    if (v.isEmpty()) continue;
                    if (!v.startsWith("/")) sb.append('/');
                    sb.append(v.endsWith("/") && v.length() > 1 ? v.substring(0, v.length() - 1) : v);
                }
                String r = sb.toString().replaceAll("/+", "/");
                yield r.isEmpty() ? "/" : r;
            }
            case "upper" -> eval(a.getFirst(), ctx).toUpperCase(Locale.ROOT);
            case "lower" -> eval(a.getFirst(), ctx).toLowerCase(Locale.ROOT);
            case "decap" -> {
                String v = eval(a.getFirst(), ctx);
                yield v.isEmpty() ? v : Character.toLowerCase(v.charAt(0)) + v.substring(1);
            }
            default -> throw new IllegalArgumentException("알 수 없는 함수: " + c.fn);
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    static String text(Node n) {
        String t = n.getText();
        return t == null ? "" : t;
    }

    /** modifiers 안에서 이름이 want인 어노테이션. */
    static Node findAnnotation(Node decl, String want) {
        for (Node c : decl.getChildren()) {
            if (!c.getType().equals("modifiers")) continue;
            for (Node a : c.getNamedChildren()) {
                if (!a.getType().endsWith("annotation")) continue;
                String n = a.getChildByFieldName("name").map(Expr::text).orElse("");
                if (n.equals(want) || n.endsWith("." + want)) return a;
            }
        }
        return null;
    }

    /** 어노테이션 요소 값. 단일 값 형식 {@code @X("v")}이면 첫 키 이름으로 봅니다. 배열은 첫 원소. */
    static String annValue(Node ann, String... keys) {
        if (ann == null) return "";
        Optional<Node> args = ann.getChildByFieldName("arguments");
        if (args.isEmpty()) return "";
        for (Node c : args.get().getNamedChildren()) {
            if (c.getType().equals("element_value_pair")) {
                String k = c.getChildByFieldName("key").map(Expr::text).orElse("");
                for (String want : keys)
                    if (k.equals(want)) return literal(c.getChildByFieldName("value").orElse(null));
            } else if (keys.length > 0 && (keys[0].equals("value") || keys[0].equals("path"))) {
                return literal(c);
            }
        }
        return "";
    }

    static String literal(Node v) {
        if (v == null) return "";
        switch (v.getType()) {
            case "string_literal" -> {
                String t = text(v);
                if (t.startsWith("\"\"\"")) return t.substring(3, t.length() - 3);
                return t.length() >= 2 ? t.substring(1, t.length() - 1) : t;
            }
            case "element_value_array_initializer", "array_initializer" -> {
                List<Node> items = v.getNamedChildren();
                return items.isEmpty() ? "" : literal(items.getFirst());
            }
            case "binary_expression" -> {                          // "a" + "b"
                StringBuilder sb = new StringBuilder();
                for (Node c : v.getNamedChildren()) sb.append(literal(c));
                return sb.toString();
            }
            case "field_access", "scoped_identifier" -> {          // RequestMethod.GET
                String t = text(v);
                return t.substring(t.lastIndexOf('.') + 1);
            }
            default -> {
                return text(v);
            }
        }
    }

    static Map<String, String> none() {
        return Map.of();
    }
}
