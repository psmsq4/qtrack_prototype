package com.ids.qtrack.next.catalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;

/**
 * 카탈로그 입력 (FR-SQ-01).
 * <ul>
 *   <li>DDL(.sql/.ddl): CREATE TABLE 문을 JSqlParser로 읽습니다.</li>
 *   <li>CSV(.csv): 헤더로 열을 찾습니다. 표준 헤더는 DATA_SOURCE, SCHEMA|OWNER, TABLE_NAME, COLUMN_NAME, COLUMN_ID.</li>
 *   <li>Q-Track QT_META_POPULATOR 내보내기(헤더에 CLASS_ID, ABBR_NAME 존재): CLASS_ID=9001 행을
 *       테이블=ABBR_NAME, 스키마=BIZ_KEY2, 데이터소스=SYS_ID, 순서=OBJ_SEQ, 컬럼=시스템 속성
 *       {@code qtrack.qtmeta.column}(기본 OBJ_RMK2)으로 읽습니다.</li>
 * </ul>
 */
public final class CatalogLoader {
    private CatalogLoader() {}

    public static Catalog load(List<Path> files, DataSourceMap dsMap) throws IOException {
        Catalog c = Catalog.empty();
        for (Path f : files) {
            String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".csv")) loadCsv(c, f, dsMap);
            else loadDdl(c, Files.readString(f), dsMap);
        }
        return c;
    }

    public static void loadDdl(Catalog c, String ddl, DataSourceMap dsMap) {
        for (String stmt : splitStatements(ddl)) {
            if (!stmt.toUpperCase(Locale.ROOT).contains("CREATE")) continue;
            try {
                Statements ss = CCJSqlParserUtil.parseStatements(stmt);
                for (Statement s : ss) {
                    if (s instanceof CreateTable ct && ct.getColumnDefinitions() != null) {
                        TableKey t = dsMap.normalize(null, ct.getTable().getSchemaName(), ct.getTable().getName());
                        for (ColumnDefinition cd : ct.getColumnDefinitions()) c.addColumn(t, cd.getColumnName());
                    }
                }
            } catch (Exception ignored) {
                // 카탈로그 DDL의 비표준 구문(스토리지 절 등)은 건너뜁니다.
            }
        }
    }

    static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char ch : sql.toCharArray()) {
            if (ch == '\'') q = !q;
            if (ch == ';' && !q) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        if (!cur.toString().isBlank()) out.add(cur.toString());
        return out;
    }

    public static void loadCsv(Catalog c, Path f, DataSourceMap dsMap) throws IOException {
        List<String> lines = Files.readAllLines(f);
        if (lines.isEmpty()) return;
        List<String> header = parseCsvLine(lines.getFirst()).stream().map(h -> h.trim().toUpperCase(Locale.ROOT)).toList();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.put(header.get(i), i);
        boolean qtmeta = col.containsKey("CLASS_ID") && col.containsKey("ABBR_NAME");
        record Row(TableKey t, String col, int order) {}
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            List<String> v = parseCsvLine(line);
            if (qtmeta) {
                if (!"9001".equals(get(v, col, "CLASS_ID"))) continue;
                String column = get(v, col, System.getProperty("qtrack.qtmeta.column", "OBJ_RMK2"));
                String ds = get(v, col, "SYS_ID");
                TableKey t = new TableKey(ds == null || ds.isEmpty() ? dsMap.forOwner(null).dataSource() : ds,
                        get(v, col, "BIZ_KEY2"), get(v, col, "ABBR_NAME"));
                rows.add(new Row(t, column, parseInt(get(v, col, "OBJ_SEQ"))));
            } else {
                String schema = first(v, col, "SCHEMA", "OWNER", "SCHEMA_NAME", "TABLE_SCHEMA");
                String table = first(v, col, "TABLE_NAME", "TABLE");
                String column = first(v, col, "COLUMN_NAME", "COLUMN");
                String ds = first(v, col, "DATA_SOURCE", "DATASOURCE");
                if (table == null || column == null) continue;
                TableKey base = dsMap.normalize(null, schema, table);
                TableKey t = ds == null || ds.isEmpty() ? base : new TableKey(ds, base.schema(), base.table());
                rows.add(new Row(t, column, parseInt(first(v, col, "COLUMN_ID", "ORDINAL_POSITION", "COL_ORDER"))));
            }
        }
        rows.sort((a, b) -> Integer.compare(a.order, b.order));
        for (Row r : rows) if (r.col != null && !r.col.isEmpty()) c.addColumn(r.t, r.col);
    }

    private static int parseInt(String s) {
        try {
            return s == null ? Integer.MAX_VALUE : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String get(List<String> v, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= v.size() ? null : v.get(i).trim();
    }

    private static String first(List<String> v, Map<String, Integer> col, String... names) {
        for (String n : names) {
            String s = get(v, col, n);
            if (s != null) return s;
        }
        return null;
    }

    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (ch == '"') q = false;
                else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
