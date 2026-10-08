package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.catalog.Catalog;
import com.ids.qtrack.next.catalog.DataSourceMap;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.SqlMeta;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.CreateTable;

/**
 * PreparedSql → 합성 MethodIR. 2단계로 씁니다.
 * <ol>
 *   <li>{@link #registerCtas}: 모든 SQL에서 CTAS를 먼저 찾아 카탈로그에 등록 (FR-SQ-04: 같은 입력 안의 이후 SQL에서 카탈로그처럼 사용)</li>
 *   <li>{@link #build}: 변형마다 JSqlParser로 분석해 합성 메서드를 만든다 (설계서 3.4 바인드와 컬럼의 결합)</li>
 * </ol>
 */
public final class SqlMethodFactory {
    private final Catalog catalog;
    private final DataSourceMap dsMap;
    public int statements, variants, parseFailures, cappedStatements;

    public SqlMethodFactory(Catalog catalog, DataSourceMap dsMap) {
        this.catalog = catalog;
        this.dsMap = dsMap;
    }

    public void registerCtas(PreparedSql p) {
        for (String v : p.variants()) {
            if (!v.toUpperCase().contains("CREATE")) continue;
            try {
                Statement st = CCJSqlParserUtil.parse(v);
                if (st instanceof CreateTable ct && ct.getSelect() != null) {
                    SqlResult r = new SqlAnalyzer(catalog, dsMap, p.className()).analyze(st);
                    if (r.createdTable != null) catalog.register(r.createdTable, r.createdColumns, "ctas:" + p.signature());
                }
            } catch (Exception ignored) {
                // build 단계에서 실패로 집계
            }
        }
    }

    public MethodIR build(PreparedSql p) {
        statements++;
        if (p.capped()) cappedStatements++;
        SqlMethodBuilder b = new SqlMethodBuilder(p.signature(), p.className(), p.name(), p.span(), p.params(), p.capped());
        for (SqlMethodBuilder.BindSlot s : p.slots()) b.addBind(s);
        if (isDto(p.parameterType()))                 // DTO 파라미터: 프로퍼티별 FIELD 근사 (설계서 3.4)
            for (SqlMethodBuilder.BindSlot s : p.slots())
                if (!s.dollar()) b.bindFromField(s.slot(), p.parameterType() + "." + s.root());
        int fails = 0;
        Set<String> tables = new LinkedHashSet<>();
        String type = p.statementType();
        for (String v : p.variants()) {
            variants++;
            try {
                SqlResult r = new SqlAnalyzer(catalog, dsMap, p.className()).analyze(v);
                type = r.type;
                r.readTables.forEach(t -> tables.add(t.toString()));
                r.writeTables.forEach(t -> tables.add(t.toString()));
                b.addVariant(r, p.resultType(), p.resultProps());
            } catch (Throwable e) {
                fails++;
                parseFailures++;
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
                b.addUnparsed(msg.length() > 120 ? msg.substring(0, 120) : msg, p.span());
            }
        }
        SqlMeta meta = SqlMeta.newBuilder()
                .setNamespace(p.className()).setStatementId(p.name()).setStatementType(type)
                .addAllParamNames(p.params().isEmpty() ? List.of("_parameter") : p.params())
                .setVariants(p.variants().size()).setParseFailures(fails).setDynamic(p.dynamic()).setCapped(p.capped())
                .addAllTables(tables)
                .setParameterType(p.parameterType() == null ? "" : p.parameterType())
                .setResultType(p.resultType() == null ? "" : p.resultType())
                .setSampleSql(p.variants().isEmpty() ? "" : SqlText.compact(p.variants().getFirst()))
                .build();
        return b.build(meta);
    }

    /** 단순 타입(문자열, 숫자, Map 등)이 아니고 패키지가 있는 클래스면 DTO로 봅니다. */
    public static boolean isDto(String type) {
        if (type == null || type.isEmpty() || !type.contains(".")) return false;
        return !(type.startsWith("java.") || type.startsWith("javax."));
    }
}
