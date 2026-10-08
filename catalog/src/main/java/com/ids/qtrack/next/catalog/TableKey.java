package com.ids.qtrack.next.catalog;

/** 테이블 식별 (데이터소스, 스키마, 테이블). FR-SQ-05: 같은 이름의 테이블이 여러 DB·스키마에 있을 수 있음. */
public record TableKey(String dataSource, String schema, String table) {
    public TableKey {
        dataSource = dataSource == null || dataSource.isEmpty() ? DataSourceMap.DEFAULT : dataSource;
        schema = schema == null ? "" : Names.norm(schema);
        table = Names.norm(table);
    }

    @Override
    public String toString() {
        return (schema.isEmpty() ? "" : schema + ".") + table;
    }
}
