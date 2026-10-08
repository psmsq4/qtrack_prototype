package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.catalog.Catalog;
import com.ids.qtrack.next.catalog.DataSourceMap;
import com.ids.qtrack.next.catalog.Names;
import com.ids.qtrack.next.catalog.TableKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.conditional.XorExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.merge.MergeInsert;
import net.sf.jsqlparser.statement.merge.MergeUpdate;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.Pivot;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * SQL 의미 해석 (설계서 3.5, FR-IN-05, FR-SQ-02~06).
 * 바인드 슬롯은 {@code :__b<slot>} 이름 파라미터로 들어온다고 가정합니다 ({@link SqlText}).
 */
public final class SqlAnalyzer {
    public static final int EXACT = 0, RESOLVED = 1, HEURISTIC = 2;
    public static final int SELECT_ITEM = 1, WHERE = 2, SET = 3, INSERT_VALUE = 4, JOIN_ON = 5, GROUP_ORDER = 6;
    public static final int DIRECT = 0, EXPR = 1, AGG = 2;

    private static final Set<String> PSEUDO = Set.of("ROWNUM", "ROWID", "SYSDATE", "SYSTIMESTAMP", "LEVEL", "NEXTVAL",
            "CURRVAL", "USER", "NULL", "TRUE", "FALSE", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CONNECT_BY_ISLEAF",
            "CONNECT_BY_ROOT", "DUAL", "UID");
    private static final Set<String> AGGREGATES = Set.of("SUM", "COUNT", "MAX", "MIN", "AVG", "LISTAGG", "STDDEV",
            "VARIANCE", "MEDIAN", "WM_CONCAT", "XMLAGG", "STRING_AGG", "ARRAY_AGG");

    private final Catalog catalog;
    private final DataSourceMap dsMap;
    private final String owner;
    private SqlResult r;

    public SqlAnalyzer(Catalog catalog, DataSourceMap dsMap, String ownerFqn) {
        this.catalog = catalog == null ? Catalog.empty() : catalog;
        this.dsMap = dsMap == null ? DataSourceMap.defaults() : dsMap;
        this.owner = ownerFqn;
    }

    /** 파싱 + 분석. 파싱 실패는 예외로 알립니다. */
    public SqlResult analyze(String sql) throws Exception {
        Statement st = CCJSqlParserUtil.parse(sql, p -> p.withTimeOut(5000));
        return analyze(st);
    }

    public SqlResult analyze(Statement st) {
        r = new SqlResult();
        switch (st) {
            case Select s -> {
                r.type = "select";
                List<Out> outs = select(s, null, new LinkedHashMap<>(), true);
                for (Out o : outs) {
                    r.outputs.add(new SqlResult.Output(o.name, o.refs, o.binds, o.derive));
                    for (ColRef c : o.refs) r.use(c, SELECT_ITEM, true);
                    for (int b : o.binds) r.allBinds.add(b);
                }
            }
            case Insert i -> insert(i);
            case Update u -> update(u);
            case Delete d -> delete(d);
            case Merge m -> merge(m);
            case CreateTable ct when ct.getSelect() != null -> ctas(ct);
            default -> r.unknowns.add("unsupported statement: " + st.getClass().getSimpleName());
        }
        return r;
    }

    // ───────────────────────────── 범위(scope) 모델 ─────────────────────────────

    /** FROM 항목 하나: 기본 테이블 또는 파생(인라인 뷰·CTE). */
    static final class Source {
        String alias;
        TableKey table;                                     // 기본 테이블
        LinkedHashMap<String, Out> outputs;                 // 파생 테이블 출력

        boolean derived() {
            return outputs != null;
        }
    }

    static final class Scope {
        final List<Source> sources = new ArrayList<>();
        final Scope outer;
        Map<String, Out> selectAliases = Map.of();          // ORDER BY에서 쓰는 select 별칭

        Scope(Scope outer) {
            this.outer = outer;
        }
    }

