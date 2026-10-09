package com.ids.qtrack.next.java;

import com.ids.qtrack.next.ir.BranchLabel;
import com.ids.qtrack.next.ir.CallKind;
import com.ids.qtrack.next.ir.CallSite;
import com.ids.qtrack.next.ir.Confidence;
import com.ids.qtrack.next.ir.Edge;
import com.ids.qtrack.next.ir.EdgeKind;
import com.ids.qtrack.next.ir.ExternalRef;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.NodeKind;
import com.ids.qtrack.next.ir.Span;
import com.ids.qtrack.next.java.JavaIndex.ClassInfo;
import com.ids.qtrack.next.java.JavaIndex.FieldInfo;
import com.ids.qtrack.next.java.JavaIndex.MethodInfo;
import com.ids.qtrack.next.rules.Facts;
import com.ids.qtrack.next.sql.PreparedSql;
import com.ids.qtrack.next.sql.SqlMethodBuilder.BindSlot;
import com.ids.qtrack.next.sql.SqlText;
import io.github.treesitter.jtreesitter.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 메서드 하나의 CST → SSA lowering (설계서 3.1) + 제어 의존 (설계서 3.2, FR-CF-01~06).
 * <ul>
 *   <li>CFG를 만들면서 SSA를 구성합니다 (Braun et al., "Simple and Efficient Construction of SSA Form").
 *       분기 합류점에는 φ, 자명한 φ는 제거.</li>
 *   <li>조건식마다 PREDICATE 노드 ({@code &&}/{@code ||}는 피연산자별, {@code else}는 없음),
 *       분기 간선에 라벨(TRUE/FALSE/CASE/DEFAULT/EXCEPTION).</li>
 *   <li>후지배 트리(Cooper–Harvey–Kennedy)로 제어 의존(Ferrante–Ottenstein–Warren)을 계산해
 *       분기에서 새로 정의되는 값마다 CONTROL 간선을 만듭니다. 바로 위 조건에만 연결됩니다.</li>
 * </ul>
 */
final class MethodLowering {
    // ───────────────────────────── 모델 ─────────────────────────────

    static final class Val {
        int id;
        NodeKind kind;
        String name;
        int start, end, line;
        Block block;
        boolean phi;
        String var;
        final List<Integer> ops = new ArrayList<>();
        int replaced = -2;                    // -2: 유효, -1: 미정의로 제거, ≥0: 다른 값으로 대체
        int ext;                              // 전역 노드 참조 (미사용)
    }

    static final class Block {
        final int id;
        final List<CEdge> succ = new ArrayList<>();
        final List<Block> preds = new ArrayList<>();
        boolean sealed, terminated;
        final Map<String, Integer> defs = new HashMap<>();
        final Map<String, Integer> incomplete = new LinkedHashMap<>();
        /** 이 블록을 만든 제어문 (φ의 위치·이름에 씀). 없으면 null. */
        Node origin;
        String originKind;

        Block(int id) {
            this.id = id;
        }
    }

    /** 지금 lowering 중인 제어문 (안쪽이 위). newBlock()이 블록의 출처로 기록합니다. */
    private record Origin(Node node, String kind) {}

    private final Deque<Origin> origins = new ArrayDeque<>();

    private void withinS(Node n, String kind, Runnable f) {
        origins.push(new Origin(n, kind));
        try {
            f.run();
        } finally {
            origins.pop();
        }
    }

    private R withinE(Node n, String kind, java.util.function.Supplier<R> f) {
        origins.push(new Origin(n, kind));
        try {
            return f.get();
        } finally {
            origins.pop();
        }
    }

    /** CFG 간선. label < 0 이면 무조건 간선. pred = 조건 노드 값 id. */
    record CEdge(Block to, int label, String caseVal, int pred) {}

    /** 식 결과: 이 식의 값이 흘러나오는 값 노드들과 추정 타입. */
    record R(List<Integer> vals, String type) {
        static final R EMPTY = new R(List.of(), null);

        static R of(int v, String type) {
            return new R(List.of(v), type);
        }

        R plus(R o) {
            Set<Integer> s = new LinkedHashSet<>(vals);
            s.addAll(o.vals);
            return new R(new ArrayList<>(s), type != null ? type : o.type);
        }
    }

    record RetSite(Block block, List<Integer> vals, Node node) {}

    record Cd(int pred, int label, String caseVal) {}

    static final class JumpCtx {
        String label;
        Block breakTo, continueTo;
        boolean isSwitch;
        List<Integer> results;                // switch 식 / 람다 결과

        JumpCtx(String label, Block breakTo, Block continueTo, boolean isSwitch) {
            this.label = label;
            this.breakTo = breakTo;
            this.continueTo = continueTo;
            this.isSwitch = isSwitch;
        }
    }

    // ───────────────────────────── 상태 ─────────────────────────────

    private final JavaIndex idx;
    private final ClassInfo cls;
    private final MethodInfo info;
    private final Node decl;
    private final Facts facts;
    private final int fileId;
    private final LibrarySummaries lib;
    private final List<PreparedSql> sqlOut;
    private final List<String> diags;

    private final List<Val> vals = new ArrayList<>();
    private final List<int[]> defUse = new ArrayList<>();          // src, dst, conf
    private final List<int[]> stores = new ArrayList<>();          // val, ext, conf
    private final List<int[]> loads = new ArrayList<>();           // ext, val, conf
    private final Map<String, Integer> extIndex = new LinkedHashMap<>();
    private final List<ExternalRef> externals = new ArrayList<>();
    private final List<CallSite.Builder> calls = new ArrayList<>();
    private final List<Block> blocks = new ArrayList<>();
    private final List<RetSite> returns = new ArrayList<>();
    private final Map<String, String> localTypes = new HashMap<>();
    private final Map<String, String> consts = new HashMap<>();
    private final Map<String, Integer> versions = new HashMap<>();
    private final Map<String, Integer> fieldLoads = new HashMap<>();
    private final Deque<JumpCtx> jumps = new ArrayDeque<>();
    private final Deque<JumpCtx> lambdas = new ArrayDeque<>();
    private final List<Integer> formalIns = new ArrayList<>();
    private Block cur, exit;
    private int formalOut = -1;
    private int thisVal = -1;
    private int tryCount, sqlCount;
    private String pendingLabel;
    int errorNodes;

    MethodLowering(JavaIndex idx, ClassInfo cls, MethodInfo info, Node decl, Facts facts, int fileId,
                   LibrarySummaries lib, List<PreparedSql> sqlOut, List<String> diags) {
        this.idx = idx;
        this.cls = cls;
        this.info = info;
        this.decl = decl;
        this.facts = facts;
        this.fileId = fileId;
        this.lib = lib;
        this.sqlOut = sqlOut;
        this.diags = diags;
    }

    // ───────────────────────────── 진입점 ─────────────────────────────

