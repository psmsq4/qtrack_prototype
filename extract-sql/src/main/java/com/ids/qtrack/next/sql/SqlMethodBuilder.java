package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.ir.BranchLabel;
import com.ids.qtrack.next.ir.Clause;
import com.ids.qtrack.next.ir.ColumnKey;
import com.ids.qtrack.next.ir.Confidence;
import com.ids.qtrack.next.ir.DeriveKind;
import com.ids.qtrack.next.ir.Edge;
import com.ids.qtrack.next.ir.EdgeKind;
import com.ids.qtrack.next.ir.ExternalRef;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.Node;
import com.ids.qtrack.next.ir.NodeKind;
import com.ids.qtrack.next.ir.Span;
import com.ids.qtrack.next.ir.SqlMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL 문 하나를 합성 메서드(MethodIR)로 만듭니다 (설계서 3.4, FR-IN-03).
 * <ul>
 *   <li>FORMAL_IN: 파라미터 (단일 값, @Param 이름별, DTO는 _parameter + 프로퍼티별 FIELD)</li>
 *   <li>BIND: {@code #{…}} 슬롯마다 1개. {@code ${…}}는 UNKNOWN(텍스트 오염, HEURISTIC)</li>
 *   <li>FORMAL_OUT: 결과행(SELECT) 또는 갱신 건수(DML)</li>
 *   <li>간선: FORMAL_IN → BIND (BIND_TO), BIND → COLUMN (MAPS_TO, 절), COLUMN → FORMAL_OUT (MAPS_TO, 필터면 implicit),
 *       BIND → FORMAL_OUT (LOCAL_FLOW, 필터면 implicit [D-06]), COLUMN → COLUMN (COL_DERIVES)</li>
 * </ul>
 */
public final class SqlMethodBuilder {
    /** 바인드 슬롯. root = 파라미터 이름(첫 경로), property = 나머지 경로. */
    public record BindSlot(int slot, String expr, String root, String property, Span span, boolean dollar) {}

    private final MethodIR.Builder m = MethodIR.newBuilder();
    private final Map<String, Integer> ext = new LinkedHashMap<>();
    private int nextId;
    private final Map<String, Integer> formalIn = new LinkedHashMap<>();
    private final Map<Integer, Integer> bindNode = new HashMap<>();
    private int formalOut;
    private final Set<String> edgeKeys = new LinkedHashSet<>();
    private final int baseConf;

    public SqlMethodBuilder(String signature, String className, String name, Span span, List<String> params,
                            boolean capped) {
        m.setSignature(signature).setSynthetic(true).setClassName(className).setName(name).setSpan(span);
        baseConf = capped ? Confidence.HEURISTIC_VALUE : Confidence.EXACT_VALUE;
        List<String> ps = params.isEmpty() ? List.of("_parameter") : params;
        for (String p : ps) {
            int id = node(NodeKind.FORMAL_IN, p, span);
            formalIn.put(p, id);
            m.addFormalIn(id);
            m.addParamNames(p);
        }
        formalOut = node(NodeKind.FORMAL_OUT, "결과", span);
        m.setFormalOut(formalOut + 1);
    }

    public MethodIR.Builder method() {
        return m;
    }

    private int node(NodeKind kind, String name, Span span) {
        int id = nextId++;
        m.addNodes(Node.newBuilder().setLocalId(id).setKind(kind).setName(name).setSpan(span == null ? Span.getDefaultInstance() : span));
        return id;
    }

    public int extColumn(ColRef c) {
        ColumnKey k = ColumnKey.newBuilder().setDataSource(c.table().dataSource()).setSchema(c.table().schema())
                .setTable(c.table().table()).setColumn(c.column()).build();
        return ext("C:" + c.key(), ExternalRef.newBuilder().setColumn(k).build());
    }

    public int extField(String field) {
        return ext("F:" + field, ExternalRef.newBuilder().setField(field).build());
    }

    private int ext(String key, ExternalRef ref) {
        return ext.computeIfAbsent(key, k -> {
            m.addExternals(ref);
            return m.getExternalsCount();          // 인덱스 + 1
        });
    }

    private void edge(Edge.Builder e) {
        Edge b = e.build();
        String key = b.getSrc() + "/" + b.getSrcExt() + ">" + b.getDst() + "/" + b.getDstExt() + ":" + b.getKind()
                + ":" + b.getClause() + ":" + b.getImplicit();
        if (edgeKeys.add(key)) m.addEdges(b);
    }

    private static Edge.Builder e(EdgeKind kind, int conf) {
        return Edge.newBuilder().setKind(kind).setConf(Confidence.forNumber(conf)).setLabel(BranchLabel.TRUE);
    }

    private int conf(int c) {
        return Math.max(baseConf, c);
    }

    /** 바인드 슬롯 등록 (모든 변형에서 공통). */
    public void addBind(BindSlot b) {
        if (bindNode.containsKey(b.slot)) return;
        if (b.dollar) {
            int u = node(NodeKind.UNKNOWN, "${" + b.expr + "}", b.span);
            bindNode.put(b.slot, u);
            for (int fi : rootsOf(b.root)) edge(e(EdgeKind.BIND_TO, Confidence.HEURISTIC_VALUE).setSrc(fi).setDst(u));
            edge(e(EdgeKind.LOCAL_FLOW, Confidence.HEURISTIC_VALUE).setSrc(u).setDst(formalOut));
            return;
        }
        int id = node(NodeKind.BIND, "#{" + b.expr + "}", b.span);
        bindNode.put(b.slot, id);
        List<Integer> roots = rootsOf(b.root);
        int c = formalIn.containsKey(b.root) ? Confidence.EXACT_VALUE
                : formalIn.size() == 1 ? Confidence.RESOLVED_VALUE : Confidence.HEURISTIC_VALUE;
        for (int fi : roots) edge(e(EdgeKind.BIND_TO, conf(c)).setSrc(fi).setDst(id));
    }

    /** 파라미터 이름 → FORMAL_IN. 단일 파라미터면 어떤 이름이든 그 파라미터 (MyBatis 규약). */
    private List<Integer> rootsOf(String root) {
        Integer fi = formalIn.get(root);
        if (fi != null) return List.of(fi);
        return new ArrayList<>(formalIn.values());
    }

    /** DTO 프로퍼티 경유: FIELD(type.prop) ─LOAD→ BIND. */
    public void bindFromField(int slot, String field) {
        Integer b = bindNode.get(slot);
        if (b == null) return;
        edge(e(EdgeKind.LOAD, conf(Confidence.RESOLVED_VALUE)).setSrcExt(extField(field)).setDst(b));
    }

    /** 변형 하나의 분석 결과를 합칩니다. */
    public void addVariant(SqlResult r, String resultType, Map<String, String> resultProps) {
        boolean select = r.type.equals("select");
        // 바인드 → 컬럼
        for (int slot : r.allBinds) {
            Integer b = bindNode.get(slot);
            if (b == null) continue;
            boolean filterOnly = true;
            for (SqlResult.BindUse u : r.binds.getOrDefault(slot, List.of())) {
                if (dollar(u.column())) continue;
                edge(e(EdgeKind.MAPS_TO, conf(u.column().conf())).setSrc(b).setDstExt(extColumn(u.column()))
                        .setClause(Clause.forNumber(u.clause())));
                if (u.clause() == SqlAnalyzer.SELECT_ITEM) filterOnly = false;
            }
            for (SqlResult.Output o : r.outputs) if (o.binds().contains(slot)) filterOnly = false;
            // 바인드 → 결과: 필터만 거치면 암묵적 흐름 [FR-SQ-06, D-06]
            edge(e(EdgeKind.LOCAL_FLOW, baseConf).setSrc(b).setDst(formalOut).setImplicit(filterOnly || !select));
        }
        // 컬럼 → 결과
        for (SqlResult.ColumnUse u : r.uses) {
            if (dollar(u.column())) continue;
            if (u.clause() == SqlAnalyzer.SET || u.clause() == SqlAnalyzer.INSERT_VALUE) {
                edge(e(EdgeKind.MAPS_TO, conf(u.column().conf())).setSrcExt(extColumn(u.column())).setDst(formalOut)
                        .setClause(Clause.forNumber(u.clause())).setImplicit(true));
                continue;
            }
            boolean flows = select && u.top() && u.clause() == SqlAnalyzer.SELECT_ITEM;
            edge(e(EdgeKind.MAPS_TO, conf(u.column().conf())).setSrcExt(extColumn(u.column())).setDst(formalOut)
                    .setClause(Clause.forNumber(u.clause())).setImplicit(!flows));
        }
        // 결과행 → DTO 프로퍼티 (resultType / resultMap)
        if (select && SqlMethodFactory.isDto(resultType)) {           // 별칭(string, map 등)은 제외
            for (SqlResult.Output o : r.outputs) {
                String prop = resultProps.getOrDefault(o.name(), camel(o.name()));
                if (o.name().equals("*")) continue;
                int f = extField(resultType + "." + prop);
                for (ColRef c : o.sources()) if (!dollar(c))
                    edge(e(EdgeKind.MAPS_TO, conf(Math.max(c.conf(), Confidence.RESOLVED_VALUE))).setSrcExt(extColumn(c))
                            .setDstExt(f).setClause(Clause.SELECT_ITEM));
            }
        }
        // 컬럼 → 컬럼 [FR-SQ-04]
        for (SqlResult.Derive d : r.derives) {
            if (dollar(d.source()) || dollar(d.target())) continue;
            int c = Math.max(d.source().conf(), d.target().conf());
            edge(e(EdgeKind.COL_DERIVES, conf(c)).setSrcExt(extColumn(d.source())).setDstExt(extColumn(d.target()))
                    .setClause(Clause.forNumber(d.clause())).setImplicit(d.implicit())
                    .setDerive(DeriveKind.forNumber(d.derive())));
        }
    }

    /** 파싱 실패 변형: UNKNOWN 노드로 남기고 집계 (FR-IN-04). */
    public void addUnparsed(String reason, Span span) {
        int u = node(NodeKind.UNKNOWN, "SQL 파싱 실패: " + reason, span);
        for (int fi : formalIn.values()) edge(e(EdgeKind.BIND_TO, Confidence.HEURISTIC_VALUE).setSrc(fi).setDst(u));
        edge(e(EdgeKind.LOCAL_FLOW, Confidence.HEURISTIC_VALUE).setSrc(u).setDst(formalOut).setImplicit(true));
    }

    /** {@code ${…}} 치환 자리 표시 식별자 (QT_DOLLAR_n)는 실제 테이블·컬럼이 아닙니다. */
    private static boolean dollar(ColRef c) {
        return c.column().startsWith("QT_DOLLAR_") || c.table().table().startsWith("QT_DOLLAR_");
    }

    public static String camel(String col) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : col.toLowerCase().toCharArray()) {
            if (ch == '_') up = true;
            else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }

    public MethodIR build(SqlMeta meta) {
        return m.setSql(meta).build();
    }
}