    /** 출력 컬럼 (이름, 원천 컬럼들, 바인드, DIRECT/EXPR/AGG). */
    record Out(String name, List<ColRef> refs, List<Integer> binds, int derive) {}

    // ───────────────────────────── SELECT ─────────────────────────────

    private List<Out> select(Select s, Scope outer, Map<String, Source> ctes, boolean top) {
        Map<String, Source> visible = new LinkedHashMap<>(ctes);
        if (s.getWithItemsList() != null) {
            for (WithItem<?> w : s.getWithItemsList()) {
                if (w.getSelect() == null) continue;
                List<Out> outs = select(w.getSelect().getSelect(), outer, visible, false);
                if (w.getWithItemList() != null)
                    outs = rename(outs, w.getWithItemList().stream().map(i -> i.getExpression().toString()).toList());
                Source src = new Source();
                src.alias = Names.norm(w.getUnquotedAliasName());
                src.outputs = toMap(outs);
                visible.put(src.alias, src);
            }
        }
        List<Out> outs;
        switch (s) {
            case PlainSelect ps -> outs = plain(ps, outer, visible, top);
            case SetOperationList sol -> {
                outs = new ArrayList<>();
                for (Select sub : sol.getSelects()) {
                    List<Out> o = select(sub, outer, visible, top);
                    if (outs.isEmpty()) outs.addAll(o);
                    else for (int i = 0; i < Math.min(outs.size(), o.size()); i++) outs.set(i, merge(outs.get(i), o.get(i)));
                }
                if (sol.getOrderByElements() != null)
                    for (OrderByElement ob : sol.getOrderByElements()) collect(ob.getExpression(), new Scope(outer), GROUP_ORDER);
            }
            case ParenthesedSelect p -> outs = select(p.getSelect(), outer, visible, top);
            default -> outs = new ArrayList<>();
        }
        return outs;
    }

    private static List<Out> rename(List<Out> outs, List<String> names) {
        List<Out> r = new ArrayList<>();
        for (int i = 0; i < outs.size(); i++) {
            Out o = outs.get(i);
            r.add(i < names.size() ? new Out(Names.norm(names.get(i)), o.refs, o.binds, o.derive) : o);
        }
        return r;
    }

    private static Out merge(Out a, Out b) {
        List<ColRef> refs = new ArrayList<>(a.refs);
        for (ColRef c : b.refs) if (!refs.contains(c)) refs.add(c);
        List<Integer> binds = new ArrayList<>(a.binds);
        binds.addAll(b.binds);
        return new Out(a.name, refs, binds, Math.max(a.derive, b.derive));
    }

    private static LinkedHashMap<String, Out> toMap(List<Out> outs) {
        LinkedHashMap<String, Out> m = new LinkedHashMap<>();
        for (Out o : outs) m.putIfAbsent(o.name, o);
        return m;
    }