    MethodIR lower() {
        Block entry = newBlock();
        entry.sealed = true;
        exit = newBlock();
        cur = entry;
        List<Node> params = new ArrayList<>();
        decl.getChildByFieldName("parameters").ifPresent(ps -> {
            for (Node p : ps.getNamedChildren())
                if (p.getType().equals("formal_parameter") || p.getType().equals("spread_parameter")) params.add(p);
        });
        // 설계서 5.2 배치 규칙: 인스턴스 메서드는 this가 FORMAL_IN 0번 (생성자는 this 없음 — 결과 객체가 ACTUAL_OUT)
        if (hasThis()) {
            thisVal = newVal(NodeKind.FORMAL_IN, "this", decl.getChildByFieldName("name").orElse(decl));
            formalIns.add(thisVal);
        }
        for (int i = 0; i < params.size(); i++) {
            Node p = params.get(i);
            String name = i < info.paramNames.size() ? info.paramNames.get(i) : "p" + i;
            int v = newVal(NodeKind.FORMAL_IN, name, p);
            formalIns.add(v);
            localTypes.put(name, i < info.paramTypes.size() ? info.paramTypes.get(i) : null);
            write(name, entry, v);
        }
        Optional<Node> body = decl.getChildByFieldName("body");
        if (body.isPresent()) {
            if (!"void".equals(info.returnType) && !info.isConstructor) ensureFormalOut();
            stmt(body.get());
            jump(cur, exit);
        } else if (!"void".equals(info.returnType) && !info.isConstructor) {
            ensureFormalOut();                       // 추상 메서드: 인터페이스 노드만
        }
        for (Block b : blocks) if (!b.sealed) seal(b);
        removeTrivialPhis();
        Map<Block, List<Cd>> cd = body.isPresent() ? controlDependence() : Map.of();
        for (RetSite r : returns) {
            if (formalOut < 0) break;
            if (r.vals.isEmpty()) continue;                      // 상수 반환: 흐름 없음
            List<Cd> c = cd.getOrDefault(r.block, List.of());
            if (c.isEmpty()) {
                for (int v : r.vals) du(v, formalOut, 0);
            } else {                                   // 조건부 반환: FORMAL_OUT 기여 값 노드
                int rv = newVal(NodeKind.LOCAL, "return@L" + (r.node.getStartPoint().row() + 1), r.node);
                vals.get(rv).block = r.block;
                for (int v : r.vals) du(v, rv, 0);
                du(rv, formalOut, 0);
            }
        }
        return emit(cd);
    }

    boolean hasThis() {
        return !info.isStatic && !info.isConstructor;
    }

    private void ensureFormalOut() {
        if (formalOut < 0) formalOut = newVal(NodeKind.FORMAL_OUT, "반환", decl.getChildByFieldName("type").orElse(decl));
    }

    // ───────────────────────────── 값·블록 기본 연산 ─────────────────────────────

    private Block newBlock() {
        Block b = new Block(blocks.size());
        if (!origins.isEmpty()) {
            b.origin = origins.peek().node();
            b.originKind = origins.peek().kind();
        }
        blocks.add(b);
        return b;
    }

    private Block deadBlock() {
        Block b = newBlock();
        b.sealed = true;
        return b;
    }

    private int newVal(NodeKind kind, String name, Node at) {
        Val v = new Val();
        v.id = vals.size();
        v.kind = kind;
        v.name = name.length() > 120 ? name.substring(0, 117) + "..." : name;
        v.block = cur;
        if (at != null) {
            v.start = at.getStartByte();
            v.end = at.getEndByte();
            v.line = at.getStartPoint().row() + 1;
        }
        vals.add(v);
        return v.id;
    }

    private void du(int src, int dst, int conf) {
        if (src >= 0 && dst >= 0 && src != dst) defUse.add(new int[]{src, dst, conf});
    }

    private void dus(List<Integer> srcs, int dst, int conf) {
        for (int s : srcs) du(s, dst, conf);
    }

    private void jump(Block from, Block to) {
        if (from.terminated) return;
        from.succ.add(new CEdge(to, -1, null, -1));
        to.preds.add(from);
        from.terminated = true;
    }

    private void branch(Block from, Block to, int label, String caseVal, int pred) {
        from.succ.add(new CEdge(to, label, caseVal, pred));
        to.preds.add(from);
    }

    // SSA (Braun et al.)
    private void write(String var, Block b, int v) {
        b.defs.put(var, v);
    }

    private int read(String var, Block b) {
        Integer v = b.defs.get(var);
        if (v != null) return v;
        return readRecursive(var, b);
    }

    private int readRecursive(String var, Block b) {
        int v;
        if (!b.sealed) {
            v = newPhi(var, b);
            b.incomplete.put(var, v);
        } else if (b.preds.size() == 1) {
            v = read(var, b.preds.getFirst());
        } else if (b.preds.isEmpty()) {
            v = -1;
        } else {
            v = newPhi(var, b);
            write(var, b, v);
            addPhiOperands(var, v);
        }
        write(var, b, v);
        return v;
    }

    private int newPhi(String var, Block b) {
        Block save = cur;
        cur = b;
        // φ는 합류를 만든 제어문의 위치를 갖습니다 (예: "grade#4 (φ: L15 if 합류)")
        String label = b.origin == null ? " (φ)"
                : " (φ: L" + (b.origin.getStartPoint().row() + 1) + " " + b.originKind + ")";
        int v = newVal(NodeKind.LOCAL, nextVersion(var) + label, b.origin);
        cur = save;
        Val pv = vals.get(v);
        pv.phi = true;
        pv.var = var;
        return v;
    }

    private void addPhiOperands(String var, int phi) {
        Val p = vals.get(phi);
        for (Block pred : new ArrayList<>(p.block.preds)) {
            int op = read(var, pred);
            if (op >= 0 && !p.ops.contains(op)) p.ops.add(op);
        }
    }

    private void seal(Block b) {
        if (b.sealed) return;
        b.sealed = true;
        for (var e : b.incomplete.entrySet()) addPhiOperands(e.getKey(), e.getValue());
        b.incomplete.clear();
    }

    private String nextVersion(String var) {
        int k = versions.merge(var, 1, Integer::sum);
        return var + "#" + k;
    }

    private int defineVar(String var, List<Integer> srcs, Node at, int conf, String suffix) {
        int v = newVal(NodeKind.LOCAL, nextVersion(var) + suffix, at);
        dus(srcs, v, conf);
        write(var, cur, v);
        return v;
    }

    private int find(int v) {
        while (v >= 0 && vals.get(v).replaced >= 0) v = vals.get(v).replaced;
        if (v >= 0 && vals.get(v).replaced == -1) return -1;
        return v;
    }

