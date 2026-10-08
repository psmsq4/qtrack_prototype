package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.catalog.TableKey;

/** 해석된 컬럼 참조 (ColumnKey + 신뢰도). column="*"는 TABLE.* (FR-SQ-03). */
public record ColRef(TableKey table, String column, int conf) {
    public ColRef withConf(int c) {
        return new ColRef(table, column, Math.max(conf, c));
    }

    public String key() {
        return table.dataSource() + ":" + table + "." + column;
    }

    @Override
    public String toString() {
        return table + "." + column;
    }
}