    private List<Out> plain(PlainSelect ps, Scope outer, Map<String, Source> ctes, boolean top) {
        Scope sc = new Scope(outer);
        if (ps.getFromItem() != null) addFrom(ps.getFromItem(), sc, ctes);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) addFrom(j.getFromItem(), sc, ctes);
            for (Join j : ps.getJoins()) {
                if (j.getOnExpressions() != null) for (Expression on : j.getOnExpressions()) condition(on, sc, JOIN_ON);
                if (j.getUsingColumns() != null)
                    for (Column c : j.getUsingColumns())
                        for (Source s : sc.sources)
                            for (ColRef ref : columnOf(s, c.getColumnName(), EXACT)) r.use(ref, JOIN_ON, false);
            }
        }
        List<Out> outs = new ArrayList<>();
        int n = 0;
        for (SelectItem<?> item : ps.getSelectItems()) {
            n++;
            Expression e = item.getExpression();
            if (e instanceof AllTableColumns atc) {
                Source s = findSource(sc, atc.getTable());
                if (s != null) outs.addAll(star(s));
                else r.unknowns.add("unknown qualifier " + atc);
            } else if (e instanceof AllColumns) {
                for (Source s : sc.sources) outs.addAll(star(s));
            } else {
                Parts p = collect(e, sc, SELECT_ITEM);
                String name = item.getAlias() != null ? Names.norm(item.getAlias().getName())
                        : e instanceof Column c ? Names.norm(c.getColumnName()) : "EXPR_" + n;
                int derive = e instanceof Column ? DIRECT : p.agg ? AGG : EXPR;
                outs.add(new Out(name, p.cols, p.binds, derive));
            }
        }
        Map<String, Out> aliases = new LinkedHashMap<>();
        for (Out o : outs) aliases.putIfAbsent(o.name, o);
        sc.selectAliases = aliases;
        if (ps.getWhere() != null) condition(ps.getWhere(), sc, WHERE);
        if (ps.getHaving() != null) condition(ps.getHaving(), sc, WHERE);
        if (ps.getOracleHierarchical() != null) {
            if (ps.getOracleHierarchical().getStartExpression() != null)
                condition(ps.getOracleHierarchical().getStartExpression(), sc, WHERE);
            if (ps.getOracleHierarchical().getConnectExpression() != null)
                condition(ps.getOracleHierarchical().getConnectExpression(), sc, WHERE);
        }
        GroupByElement g = ps.getGroupBy();
        if (g != null && g.getGroupByExpressionList() != null)
            for (Object o : g.getGroupByExpressionList()) if (o instanceof Expression e) collect(e, sc, GROUP_ORDER);
        if (ps.getOrderByElements() != null)
            for (OrderByElement ob : ps.getOrderByElements()) collect(ob.getExpression(), sc, GROUP_ORDER);
        if (!top) for (Out o : outs) for (ColRef c : o.refs) r.use(c, SELECT_ITEM, false);
        return outs;
    }

    /** {@code *} / {@code t.*} 치환 (FR-SQ-03). */
    private List<Out> star(Source s) {
        List<Out> outs = new ArrayList<>();
        if (s.derived()) {
            outs.addAll(s.outputs.values());                         // 인라인 뷰·CTE: 안쪽 프로젝션 (메타 불필요)
        } else {
            List<String> cols = catalog.columns(s.table);
            if (cols != null && !cols.isEmpty()) {
                for (String c : cols) outs.add(new Out(c, List.of(new ColRef(s.table, c, RESOLVED)), List.of(), DIRECT));
            } else {
                outs.add(new Out("*", List.of(new ColRef(s.table, "*", HEURISTIC)), List.of(), DIRECT));
            }
        }
        return outs;
    }

    private TableKey resolved(TableKey t) {
        TableKey k = catalog.resolve(t);
        return k == null ? t : k;
    }

    private void addFrom(FromItem fi, Scope sc, Map<String, Source> ctes) {
        switch (fi) {
            case Table t -> {
                Source s = new Source();
                String name = Names.norm(t.getName());
                String alias = t.getAlias() != null ? Names.norm(t.getAlias().getName()) : name;
                if ((t.getSchemaName() == null || t.getSchemaName().isEmpty()) && ctes.containsKey(name)) {
                    s.outputs = ctes.get(name).outputs;
                } else {
                    s.table = resolved(dsMap.normalize(owner, t.getSchemaName(), t.getName()));
                    r.readTables.add(s.table);
                }
                s.alias = alias;
                sc.sources.add(s);
                pivot(t.getPivot(), sc);
            }
            case LateralSubSelect l -> {
                Source s = new Source();
                s.outputs = toMap(select(l.getSelect(), sc, ctes, false));
                s.alias = l.getAlias() != null ? Names.norm(l.getAlias().getName()) : "";
                sc.sources.add(s);
            }
            case ParenthesedSelect p -> {
                Source s = new Source();
                s.outputs = toMap(select(p.getSelect(), null, ctes, false));
                s.alias = p.getAlias() != null ? Names.norm(p.getAlias().getName()) : "";
                sc.sources.add(s);
                pivot(p.getPivot(), sc);
            }
            case ParenthesedFromItem pf -> {
                addFrom(pf.getFromItem(), sc, ctes);
                if (pf.getJoins() != null) for (Join j : pf.getJoins()) {
                    addFrom(j.getFromItem(), sc, ctes);
                    if (j.getOnExpressions() != null) for (Expression on : j.getOnExpressions()) condition(on, sc, JOIN_ON);
                }
            }
            default -> {
                Source s = new Source();                       // 테이블 함수 등: 출력 미상
                s.outputs = new LinkedHashMap<>();
                s.alias = fi.getAlias() != null ? Names.norm(fi.getAlias().getName()) : "";
                sc.sources.add(s);
                r.unknowns.add("opaque from item: " + fi);
            }
        }
    }

    private void pivot(Pivot p, Scope sc) {
        if (p == null) return;
        if (p.getFunctionItems() != null) for (SelectItem<?> f : p.getFunctionItems()) collect(f.getExpression(), sc, GROUP_ORDER);
        if (p.getForColumns() != null) for (Column c : p.getForColumns()) collect(c, sc, GROUP_ORDER);
    }

    // ───────────────────────────── 이름 해석 (FR-SQ-02) ─────────────────────────────

    private Source findSource(Scope sc, Table q) {
        if (q == null || q.getName() == null) return null;
        String qn = Names.norm(q.getName());
        for (Scope s = sc; s != null; s = s.outer) {
            for (Source src : s.sources) if (qn.equals(src.alias)) return src;
            for (Source src : s.sources) if (src.table != null && qn.equals(src.table.table())) return src;
        }
        return null;
    }

    /** 출처 s에서 컬럼 name. */
    private List<ColRef> columnOf(Source s, String name, int conf) {
        String n = Names.norm(name);
        if (s.derived()) {
            Out o = s.outputs.get(n);
            if (o != null) return o.refs.stream().map(c -> c.withConf(conf)).toList();
            Out star = s.outputs.get("*");
            if (star != null)                                    // 안쪽이 TABLE.* 라면 같은 테이블의 컬럼으로 추정
                return star.refs.stream().map(c -> new ColRef(c.table(), n, HEURISTIC)).toList();
            return List.of();
        }
        return List.of(new ColRef(s.table, n, conf));
    }

    /** 설계서 3.5 resolve(col, scope). 빈 목록이면 UNKNOWN. */
    private List<ColRef> resolve(Column col, Scope sc) {
        String name = Names.norm(col.getColumnName());
        if (col.getTable() != null && col.getTable().getName() != null) {
            Source s = findSource(sc, col.getTable());
            if (s == null) {
                r.unknowns.add("unknown qualifier: " + col);
                return List.of();
            }
            return columnOf(s, name, EXACT);
        }
        for (Scope s = sc; s != null; s = s.outer) {
            List<ColRef> res = resolveIn(name, s);
            if (res != null) return res;
        }
        if (sc.selectAliases.containsKey(name)) return sc.selectAliases.get(name).refs;   // ORDER BY 별칭
        r.unknowns.add("unresolved column: " + name);
        return List.of();
    }

    /** 한 범위 안에서 해석. null이면 바깥 범위(상관 서브쿼리)로. */
    private List<ColRef> resolveIn(String name, Scope sc) {
        if (sc.sources.isEmpty()) return null;
        if (sc.sources.size() == 1) {
            Source only = sc.sources.getFirst();
            List<ColRef> c = columnOf(only, name, EXACT);
            if (sc.outer == null) return c.isEmpty() ? null : c;
            // 바깥 범위가 있으면 안쪽 출처에 컬럼이 확인될 때만 확정
            if (only.derived()) return c.isEmpty() ? null : c;
            if (!catalog.has(only.table) || catalog.hasColumn(only.table, name)) return c;
            return null;
        }
        List<ColRef> confirmed = new ArrayList<>();
        int confirmedSources = 0;
        List<Source> unknownBase = new ArrayList<>();
        for (Source src : sc.sources) {
            if (src.derived()) {
                if (src.outputs.containsKey(name)) {
                    confirmed.addAll(columnOf(src, name, EXACT));
                    confirmedSources++;
                }
            } else if (catalog.has(src.table)) {
                if (catalog.hasColumn(src.table, name)) {
                    confirmed.add(new ColRef(src.table, name, RESOLVED));
                    confirmedSources++;
                }
            } else {
                unknownBase.add(src);
            }
        }
        if (confirmedSources == 1 && unknownBase.isEmpty()) return confirmed;
        if (confirmedSources > 1) {
            r.unknowns.add("ambiguous column: " + name);
            return List.of();
        }
        if (!unknownBase.isEmpty()) {
            List<ColRef> h = new ArrayList<>();
            for (ColRef c : confirmed) h.add(c.withConf(HEURISTIC));
            for (Source s : unknownBase) h.add(new ColRef(s.table, name, HEURISTIC));
            return h;
        }
        return null;
    }

    // ───────────────────────────── 식 수집 ─────────────────────────────

    static final class Parts {
        final List<ColRef> cols = new ArrayList<>();
        final List<Integer> binds = new ArrayList<>();
        boolean agg;
    }

    /** 식 안의 컬럼·바인드를 모으고 컬럼 사용을 기록합니다. 서브쿼리는 현재 범위를 바깥 범위로 분석합니다. */
    private Parts collect(Expression e, Scope sc, int clause) {
        Parts p = new Parts();
        if (e == null) return p;
        e.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S ctx) {
                String n = Names.norm(column.getColumnName());
                if (column.getTable() == null && PSEUDO.contains(n)) return null;
                if (n.equals("NEXTVAL") || n.equals("CURRVAL")) return null;
                for (ColRef c : resolve(column, sc)) {
                    if (!p.cols.contains(c)) p.cols.add(c);
                    if (clause != SELECT_ITEM) r.use(c, clause, false);
                }
                return null;
            }

            @Override
            public <S> Void visit(JdbcNamedParameter jp, S ctx) {
                int slot = SqlText.slotOf(jp.getName());
                if (slot >= 0) {
                    p.binds.add(slot);
                    r.allBinds.add(slot);
                }
                return null;
            }

            @Override
            public <S> Void visit(JdbcParameter jp, S ctx) {
                return null;
            }

            @Override
            public <S> Void visit(Function f, S ctx) {
                if (f.getName() != null && AGGREGATES.contains(f.getName().toUpperCase(Locale.ROOT))) p.agg = true;
                return super.visit(f, ctx);
            }

            @Override
            public <S> Void visit(AnalyticExpression a, S ctx) {
                if (a.getName() != null && AGGREGATES.contains(a.getName().toUpperCase(Locale.ROOT))) p.agg = true;
                return super.visit(a, ctx);
            }

            @Override
            public <S> Void visit(ParenthesedSelect sub, S ctx) {
                absorb(select(sub.getSelect(), sc, Map.of(), false));
                return null;
            }

            @Override
            public <S> Void visit(Select sub, S ctx) {
                absorb(select(sub, sc, Map.of(), false));
                return null;
            }

            private void absorb(List<Out> outs) {
                for (Out o : outs) {
                    for (ColRef c : o.refs) if (!p.cols.contains(c)) p.cols.add(c);
                    p.binds.addAll(o.binds);
                    if (o.derive == AGG) p.agg = true;
                }
            }
        });
        return p;
    }

    /** 조건식: 비교 연산의 양쪽을 짝지어 바인드 → 컬럼을 기록합니다. */
    private void condition(Expression e, Scope sc, int clause) {
        switch (e) {
            case AndExpression a -> { condition(a.getLeftExpression(), sc, clause); condition(a.getRightExpression(), sc, clause); }
            case OrExpression o -> { condition(o.getLeftExpression(), sc, clause); condition(o.getRightExpression(), sc, clause); }
            case XorExpression x -> { condition(x.getLeftExpression(), sc, clause); condition(x.getRightExpression(), sc, clause); }
            case NotExpression n -> condition(n.getExpression(), sc, clause);
            case ParenthesedExpressionList<?> pl when pl.size() == 1 && pl.getFirst() instanceof Expression inner
                    && !(inner instanceof Column) -> condition(inner, sc, clause);
            case ComparisonOperator c ->
                    pair(collect(c.getLeftExpression(), sc, clause), collect(c.getRightExpression(), sc, clause), clause);
            case LikeExpression l ->
                    pair(collect(l.getLeftExpression(), sc, clause), collect(l.getRightExpression(), sc, clause), clause);
            case InExpression in ->
                    pair(collect(in.getLeftExpression(), sc, clause), collect(in.getRightExpression(), sc, clause), clause);
            case Between b -> {
                Parts left = collect(b.getLeftExpression(), sc, clause);
                pair(left, collect(b.getBetweenExpressionStart(), sc, clause), clause);
                pair(left, collect(b.getBetweenExpressionEnd(), sc, clause), clause);
            }
            case BinaryExpression b ->
                    pair(collect(b.getLeftExpression(), sc, clause), collect(b.getRightExpression(), sc, clause), clause);
            default -> {
                Parts p = collect(e, sc, clause);
                for (int b : p.binds) for (ColRef c : p.cols) r.bind(b, c, clause);
            }
        }
    }

    private void pair(Parts a, Parts b, int clause) {
        for (int bind : a.binds) for (ColRef c : b.cols) r.bind(bind, c, clause);
        for (int bind : b.binds) for (ColRef c : a.cols) r.bind(bind, c, clause);
    }

    // ───────────────────────────── DML ─────────────────────────────

    private Source baseSource(Table t) {
        Source s = new Source();
        s.table = resolved(dsMap.normalize(owner, t.getSchemaName(), t.getName()));
        s.alias = t.getAlias() != null ? Names.norm(t.getAlias().getName()) : Names.norm(t.getName());
        return s;
    }

    /** 대상 테이블의 컬럼 목록: 명시 목록 → 카탈로그 순서 → null(UNKNOWN). */
    private List<ColRef> targetColumns(Source tgt, ExpressionList<Column> cols) {
        if (cols != null && !cols.isEmpty())
            return cols.stream().map(c -> new ColRef(tgt.table, Names.norm(c.getColumnName()), EXACT)).toList();
        List<String> cat = catalog.columns(tgt.table);
        if (cat != null && !cat.isEmpty()) return cat.stream().map(c -> new ColRef(tgt.table, c, RESOLVED)).toList();
        return null;
    }

    private void insert(Insert ins) {
        r.type = "insert";
        Source tgt = baseSource(ins.getTable());
        r.writeTables.add(tgt.table);
        List<ColRef> cols = targetColumns(tgt, ins.getColumns());
        if (cols != null) for (ColRef c : cols) r.use(c, INSERT_VALUE, false);
        Select sel = ins.getSelect();
        if (sel == null) return;
        Values values = sel instanceof Values v ? v
                : sel instanceof ParenthesedSelect ps && ps.getSelect() instanceof Values pv ? pv : null;
        if (values != null) {
            Scope sc = new Scope(null);
            List<Expression> items = new ArrayList<>();
            for (Object o : values.getExpressions()) items.add((Expression) o);
            List<List<Expression>> tuples = new ArrayList<>();
            boolean multiRow = !items.isEmpty() && items.stream().allMatch(x -> x instanceof ParenthesedExpressionList<?>);
            if (multiRow) {
                for (Expression row : items) {
                    List<Expression> t = new ArrayList<>();
                    for (Object o : (ParenthesedExpressionList<?>) row) t.add((Expression) o);
                    tuples.add(t);
                }
            } else tuples.add(items);
            for (List<Expression> tuple : tuples) {
                for (int i = 0; i < tuple.size(); i++) {
                    Parts p = collect(tuple.get(i), sc, INSERT_VALUE);
                    ColRef target = cols != null && i < cols.size() ? cols.get(i) : null;
                    if (target == null) {
                        target = new ColRef(tgt.table, "*", HEURISTIC);
                        r.unknowns.add("insert value position " + (i + 1) + " unmapped: " + tgt.table);
                    }
                    for (int b : p.binds) r.bind(b, target, INSERT_VALUE);
                    for (ColRef src : p.cols)
                        r.derive(target, src, tuple.get(i) instanceof Column ? DIRECT : EXPR, SELECT_ITEM, false);
                }
            }
            return;
        }
        // INSERT … SELECT (FR-SQ-04)
        int mark = r.uses.size();
        List<Out> outs = select(sel, null, new LinkedHashMap<>(), false);
        List<ColRef> filters = filterRefsSince(mark);
        for (int i = 0; i < outs.size(); i++) {
            Out o = outs.get(i);
            ColRef target;
            if (cols != null && i < cols.size()) target = cols.get(i);
            else {
                r.unknowns.add("insert-select position " + (i + 1) + " unmapped: " + tgt.table);
                target = new ColRef(tgt.table, "*", HEURISTIC);
            }
            for (ColRef src : o.refs) r.derive(target, src.withConf(target.conf()), o.derive, SELECT_ITEM, false);
            for (int b : o.binds) r.bind(b, target, INSERT_VALUE);
        }
        if (cols != null) for (ColRef f : filters) for (ColRef t : cols) r.derive(t, f, EXPR, WHERE, true);
    }

    /** mark 이후 기록된 WHERE/JOIN_ON 사용 컬럼 (암묵적 흐름). */
    private List<ColRef> filterRefsSince(int from) {
        Set<ColRef> s = new LinkedHashSet<>();
        for (int i = from; i < r.uses.size(); i++) {
            SqlResult.ColumnUse u = r.uses.get(i);
            if (u.clause() == WHERE || u.clause() == JOIN_ON) s.add(u.column());
        }
        return new ArrayList<>(s);
    }

    private void update(Update u) {
        r.type = "update";
        Scope sc = new Scope(null);
        Source tgt = baseSource(u.getTable());
        sc.sources.add(tgt);
        r.writeTables.add(tgt.table);
        if (u.getFromItem() != null) addFrom(u.getFromItem(), sc, Map.of());
        if (u.getJoins() != null) for (Join j : u.getJoins()) addFrom(j.getFromItem(), sc, Map.of());
        sets(u.getUpdateSets(), tgt, sc);
        if (u.getWhere() != null) condition(u.getWhere(), sc, WHERE);
    }

    private void sets(List<UpdateSet> sets, Source tgt, Scope sc) {
        if (sets == null) return;
        for (UpdateSet us : sets) {
            List<ColRef> targets = new ArrayList<>();
            for (Column c : us.getColumns()) {
                ColRef t = new ColRef(tgt.table, Names.norm(c.getColumnName()), EXACT);
                targets.add(t);
                r.use(t, SET, false);
            }
            List<Expression> vals = new ArrayList<>();
            for (Object o : us.getValues()) vals.add((Expression) o);
            if (targets.size() > 1 && vals.size() == 1 && vals.getFirst() instanceof ParenthesedSelect ps) {
                List<Out> outs = select(ps.getSelect(), sc, Map.of(), false);   // SET (a,b) = (SELECT x,y …)
                for (int i = 0; i < Math.min(outs.size(), targets.size()); i++) {
                    for (ColRef s : outs.get(i).refs) r.derive(targets.get(i), s, outs.get(i).derive, SELECT_ITEM, false);
                    for (int b : outs.get(i).binds) r.bind(b, targets.get(i), SET);
                }
                continue;
            }
            for (int i = 0; i < vals.size(); i++) {
                ColRef t = i < targets.size() ? targets.get(i) : targets.getLast();
                Parts p = collect(vals.get(i), sc, SET);
                for (int b : p.binds) r.bind(b, t, SET);
                int kind = vals.get(i) instanceof Column ? DIRECT : p.agg ? AGG : EXPR;
                for (ColRef s : p.cols) r.derive(t, s, kind, SELECT_ITEM, false);
            }
        }
    }

    private void delete(Delete d) {
        r.type = "delete";
        Scope sc = new Scope(null);
        Source tgt = baseSource(d.getTable());
        sc.sources.add(tgt);
        r.writeTables.add(tgt.table);
        if (d.getWhere() != null) condition(d.getWhere(), sc, WHERE);
    }

    private void merge(Merge m) {
        r.type = "merge";
        Scope sc = new Scope(null);
        Source tgt = baseSource(m.getTable());
        sc.sources.add(tgt);
        r.writeTables.add(tgt.table);
        if (m.getFromItem() != null) addFrom(m.getFromItem(), sc, Map.of());
        int mark = r.uses.size();
        if (m.getOnCondition() != null) condition(m.getOnCondition(), sc, JOIN_ON);
        List<ColRef> on = filterRefsSince(mark).stream().filter(c -> !c.table().equals(tgt.table)).toList();
        int before = r.derives.size();
        MergeUpdate mu = m.getMergeUpdate();
        if (mu != null) {
            sets(mu.getUpdateSets(), tgt, sc);
            if (mu.getWhereCondition() != null) condition(mu.getWhereCondition(), sc, WHERE);
        }
        MergeInsert mi = m.getMergeInsert();
        if (mi != null) {
            List<ColRef> cols = targetColumns(tgt, mi.getColumns());
            List<Expression> vals = new ArrayList<>();
            if (mi.getValues() != null) for (Object o : mi.getValues()) vals.add((Expression) o);
            for (int i = 0; i < vals.size(); i++) {
                ColRef t = cols != null && i < cols.size() ? cols.get(i) : new ColRef(tgt.table, "*", HEURISTIC);
                r.use(t, INSERT_VALUE, false);
                Parts p = collect(vals.get(i), sc, INSERT_VALUE);
                for (int b : p.binds) r.bind(b, t, INSERT_VALUE);
                for (ColRef s : p.cols)
                    r.derive(t, s, vals.get(i) instanceof Column ? DIRECT : p.agg ? AGG : EXPR, SELECT_ITEM, false);
            }
        }
        Set<ColRef> written = new LinkedHashSet<>();
        for (int i = before; i < r.derives.size(); i++) written.add(r.derives.get(i).target());
        for (ColRef t : written) for (ColRef s : on) r.derive(t, s, EXPR, JOIN_ON, true);
    }

    private void ctas(CreateTable ct) {
        r.type = "ctas";
        TableKey t = dsMap.normalize(owner, ct.getTable().getSchemaName(), ct.getTable().getName());
        r.writeTables.add(t);
        List<Out> outs = select(ct.getSelect(), null, new LinkedHashMap<>(), false);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < outs.size(); i++) {
            String name = ct.getColumns() != null && i < ct.getColumns().size()
                    ? Names.norm(ct.getColumns().get(i)) : outs.get(i).name;
            names.add(name);
            ColRef target = new ColRef(t, name, EXACT);
            for (ColRef s : outs.get(i).refs) r.derive(target, s, outs.get(i).derive, SELECT_ITEM, false);
        }
        r.createdTable = t;
        r.createdColumns = names;
    }
}