    private void removeTrivialPhis() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Val p : vals) {
                if (!p.phi || p.replaced != -2 || p.var == null) continue;   // 값 φ(삼항·switch 결과)는 제외
                Set<Integer> ops = new LinkedHashSet<>();
                for (int o : p.ops) {
                    int f = find(o);
                    if (f >= 0 && f != p.id) ops.add(f);
                }
                if (ops.size() <= 1) {
                    p.replaced = ops.isEmpty() ? -1 : ops.iterator().next();
                    changed = true;
                }
            }
        }
    }

    private int ext(String key, ExternalRef ref) {
        return extIndex.computeIfAbsent(key, k -> {
            externals.add(ref);
            return externals.size();
        });
    }

    private int fieldExt(String field) {
        return ext("F:" + field, ExternalRef.newBuilder().setField(field).build());
    }

    private Span span(Node n) {
        return Span.newBuilder().setFileId(fileId).setStart(n.getStartByte()).setEnd(n.getEndByte())
                .setLine(n.getStartPoint().row() + 1).build();
    }

    /**
     * 메서드 선언 위치: 시작은 메서드 이름(어노테이션 줄이 아니라 시그니처 줄), 끝은 선언 끝.
     * 위치가 없는 노드의 대체 위치로도 쓰입니다.
     */
    static Span declSpan(Node decl, int fileId) {
        Node name = decl.getChildByFieldName("name").orElse(decl);
        return Span.newBuilder().setFileId(fileId).setStart(name.getStartByte()).setEnd(decl.getEndByte())
                .setLine(name.getStartPoint().row() + 1).build();
    }

    private static String text(Node n) {
        return JavaIndex.text(n);
    }

    private static String compact(Node n) {
        String t = text(n).replaceAll("\\s+", " ").trim();
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    private static Node unparen(Node n) {
        while (n != null && n.getType().equals("parenthesized_expression") && !n.getNamedChildren().isEmpty())
            n = n.getNamedChildren().getFirst();
        return n;
    }

    // ───────────────────────────── 문장 ─────────────────────────────

    private void stmt(Node s) {
        if (s == null) return;
        if (s.isError() || s.isMissing()) {                  // FR-IN-01: 오류 노드만 건너뜀
            errorNodes++;
            return;
        }
        String label = pendingLabel;
        pendingLabel = null;
        switch (s.getType()) {
            case "block", "constructor_body" -> {
                for (Node c : s.getNamedChildren()) stmt(c);
            }
            case "local_variable_declaration" -> localDecl(s);
            case "expression_statement" -> {
                for (Node c : s.getNamedChildren()) expr(c);
            }
            case "if_statement" -> withinS(s, "if 합류", () -> ifStmt(s));
            case "while_statement" -> withinS(s, "while 반복", () -> whileStmt(s, label));
            case "for_statement" -> withinS(s, "for 반복", () -> forStmt(s, label));
            case "enhanced_for_statement" -> withinS(s, "for 반복", () -> forEachStmt(s, label));
            case "do_statement" -> withinS(s, "do 반복", () -> doStmt(s, label));
            case "switch_expression" -> withinE(s, "switch 합류", () -> switchExpr(s, false, label));
            case "return_statement" -> returnStmt(s);
            case "break_statement" -> breakStmt(s);
            case "continue_statement" -> continueStmt(s);
            case "yield_statement" -> yieldStmt(s);
            case "throw_statement" -> {
                for (Node c : s.getNamedChildren()) expr(c);
                jump(cur, exit);
                cur = deadBlock();
            }
            case "try_statement", "try_with_resources_statement" -> withinS(s, "try 합류", () -> tryStmt(s));
            case "labeled_statement" -> {
                List<Node> ch = s.getNamedChildren();
                pendingLabel = text(ch.getFirst());
                stmt(ch.getLast());
            }
            case "synchronized_statement" -> {
                for (Node c : s.getNamedChildren()) {
                    if (c.getType().equals("block")) stmt(c);
                    else expr(c);
                }
            }
            case "explicit_constructor_invocation" -> ctorInvocation(s);
            case "assert_statement" -> {
                for (Node c : s.getNamedChildren()) expr(c);
            }
            case "class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
                 "local_class_declaration", "line_comment", "block_comment" -> { }
            default -> {
                if (s.isNamed()) expr(s);
            }
        }
    }

    private void localDecl(Node s) {
        String type = s.getChildByFieldName("type").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(null);
        for (Node d : s.getChildrenByFieldName("declarator")) {
            String name = d.getChildByFieldName("name").map(MethodLowering::text).orElse("?");
            localTypes.put(name, type);
            Optional<Node> value = d.getChildByFieldName("value");
            if (value.isEmpty()) continue;
            String c = constOf(value.get());
            if (c != null) consts.put(name, c);
            else consts.remove(name);
            R r = expr(value.get());
            if ("var".equals(type) || type == null) localTypes.put(name, r.type);
            defineVar(name, r.vals, d, 0, "");
        }
    }

    private void ifStmt(Node s) {
        Node c = unparen(s.getChildByFieldName("condition").orElse(null));
        Block tb = newBlock(), fb = newBlock(), join = newBlock();
        cond(c, tb, fb);
        seal(tb);
        seal(fb);
        cur = tb;
        stmt(s.getChildByFieldName("consequence").orElse(null));
        jump(cur, join);
        cur = fb;
        s.getChildByFieldName("alternative").ifPresent(this::stmt);
        jump(cur, join);
        seal(join);
        cur = join;
    }

    /** 조건 분기. {@code &&}/{@code ||}는 피연산자마다 조건 노드를 만듭니다 (단락 평가, FR-CF-05). */
    private void cond(Node c, Block t, Block f) {
        c = unparen(c);
        if (c != null && c.getType().equals("binary_expression")) {
            String op = c.getChildByFieldName("operator").map(MethodLowering::text).orElse("");
            if (op.equals("&&") || op.equals("||")) {
                Block mid = newBlock();
                if (op.equals("&&")) cond(c.getChildByFieldName("left").orElse(null), mid, f);
                else cond(c.getChildByFieldName("left").orElse(null), t, mid);
                seal(mid);
                cur = mid;
                cond(c.getChildByFieldName("right").orElse(null), t, f);
                return;
            }
        }
        R r = c == null ? R.EMPTY : expr(c);
        int p = newVal(NodeKind.PREDICATE, c == null ? "true" : compact(c), c);
        dus(r.vals, p, 0);
        branch(cur, t, BranchLabel.TRUE_VALUE, null, p);
        branch(cur, f, BranchLabel.FALSE_VALUE, null, p);
        cur.terminated = true;
    }

    private void whileStmt(Node s, String label) {
        Block h = newBlock();
        jump(cur, h);
        cur = h;
        Block body = newBlock(), out = newBlock();
        cond(s.getChildByFieldName("condition").orElse(null), body, out);
        seal(body);
        jumps.push(new JumpCtx(label, out, h, false));
        cur = body;
        stmt(s.getChildByFieldName("body").orElse(null));
        jump(cur, h);
        jumps.pop();
        seal(h);
        seal(out);
        cur = out;
    }

    private void doStmt(Node s, String label) {
        Block body = newBlock(), condB = newBlock(), out = newBlock();
        jump(cur, body);
        jumps.push(new JumpCtx(label, out, condB, false));
        cur = body;
        stmt(s.getChildByFieldName("body").orElse(null));
        jump(cur, condB);
        jumps.pop();
        seal(condB);
        cur = condB;
        cond(s.getChildByFieldName("condition").orElse(null), body, out);
        seal(body);
        seal(out);
        cur = out;
    }

    private void forStmt(Node s, String label) {
        for (Node init : s.getChildrenByFieldName("init")) {
            if (init.getType().equals("local_variable_declaration")) localDecl(init);
            else expr(init);
        }
        Block h = newBlock();
        jump(cur, h);
        cur = h;
        Block body = newBlock(), upd = newBlock(), out = newBlock();
        Optional<Node> c = s.getChildByFieldName("condition");
        if (c.isPresent()) cond(c.get(), body, out);
        else jump(cur, body);
        seal(body);
        jumps.push(new JumpCtx(label, out, upd, false));
        cur = body;
        stmt(s.getChildByFieldName("body").orElse(null));
        jump(cur, upd);
        jumps.pop();
        seal(upd);
        cur = upd;
        for (Node u : s.getChildrenByFieldName("update")) expr(u);
        jump(cur, h);
        seal(h);
        seal(out);
        cur = out;
    }

    private void forEachStmt(Node s, String label) {
        Node value = s.getChildByFieldName("value").orElse(null);
        R coll = expr(value);
        Block h = newBlock();
        jump(cur, h);
        cur = h;
        Block body = newBlock(), out = newBlock();
        String var = s.getChildByFieldName("name").map(MethodLowering::text).orElse("it");
        int p = newVal(NodeKind.PREDICATE, "for (" + var + " : " + (value == null ? "?" : compact(value)) + ")", value);
        dus(coll.vals, p, 0);
        branch(h, body, BranchLabel.TRUE_VALUE, null, p);
        branch(h, out, BranchLabel.FALSE_VALUE, null, p);
        h.terminated = true;
        seal(body);
        cur = body;
        localTypes.put(var, s.getChildByFieldName("type").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(null));
        defineVar(var, coll.vals, s.getChildByFieldName("name").orElse(s), 0, "");
        jumps.push(new JumpCtx(label, out, h, false));
        stmt(s.getChildByFieldName("body").orElse(null));
        jump(cur, h);
        jumps.pop();
        seal(h);
        seal(out);
        cur = out;
    }

    /** switch 문/식 (N갈래, fall-through 포함, FR-CF-05). */
    private R switchExpr(Node s, boolean isExpr, String label) {
        Node condNode = s.getChildByFieldName("condition").orElse(null);
        R subj = expr(unparen(condNode));
        int p = newVal(NodeKind.PREDICATE, "switch " + (condNode == null ? "?" : compact(condNode)), condNode);
        dus(subj.vals, p, 0);
        Block head = cur, out = newBlock();
        JumpCtx ctx = new JumpCtx(label, out, null, true);
        ctx.results = new ArrayList<>();
        jumps.push(ctx);
        boolean hasDefault = false;
        Block fall = null;
        Node body = s.getChildByFieldName("body").orElse(null);
        for (Node g : body == null ? List.<Node>of() : body.getNamedChildren()) {
            boolean rule = g.getType().equals("switch_rule");
            if (!rule && !g.getType().equals("switch_block_statement_group")) continue;
            Block gb = newBlock();
            for (Node l : g.getNamedChildren()) {
                if (!l.getType().equals("switch_label")) continue;
                if (text(l).startsWith("default")) {
                    branch(head, gb, BranchLabel.DEFAULT_VALUE, null, p);
                    hasDefault = true;
                } else {
                    List<Node> cases = l.getNamedChildren();
                    if (cases.isEmpty()) branch(head, gb, BranchLabel.CASE_VALUE, text(l), p);
                    for (Node cv : cases) branch(head, gb, BranchLabel.CASE_VALUE, compact(cv), p);
                }
            }
            if (!rule && fall != null) jump(fall, gb);               // fall-through
            seal(gb);
            cur = gb;
            for (Node c : g.getNamedChildren()) {
                if (c.getType().equals("switch_label")) continue;
                if (rule && isExpr && c.getType().equals("expression_statement")) {
                    for (Node e : c.getNamedChildren()) ctx.results.addAll(expr(e).vals);
                } else if (rule && isExpr && !c.getType().endsWith("statement") && !c.getType().equals("block")) {
                    ctx.results.addAll(expr(c).vals);
                } else stmt(c);
            }
            if (rule) {
                jump(cur, out);
                fall = null;
            } else fall = cur;
        }
        if (fall != null) jump(fall, out);
        if (!hasDefault) branch(head, out, BranchLabel.DEFAULT_VALUE, null, p);
        head.terminated = true;
        jumps.pop();
        seal(out);
        cur = out;
        if (!isExpr || ctx.results.isEmpty()) return R.EMPTY;
        int phi = newVal(NodeKind.LOCAL, "switch 결과", s);
        vals.get(phi).phi = true;                                    // 값 φ: 제어 의존 대상 아님
        dus(ctx.results, phi, 0);
        return R.of(phi, null);
    }

    private void returnStmt(Node s) {
        List<Node> ch = s.getNamedChildren();
        R r = ch.isEmpty() ? R.EMPTY : expr(ch.getFirst());
        if (!lambdas.isEmpty()) {
            JumpCtx l = lambdas.peek();
            l.results.addAll(r.vals);
            jump(cur, l.breakTo);
            cur = deadBlock();
            return;
        }
        if (!ch.isEmpty() && formalOut >= 0) returns.add(new RetSite(cur, r.vals, ch.getFirst()));
        jump(cur, exit);
        cur = deadBlock();
    }

    private JumpCtx target(Node s, boolean isContinue) {
        String label = s.getNamedChildren().stream().filter(x -> x.getType().equals("identifier")).findFirst()
                .map(MethodLowering::text).orElse(null);
        for (JumpCtx j : jumps) {
            if (label != null && !label.equals(j.label)) continue;
            if (isContinue && j.isSwitch) continue;
            return j;
        }
        return null;
    }

    private void breakStmt(Node s) {
        JumpCtx j = target(s, false);
        if (j != null) jump(cur, j.breakTo);
        cur = deadBlock();
    }

    private void continueStmt(Node s) {
        JumpCtx j = target(s, true);
        if (j != null) jump(cur, j.continueTo);
        cur = deadBlock();
    }

    private void yieldStmt(Node s) {
        R r = s.getNamedChildren().isEmpty() ? R.EMPTY : expr(s.getNamedChildren().getFirst());
        for (JumpCtx j : jumps) {
            if (!j.isSwitch) continue;
            j.results.addAll(r.vals);
            jump(cur, j.breakTo);
            break;
        }
        cur = deadBlock();
    }

    /** try/catch 단순화 (설계서 3.2.3): try마다 합성 PREDICATE EXC(try#n), catch 값은 EXCEPTION 라벨로 종속. */
    private void tryStmt(Node s) {
        s.getChildByFieldName("resources").ifPresent(rs -> {
            for (Node r : rs.getNamedChildren()) {
                if (!r.getType().equals("resource")) continue;
                Optional<Node> name = r.getChildByFieldName("name");
                Optional<Node> value = r.getChildByFieldName("value");
                if (name.isPresent() && value.isPresent()) {
                    R v = expr(value.get());
                    localTypes.put(text(name.get()), r.getChildByFieldName("type").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(v.type));
                    defineVar(text(name.get()), v.vals, r, 0, "");
                } else expr(r);
            }
        });
        Block entry = cur;
        int exc = newVal(NodeKind.PREDICATE, "EXC(try#" + (++tryCount) + ")", s);
        Block body = newBlock(), join = newBlock();
        List<Node> catches = s.getNamedChildren().stream().filter(c -> c.getType().equals("catch_clause")).toList();
        List<Block> cbs = new ArrayList<>();
        for (int i = 0; i < catches.size(); i++) {
            Block cb = newBlock();
            branch(entry, cb, BranchLabel.EXCEPTION_VALUE, null, exc);
            cbs.add(cb);
        }
        jump(entry, body);
        entry.terminated = true;
        seal(body);
        cur = body;
        stmt(s.getChildByFieldName("body").orElse(null));
        jump(cur, join);
        for (int i = 0; i < catches.size(); i++) {
            Block cb = cbs.get(i);
            seal(cb);
            cur = cb;
            Node cc = catches.get(i);
            for (Node c : cc.getNamedChildren()) {
                if (c.getType().equals("catch_formal_parameter")) {
                    String n = c.getChildByFieldName("name").map(MethodLowering::text).orElse("e");
                    localTypes.put(n, null);
                    defineVar(n, List.of(), c, 0, "");
                } else if (c.getType().equals("block")) stmt(c);
            }
            jump(cur, join);
        }
        seal(join);
        cur = join;
        for (Node c : s.getNamedChildren()) if (c.getType().equals("finally_clause")) for (Node b : c.getNamedChildren()) stmt(b);
    }

    // ───────────────────────────── 식 ─────────────────────────────

    private R expr(Node n) {
        if (n == null) return R.EMPTY;
        if (n.isError() || n.isMissing()) {
            errorNodes++;
            return R.EMPTY;
        }
        switch (n.getType()) {
            case "parenthesized_expression" -> {
                return n.getNamedChildren().isEmpty() ? R.EMPTY : expr(n.getNamedChildren().getFirst());
            }
            case "identifier" -> {
                return ident(n);
            }
            case "this" -> {
                return thisVal >= 0 ? R.of(thisVal, cls.fqn) : new R(List.of(), cls.fqn);
            }
            case "string_literal", "text_block" -> {
                return new R(List.of(), "java.lang.String");
            }
            case "decimal_integer_literal", "hex_integer_literal", "octal_integer_literal", "binary_integer_literal",
                 "decimal_floating_point_literal", "hex_floating_point_literal", "true", "false", "null_literal",
                 "character_literal", "class_literal", "line_comment", "block_comment" -> {
                return R.EMPTY;
            }
            case "field_access" -> {
                return fieldRead(n);
            }
            case "method_invocation" -> {
                return call(n);
            }
            case "object_creation_expression" -> {
                return newObject(n);
            }
            case "assignment_expression" -> {
                return assign(n);
            }
            case "binary_expression" -> {
                String op = n.getChildByFieldName("operator").map(MethodLowering::text).orElse("");
                if (op.equals("&&") || op.equals("||")) return withinE(n, op + " 합류", () -> shortCircuitValue(n));
                R l = expr(n.getChildByFieldName("left").orElse(null));
                R r = expr(n.getChildByFieldName("right").orElse(null));
                String t = op.equals("+") && ("java.lang.String".equals(l.type) || "java.lang.String".equals(r.type))
                        ? "java.lang.String" : null;
                return new R(l.plus(r).vals, t);
            }
            case "unary_expression" -> {
                return expr(n.getChildByFieldName("operand").orElse(null));
            }
            case "update_expression" -> {
                Node target = n.getNamedChildren().isEmpty() ? null : n.getNamedChildren().getFirst();
                if (target != null && target.getType().equals("identifier") && localTypes.containsKey(text(target))) {
                    R old = ident(target);
                    return R.of(defineVar(text(target), old.vals, n, 0, ""), old.type);
                }
                return expr(target);
            }
            case "ternary_expression" -> {
                return withinE(n, "?: 합류", () -> ternary(n));
            }
            case "cast_expression" -> {
                R v = expr(n.getChildByFieldName("value").orElse(null));
                return new R(v.vals, n.getChildByFieldName("type").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(v.type));
            }
            case "instanceof_expression" -> {
                R l = expr(n.getChildByFieldName("left").orElse(null));
                n.getChildByFieldName("name").ifPresent(nm -> {
                    localTypes.put(text(nm), n.getChildByFieldName("right").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(null));
                    defineVar(text(nm), l.vals, nm, 0, "");
                });
                return new R(l.vals, "boolean");
            }
            case "lambda_expression" -> {
                return lambda(n, List.of());
            }
            case "switch_expression" -> {
                return withinE(n, "switch 합류", () -> switchExpr(n, true, null));
            }
            case "array_access" -> {
                return expr(n.getChildByFieldName("array").orElse(null)).plus(expr(n.getChildByFieldName("index").orElse(null)));
            }
            default -> {
                R r = R.EMPTY;
                for (Node c : n.getNamedChildren()) r = r.plus(expr(c));
                return r;
            }
        }
    }

    private R ident(Node n) {
        String name = text(n);
        if (localTypes.containsKey(name)) {
            int v = read(name, cur);
            return v >= 0 ? R.of(v, localTypes.get(name)) : new R(List.of(), localTypes.get(name));
        }
        String[] declaring = new String[1];
        FieldInfo f = idx.field(cls.fqn, name, declaring);
        if (f != null) return fieldLoad(declaring[0], name, f.type, n, 0);
        return new R(List.of(), idx.resolve(name, cls));
    }

    private R fieldLoad(String declCls, String field, String type, Node at, int conf) {
        String key = declCls + "." + field + "@" + cur.id;
        Integer v = fieldLoads.get(key);
        if (v == null) {
            v = newVal(NodeKind.FIELD_LOAD, "load " + simple(declCls) + "." + field, at);
            loads.add(new int[]{fieldExt(declCls + "." + field), v, conf});
            fieldLoads.put(key, v);
        }
        return R.of(v, type);
    }

    private int fieldStore(String declCls, String field, List<Integer> srcs, Node at, int conf) {
        int v = newVal(NodeKind.FIELD_STORE, "store " + simple(declCls) + "." + field, at);
        dus(srcs, v, 0);
        stores.add(new int[]{v, fieldExt(declCls + "." + field), conf});
        return v;
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /** obj.f 읽기: 프로젝트 클래스의 필드면 FIELD 근사(field-based), 아니면 수신 값 그대로. */
    private R fieldRead(Node n) {
        Node obj = n.getChildByFieldName("object").orElse(null);
        String fname = n.getChildByFieldName("field").map(MethodLowering::text).orElse("?");
        String owner = ownerType(obj);
        if (owner != null && idx.isProject(owner)) {
            String[] d = new String[1];
            FieldInfo f = idx.field(owner, fname, d);
            if (f != null) return fieldLoad(d[0], fname, f.type, n, 0);
        }
        return obj == null ? R.EMPTY : new R(expr(obj).vals, null);
    }

    /** 필드 접근의 소유 타입: this/super/지역 변수/필드/클래스 이름. */
    private String ownerType(Node obj) {
        if (obj == null) return null;
        switch (obj.getType()) {
            case "this" -> {
                return cls.fqn;
            }
            case "super" -> {
                return cls.superFqn;
            }
            case "identifier" -> {
                String nm = text(obj);
                if (localTypes.containsKey(nm)) return localTypes.get(nm);
                FieldInfo f = idx.field(cls.fqn, nm, null);
                if (f != null) return f.type;
                String t = idx.resolve(nm, cls);
                return idx.isProject(t) ? t : null;
            }
            default -> {
                return null;
            }
        }
    }

    private R assign(Node n) {
        Node left = n.getChildByFieldName("left").orElse(null);
        Node right = n.getChildByFieldName("right").orElse(null);
        String op = n.getChildByFieldName("operator").map(MethodLowering::text).orElse("=");
        R rv = expr(right);
        if (left == null) return rv;
        if (!op.equals("=")) rv = rv.plus(expr(left));
        switch (left.getType()) {
            case "identifier" -> {
                String name = text(left);
                if (localTypes.containsKey(name)) {
                    String c = right == null ? null : constOf(right);
                    if (op.equals("=") && c != null) consts.put(name, c);
                    else if (op.equals("+=") && c != null && consts.containsKey(name)) consts.put(name, consts.get(name) + c);
                    else consts.remove(name);
                    return R.of(defineVar(name, rv.vals, n, 0, ""), localTypes.get(name));
                }
                String[] d = new String[1];
                FieldInfo f = idx.field(cls.fqn, name, d);
                if (f != null) return R.of(fieldStore(d[0], name, rv.vals, n, 0), f.type);
                return rv;
            }
            case "field_access" -> {
                Node obj = left.getChildByFieldName("object").orElse(null);
                String fname = left.getChildByFieldName("field").map(MethodLowering::text).orElse("?");
                String owner = ownerType(obj);
                if (owner != null && idx.isProject(owner)) {
                    String[] d = new String[1];
                    FieldInfo f = idx.field(owner, fname, d);
                    if (f != null) return R.of(fieldStore(d[0], fname, rv.vals, n, 0), f.type);
                }
                return weakUpdate(obj, rv, n);
            }
            case "array_access" -> {
                Node arr = left.getChildByFieldName("array").orElse(null);
                expr(left.getChildByFieldName("index").orElse(null));
                return weakUpdate(arr, rv, n);
            }
            default -> {
                return rv;
            }
        }
    }

    /** 지역 변수가 가리키는 객체의 일부 변경: 변수의 새 버전 = 옛 버전 ∪ 새 값. */
    private R weakUpdate(Node target, R rv, Node at) {
        if (target != null && target.getType().equals("identifier") && localTypes.containsKey(text(target))) {
            R old = ident(target);
            return R.of(defineVar(text(target), old.plus(rv).vals, at, 0, ""), old.type);
        }
        return rv;
    }

    private R ternary(Node n) {
        Block tb = newBlock(), fb = newBlock(), join = newBlock();
        cond(n.getChildByFieldName("condition").orElse(null), tb, fb);
        seal(tb);
        seal(fb);
        cur = tb;
        R a = expr(n.getChildByFieldName("consequence").orElse(null));
        jump(cur, join);
        cur = fb;
        R b = expr(n.getChildByFieldName("alternative").orElse(null));
        jump(cur, join);
        seal(join);
        cur = join;
        R both = a.plus(b);
        if (both.vals.isEmpty()) return new R(List.of(), both.type);
        int phi = newVal(NodeKind.LOCAL, "?: 결과", n);
        vals.get(phi).phi = true;
        dus(both.vals, phi, 0);
        return R.of(phi, both.type);
    }

    /** 값 문맥의 a && b / a || b: 피연산자별 조건 노드, 결과는 조건 노드들의 φ. */
    private R shortCircuitValue(Node n) {
        Block tb = newBlock(), fb = newBlock(), join = newBlock();
        int before = vals.size();
        cond(n, tb, fb);
        seal(tb);
        seal(fb);
        jump(tb, join);
        jump(fb, join);
        seal(join);
        cur = join;
        List<Integer> preds = new ArrayList<>();
        for (int i = before; i < vals.size(); i++) if (vals.get(i).kind == NodeKind.PREDICATE) preds.add(i);
        int phi = newVal(NodeKind.LOCAL, compact(n) + " 결과", n);
        vals.get(phi).phi = true;
        dus(preds, phi, 0);
        return R.of(phi, "boolean");
    }

    /** 람다: 매개변수 ← 바깥 값(HEURISTIC), 본문은 순차 실행으로 근사, return 값은 람다 결과. */
    private R lambda(Node n, List<Integer> inputs) {
        Node params = n.getChildByFieldName("parameters").orElse(null);
        List<String> names = new ArrayList<>();
        if (params != null) {
            if (params.getType().equals("identifier")) names.add(text(params));
            else for (Node p : params.getNamedChildren()) {
                if (p.getType().equals("identifier")) names.add(text(p));
                else p.getChildByFieldName("name").ifPresent(x -> names.add(text(x)));
            }
        }
        for (String nm : names) {
            localTypes.put(nm, null);
            defineVar(nm, inputs, n, Confidence.HEURISTIC_VALUE, "");
        }
        Node body = n.getChildByFieldName("body").orElse(null);
        if (body == null) return R.EMPTY;
        if (!body.getType().equals("block")) return expr(body);
        origins.push(new Origin(n, "람다"));
        Block lexit = newBlock();
        JumpCtx l = new JumpCtx(null, lexit, null, false);
        l.results = new ArrayList<>();
        lambdas.push(l);
        Deque<JumpCtx> saved = new ArrayDeque<>(jumps);
        jumps.clear();
        stmt(body);
        jumps.addAll(saved);
        lambdas.pop();
        jump(cur, lexit);
        seal(lexit);
        cur = lexit;
        origins.pop();
        return new R(l.results, null);
    }

    // ───────────────────────────── 호출 ─────────────────────────────

    private static List<Node> args(Node inv) {
        return inv.getChildByFieldName("arguments").map(Node::getNamedChildren).orElse(List.of())
                .stream().filter(x -> !x.getType().endsWith("comment")).toList();
    }

    private R call(Node inv) {
        Node obj = inv.getChildByFieldName("object").orElse(null);
        String name = inv.getChildByFieldName("name").map(MethodLowering::text).orElse("?");
        List<Node> args = args(inv);
        Facts.Jdbc j = facts.jdbcs.get(inv.getStartByte());
        if (j != null) {
            R r = embeddedSql(inv, obj, name, args, j);
            if (r != null) return r;
        }
        CallKind kind;
        String rtype = null;
        String recvField = null;
        R recvVal = null;
        if (obj == null) {
            kind = CallKind.THIS;
            rtype = cls.fqn;
        } else switch (obj.getType()) {
            case "this" -> {
                kind = CallKind.THIS;
                rtype = cls.fqn;
            }
            case "super" -> {
                kind = CallKind.SUPER;
                rtype = cls.superFqn;
            }
            case "identifier" -> {
                String nm = text(obj);
                String[] d = new String[1];
                FieldInfo f = localTypes.containsKey(nm) ? null : idx.field(cls.fqn, nm, d);
                if (localTypes.containsKey(nm)) {
                    kind = CallKind.VIRTUAL;
                    rtype = localTypes.get(nm);
                } else if (f != null) {
                    kind = CallKind.VIRTUAL;
                    rtype = f.type;
                    recvField = d[0] + "." + nm;
                } else if (Character.isUpperCase(nm.charAt(0))) {
                    kind = CallKind.STATIC;
                    rtype = idx.resolve(nm, cls);
                } else {
                    kind = CallKind.VIRTUAL;
                }
            }
            case "field_access" -> {
                Node o2 = obj.getChildByFieldName("object").orElse(null);
                String fname = obj.getChildByFieldName("field").map(MethodLowering::text).orElse("?");
                String owner = ownerType(o2);
                String[] d = new String[1];
                FieldInfo f = owner != null && idx.isProject(owner) ? idx.field(owner, fname, d) : null;
                kind = CallKind.VIRTUAL;
                if (f != null && o2 != null && o2.getType().equals("this")) {
                    rtype = f.type;
                    recvField = d[0] + "." + fname;
                } else {
                    recvVal = expr(obj);
                    rtype = recvVal.type;
                }
            }
            default -> {
                kind = CallKind.VIRTUAL;
                recvVal = expr(obj);
                rtype = recvVal.type;
            }
        }
        List<MethodInfo> targets = rtype != null && idx.isProject(rtype) ? idx.findMethods(rtype, name, args.size()) : List.of();
        int conf = Confidence.EXACT_VALUE;
        boolean project = !targets.isEmpty();
        if (!project && kind == CallKind.VIRTUAL && (rtype == null || rtype.isEmpty())) {
            List<MethodInfo> byName = new ArrayList<>();
            for (String c : idx.classesWithMethod(name)) byName.addAll(idx.findMethods(c, name, args.size()));
            if (!byName.isEmpty()) {                             // 이름만 일치: HEURISTIC
                project = true;
                targets = byName;
                conf = Confidence.HEURISTIC_VALUE;
            }
        }
        if (project) {
            // 인스턴스 메서드 호출: 수신 객체를 ACTUAL_IN[0]으로 (대상의 this FORMAL_IN에 대응)
            R recv = null;
            if (kind != CallKind.STATIC && targets.stream().anyMatch(t -> !t.isStatic)) {
                if (kind == CallKind.THIS || kind == CallKind.SUPER) recv = thisVal >= 0 ? R.of(thisVal, cls.fqn) : R.EMPTY;
                else if (recvVal != null) recv = recvVal;
                else if (recvField != null) {
                    int k = recvField.lastIndexOf('.');
                    recv = fieldLoad(recvField.substring(0, k), recvField.substring(k + 1), rtype, obj, 0);
                } else recv = obj == null ? R.EMPTY : expr(obj);
            }
            return projectCall(inv, name, args, kind, rtype, recvField, targets, conf, recv);
        }
        if (rtype != null && idx.isProject(rtype)) {
            R acc = accessor(rtype, name, args, inv);
            if (acc != null) return acc;
        }
        return libraryCall(inv, obj, name, args, rtype, recvVal);
    }

    private R projectCall(Node inv, String name, List<Node> args, CallKind kind, String rtype, String recvField,
                          List<MethodInfo> targets, int conf, R recv) {
        List<Integer> ain = new ArrayList<>();
        if (recv != null) {
            int ri = newVal(NodeKind.ACTUAL_IN, name + "(·)[this]", inv.getChildByFieldName("object").orElse(inv));
            dus(recv.vals, ri, 0);
            ain.add(ri);
        }
        for (int i = 0; i < args.size(); i++) {
            Node a = args.get(i);
            R av = a.getType().equals("lambda_expression") ? lambda(a, List.of()) : expr(a);
            int ai = newVal(NodeKind.ACTUAL_IN, name + "(·)[" + i + "]", a);
            dus(av.vals, ai, 0);
            ain.add(ai);
        }
        int ao = newVal(NodeKind.ACTUAL_OUT, name + "() 결과", inv);
        CallSite.Builder cs = CallSite.newBuilder().addAllActualIn(ain).setActualOut(ao).setMethodName(name)
                .setReceiverType(rtype == null ? "" : rtype).setCallKind(kind).setConf(Confidence.forNumber(conf)).setHasReceiver(recv != null)
                .setSpan(span(inv));
        for (MethodInfo t : targets) cs.addCandidateTargets(t.signature);
        if (recvField != null) cs.setReceiverField(fieldExt(recvField));
        calls.add(cs);
        String ret = targets.isEmpty() ? null : targets.getFirst().returnType;
        return R.of(ao, ret);
    }

    /** 프로젝트 DTO의 get/set/is 접근자가 선언되지 않았으면(Lombok 등) 필드 접근으로 근사 (HEURISTIC). */
    private R accessor(String type, String name, List<Node> args, Node inv) {
        String prop = null;
        boolean set = false;
        if (name.length() > 3 && (name.startsWith("get") || name.startsWith("set")) && Character.isUpperCase(name.charAt(3))) {
            prop = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            set = name.startsWith("set");
        } else if (name.length() > 2 && name.startsWith("is") && Character.isUpperCase(name.charAt(2))) {
            prop = Character.toLowerCase(name.charAt(2)) + name.substring(3);
        }
        if (prop == null) return null;
        String[] d = new String[1];
        FieldInfo f = idx.field(type, prop, d);
        if (f == null) return null;
        if (set && args.size() == 1) {
            R v = expr(args.getFirst());
            fieldStore(d[0], prop, v.vals, inv, Confidence.HEURISTIC_VALUE);
            return R.EMPTY;
        }
        if (!set && args.isEmpty()) return fieldLoad(d[0], prop, f.type, inv, Confidence.HEURISTIC_VALUE);
        return null;
    }

    private R libraryCall(Node inv, Node obj, String name, List<Node> args, String rtype, R recvVal) {
        R recv = recvVal != null ? recvVal : obj == null ? R.EMPTY : expr(obj);
        LibrarySummaries.Summary s = lib.lookup(rtype, name);
        List<R> argv = new ArrayList<>();
        for (Node a : args) argv.add(a.getType().equals("lambda_expression") ? lambda(a, recv.vals) : expr(a));
        int conf = s.known() ? Confidence.EXACT_VALUE : Confidence.HEURISTIC_VALUE;
        Set<Integer> in = new LinkedHashSet<>();
        if (s.recv()) in.addAll(recv.vals);
        if (s.allArgs()) for (R r : argv) in.addAll(r.vals);
        if (s.argIdx() >= 0 && s.argIdx() < argv.size()) in.addAll(argv.get(s.argIdx()).vals);
        if (s.mut() && obj != null && obj.getType().equals("identifier") && localTypes.containsKey(text(obj))) {
            Set<Integer> m = new LinkedHashSet<>(recv.vals);
            for (R r : argv) m.addAll(r.vals);
            defineVar(text(obj), new ArrayList<>(m), inv, conf, " (" + name + ")");
        }
        String rt = s.recv() && rtype != null && rtype.endsWith("String") && !name.startsWith("is") ? rtype : null;
        if (in.isEmpty()) return new R(List.of(), rt);
        int rv = newVal(NodeKind.LOCAL, name + "() 결과", inv);
        for (int v : in) du(v, rv, conf);
        return R.of(rv, rt);
    }

    private R newObject(Node n) {
        String type = n.getChildByFieldName("type").map(t -> idx.resolve(JavaIndex.erasure(t), cls)).orElse(null);
        List<Node> args = args(n);
        if (type != null && idx.isProject(type)) {
            List<MethodInfo> ctors = idx.findMethods(type, "<init>", args.size());
            if (!ctors.isEmpty() || !args.isEmpty()) {
                R r = projectCall(n, "<init>", args, CallKind.CONSTRUCTOR, type, null, ctors,
                        ctors.isEmpty() ? Confidence.HEURISTIC_VALUE : Confidence.EXACT_VALUE, null);
                return new R(r.vals, type);
            }
            return new R(List.of(), type);
        }
        LibrarySummaries.Summary s = lib.lookup(type, "<init>");
        R in = R.EMPTY;
        for (Node a : args) in = in.plus(expr(a));
        if (in.vals.isEmpty()) return new R(List.of(), type);
        int v = newVal(NodeKind.LOCAL, "new " + (type == null ? "?" : simple(type)) + "()", n);
        dus(in.vals, v, s.known() ? 0 : Confidence.HEURISTIC_VALUE);
        return R.of(v, type);
    }

    private void ctorInvocation(Node s) {
        String which = s.getChildByFieldName("constructor").map(MethodLowering::text).orElse("this");
        List<Node> args = args(s);
        String target = which.equals("super") ? cls.superFqn : cls.fqn;
        if (target == null || !idx.isProject(target)) {
            for (Node a : args) expr(a);
            return;
        }
        projectCall(s, "<init>", args, which.equals("super") ? CallKind.SUPER : CallKind.THIS, target, null,
                idx.findMethods(target, "<init>", args.size()), Confidence.EXACT_VALUE, null);
    }

    /** Tier C 내장 SQL: SQL 문자열을 해석할 수 있으면 합성 메서드를 만들고 직접 호출로 연결합니다. */
    private R embeddedSql(Node inv, Node obj, String name, List<Node> args, Facts.Jdbc j) {
        if (j.sqlArg() >= args.size()) return null;
        String sql = constOf(args.get(j.sqlArg()));
        if (sql == null) {
            diags.add(info.signature + " L" + (inv.getStartPoint().row() + 1) + ": 내장 SQL 문자열을 해석할 수 없음");
            return null;
        }
        if (obj != null && !obj.getType().equals("identifier") && !obj.getType().equals("this")) expr(obj);
        int[] cnt = new int[1];
        String numbered = SqlText.numberQuestionMarks(sql, 0, cnt);
        int k = j.bindFrom() < 0 ? 0 : cnt[0];
        int line = inv.getStartPoint().row() + 1;
        String sname = info.name + "$sql" + (++sqlCount) + "@L" + line;
        String sig = cls.fqn + "#" + sname + "(" + String.join(",", java.util.Collections.nCopies(k, "java.lang.Object")) + ")";
        List<String> params = new ArrayList<>();
        List<BindSlot> slots = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            params.add("p" + (i + 1));
            slots.add(new BindSlot(i, "?" + (i + 1), "p" + (i + 1), "", span(inv), false));
        }
        sqlOut.add(new PreparedSql(sig, cls.fqn, sname, span(inv), firstWord(sql), null, null, Map.of(), params, slots,
                List.of(numbered), false, false));
        List<Node> bindArgs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (j.bindFrom() >= 0 && i >= j.bindFrom()) {
                Node a = args.get(i);
                if (args.size() == j.bindFrom() + 1 && a.getType().equals("array_creation_expression"))
                    a.getChildByFieldName("value").ifPresent(v -> bindArgs.addAll(v.getNamedChildren()));
                else bindArgs.add(a);
            } else if (i != j.sqlArg()) expr(args.get(i));
        }
        List<Integer> ain = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            R av = i < bindArgs.size() ? expr(bindArgs.get(i)) : R.EMPTY;
            int ai = newVal(NodeKind.ACTUAL_IN, name + "(·)[" + (i + 1) + "]", i < bindArgs.size() ? bindArgs.get(i) : inv);
            dus(av.vals, ai, 0);
            ain.add(ai);
        }
        int ao = newVal(NodeKind.ACTUAL_OUT, name + "() 결과", inv);
        calls.add(CallSite.newBuilder().addAllActualIn(ain).setActualOut(ao).setMethodName(sname)
                .setCallKind(CallKind.DIRECT_TARGET).addCandidateTargets(sig).setConf(Confidence.EXACT)
                .setSpan(span(inv)));
        return R.of(ao, null);
    }

    private static String firstWord(String sql) {
        String t = sql.trim().toLowerCase();
        int k = 0;
        while (k < t.length() && Character.isLetter(t.charAt(k))) k++;
        return t.substring(0, k);
    }

    /** 문자열 상수 해석: 리터럴, 연결, 상수 지역 변수, static final 상수. */
    private String constOf(Node n) {
        n = unparen(n);
        if (n == null) return null;
        switch (n.getType()) {
            case "string_literal", "text_block" -> {
                return JavaIndex.constString(n);
            }
            case "binary_expression" -> {
                String op = n.getChildByFieldName("operator").map(MethodLowering::text).orElse("");
                if (!op.equals("+")) return null;
                String a = constOf(n.getChildByFieldName("left").orElse(null));
                String b = constOf(n.getChildByFieldName("right").orElse(null));
                return a == null || b == null ? null : a + b;
            }
            case "identifier" -> {
                String nm = text(n);
                if (consts.containsKey(nm)) return consts.get(nm);
                FieldInfo f = localTypes.containsKey(nm) ? null : idx.field(cls.fqn, nm, null);
                return f == null ? null : f.constant;
            }
            case "field_access" -> {
                String owner = ownerType(n.getChildByFieldName("object").orElse(null));
                String fname = n.getChildByFieldName("field").map(MethodLowering::text).orElse("?");
                FieldInfo f = owner == null ? null : idx.field(owner, fname, null);
                return f == null ? null : f.constant;
            }
            case "method_invocation" -> {                         // sb.toString(), String.join 등은 해석하지 않음
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    // ───────────────────────────── 제어 의존 (설계서 3.2.3) ─────────────────────────────

    /** 후지배 트리(역 CFG에서 Cooper–Harvey–Kennedy) → 분기 간선별 제어 의존 블록 집합. */
    private Map<Block, List<Cd>> controlDependence() {
        // EXIT에 도달하지 못하는 블록(무한 반복 등)은 가상 간선으로 EXIT에 잇는다
        Set<Block> reach = new HashSet<>();
        Deque<Block> work = new ArrayDeque<>(List.of(exit));
        while (!work.isEmpty()) {
            Block b = work.pop();
            if (!reach.add(b)) continue;
            work.addAll(b.preds);
        }
        for (Block b : blocks) {
            if (b != exit && !reach.contains(b)) {
                b.succ.add(new CEdge(exit, -1, null, -1));
                exit.preds.add(b);
            }
        }
        // 역 CFG 후위 순서 (EXIT에서 preds 방향 DFS)
        Map<Block, Integer> po = new HashMap<>();
        List<Block> order = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Object[]> st = new ArrayDeque<>();
        st.push(new Object[]{exit, 0});
        seen.add(exit);
        while (!st.isEmpty()) {
            Object[] top = st.peek();
            Block b = (Block) top[0];
            int i = (int) top[1];
            if (i < b.preds.size()) {
                top[1] = i + 1;
                Block p = b.preds.get(i);
                if (seen.add(p)) st.push(new Object[]{p, 0});
            } else {
                st.pop();
                po.put(b, order.size());
                order.add(b);
            }
        }
        Map<Block, Block> ipdom = new HashMap<>();
        ipdom.put(exit, exit);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = order.size() - 1; k >= 0; k--) {
                Block b = order.get(k);
                if (b == exit) continue;
                Block nd = null;
                for (CEdge e : b.succ) {
                    Block s = e.to;
                    if (!ipdom.containsKey(s)) continue;
                    nd = nd == null ? s : intersect(s, nd, ipdom, po);
                }
                if (nd != null && ipdom.get(b) != nd) {
                    ipdom.put(b, nd);
                    changed = true;
                }
            }
        }
        Map<Block, List<Cd>> cd = new HashMap<>();
        for (Block a : blocks) {
            Block stop = ipdom.get(a);
            for (CEdge e : a.succ) {
                if (e.label < 0) continue;
                Block runner = e.to;
                int guard = 0;
                while (runner != null && runner != stop && runner != exit && guard++ < blocks.size() + 2) {
                    Cd c = new Cd(e.pred, e.label, e.caseVal);
                    List<Cd> l = cd.computeIfAbsent(runner, x -> new ArrayList<>());
                    if (!l.contains(c)) l.add(c);
                    runner = ipdom.get(runner);
                }
            }
        }
        return cd;
    }

    private static Block intersect(Block a, Block b, Map<Block, Block> ipdom, Map<Block, Integer> po) {
        while (a != b) {
            while (po.get(a) < po.get(b)) a = ipdom.get(a);
            while (po.get(b) < po.get(a)) b = ipdom.get(b);
        }
        return a;
    }

    // ───────────────────────────── IR 출력 ─────────────────────────────

    private MethodIR emit(Map<Block, List<Cd>> cd) {
        MethodIR.Builder m = MethodIR.newBuilder().setSignature(info.signature).setClassName(cls.fqn).setName(info.name)
                .addAllParamTypes(info.paramTypes).addAllParamNames(info.paramNames).setReturnType(info.returnType).setHasThis(thisVal >= 0)
                .setIsAbstract(info.isAbstract).setIsStatic(info.isStatic).setSpan(declSpan(decl, fileId));
        int[] map = new int[vals.size()];
        int n = 0;
        for (Val v : vals) {
            map[v.id] = v.replaced == -2 ? n++ : -1;
            if (map[v.id] < 0) continue;
            m.addNodes(com.ids.qtrack.next.ir.Node.newBuilder().setLocalId(map[v.id]).setKind(v.kind).setName(v.name)
                    .setSpan(Span.newBuilder().setFileId(fileId).setStart(v.start).setEnd(v.end).setLine(v.line)));
        }
        Set<Long> seenEdges = new HashSet<>();
        java.util.function.IntUnaryOperator mapped = x -> {
            int f = find(x);
            return f < 0 ? -1 : map[f];
        };
        for (int[] e : defUse) addDu(m, mapped.applyAsInt(e[0]), mapped.applyAsInt(e[1]), e[2], seenEdges);
        for (Val p : vals) {
            if (!p.phi || p.replaced != -2 || p.var == null) continue;
            for (int o : p.ops) addDu(m, mapped.applyAsInt(o), map[p.id], 0, seenEdges);
        }
        for (int[] s : stores)
            m.addEdges(Edge.newBuilder().setSrc(map[s[0]]).setDstExt(s[1]).setKind(EdgeKind.STORE).setConfValue(s[2]));
        for (int[] l : loads)
            m.addEdges(Edge.newBuilder().setSrcExt(l[0]).setDst(map[l[1]]).setKind(EdgeKind.LOAD).setConfValue(l[2]));
        // CONTROL: 분기에서 새로 정의되는 값마다 P ─L→ v (φ와 FORMAL_IN 제외)
        for (Val v : vals) {
            if (v.replaced != -2 || v.phi || v.kind == NodeKind.FORMAL_IN || v.kind == NodeKind.FORMAL_OUT) continue;
            for (Cd c : cd.getOrDefault(v.block, List.of())) {
                int p = map[c.pred()];
                if (p < 0) continue;
                Edge.Builder e = Edge.newBuilder().setSrc(p).setDst(map[v.id]).setKind(EdgeKind.CONTROL)
                        .setLabelValue(c.label()).setConf(c.label() == BranchLabel.EXCEPTION_VALUE ? Confidence.HEURISTIC : Confidence.EXACT);
                if (c.caseVal() != null) e.setCaseValue(c.caseVal());
                m.addEdges(e);
            }
        }
        for (CallSite.Builder c : calls) {
            List<Integer> ain = new ArrayList<>();
            for (int a : c.getActualInList()) ain.add(map[a]);
            c.clearActualIn().addAllActualIn(ain).setActualOut(map[c.getActualOut()]);
            m.addCalls(c);
        }
        m.addAllExternals(externals);
        for (int fi : formalIns) m.addFormalIn(map[fi]);
        if (formalOut >= 0) m.setFormalOut(map[formalOut] + 1);
        return m.build();
    }

    private static void addDu(MethodIR.Builder m, int s, int d, int conf, Set<Long> seen) {
        if (s < 0 || d < 0 || s == d) return;
        if (!seen.add(((long) s << 32) | d)) return;
        m.addEdges(Edge.newBuilder().setSrc(s).setDst(d).setKind(EdgeKind.DEF_USE).setConfValue(conf));
    }
}
