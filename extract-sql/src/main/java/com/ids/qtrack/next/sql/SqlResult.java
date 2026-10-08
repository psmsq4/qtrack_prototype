package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.catalog.TableKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQL 문 하나의 분석 결과. */
public final class SqlResult {
    /** 바인드 슬롯이 연결된 컬럼과 절. */
    public record BindUse(int slot, ColRef column, int clause) {}

    /** 컬럼 사용 위치. top = 최상위 SELECT 목록(결과행으로 흐름) 여부. */
    public record ColumnUse(ColRef column, int clause, boolean top) {}

    /** 결과행의 출력 컬럼 하나. */
    public record Output(String name, List<ColRef> sources, List<Integer> binds, int derive) {}

    /** COL_DERIVES: target ← source. */
    public record Derive(ColRef target, ColRef source, int derive, int clause, boolean implicit) {}

    public String type = "select";
    public final Map<Integer, List<BindUse>> binds = new LinkedHashMap<>();
    public final Set<Integer> allBinds = new LinkedHashSet<>();
    public final List<ColumnUse> uses = new ArrayList<>();
    public final List<Output> outputs = new ArrayList<>();
    public final List<Derive> derives = new ArrayList<>();
    public final Set<TableKey> readTables = new LinkedHashSet<>();
    public final Set<TableKey> writeTables = new LinkedHashSet<>();
    public final List<String> unknowns = new ArrayList<>();
    /** CTAS로 생성된 테이블과 출력 컬럼 (카탈로그 등록용). */
    public TableKey createdTable;
    public List<String> createdColumns = List.of();

    void bind(int slot, ColRef col, int clause) {
        allBinds.add(slot);
        if (col == null) return;
        List<BindUse> l = binds.computeIfAbsent(slot, k -> new ArrayList<>());
        BindUse u = new BindUse(slot, col, clause);
        if (!l.contains(u)) l.add(u);
    }

    void use(ColRef c, int clause, boolean top) {
        ColumnUse u = new ColumnUse(c, clause, top);
        if (!uses.contains(u)) uses.add(u);
    }

    void derive(ColRef t, ColRef s, int kind, int clause, boolean implicit) {
        Derive d = new Derive(t, s, kind, clause, implicit);
        if (!derives.contains(d)) derives.add(d);
    }
}
