package com.ids.qtrack.next.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB 카탈로그 스냅샷 (FR-SQ-01): 테이블 → 컬럼 목록(순서 유지).
 * 조회는 (데이터소스, 스키마, 테이블)이 정확히 같으면 그것을, 아니면 스키마를 무시하고 테이블 이름으로 찾습니다.
 * CTAS로 만든 테이블은 {@link #register}로 같은 입력 안의 이후 SQL에서 카탈로그처럼 씁니다 (FR-SQ-04).
 */
public final class Catalog {
    private final Map<TableKey, List<String>> tables = new LinkedHashMap<>();
    private final Map<String, List<TableKey>> byName = new LinkedHashMap<>();
    private final Map<TableKey, String> origin = new LinkedHashMap<>();

    public static Catalog empty() {
        return new Catalog();
    }

    public boolean isEmpty() {
        return tables.isEmpty();
    }

    public int tableCount() {
        return tables.size();
    }

    public void addColumn(TableKey t, String column) {
        List<String> cols = tables.computeIfAbsent(t, k -> {
            byName.computeIfAbsent(k.table(), x -> new ArrayList<>()).add(k);
            return new ArrayList<>();
        });
        String c = Names.norm(column);
        if (!cols.contains(c)) cols.add(c);
    }

    public void register(TableKey t, List<String> columns, String source) {
        tables.remove(t);
        List<TableKey> l = byName.get(t.table());
        if (l != null) l.remove(t);
        for (String c : columns) addColumn(t, c);
        if (columns.isEmpty()) addColumnsPlaceholder(t);
        origin.put(t, source);
    }

    private void addColumnsPlaceholder(TableKey t) {
        tables.computeIfAbsent(t, k -> {
            byName.computeIfAbsent(k.table(), x -> new ArrayList<>()).add(k);
            return new ArrayList<>();
        });
    }

    /** 테이블 해석: 정확 일치 → 같은 데이터소스·이름 → 이름만 일치(유일할 때). */
    public TableKey resolve(TableKey t) {
        if (tables.containsKey(t)) return t;
        List<TableKey> cands = byName.getOrDefault(t.table(), List.of());
        List<TableKey> sameDs = cands.stream()
                .filter(c -> c.dataSource().equals(t.dataSource()) && (t.schema().isEmpty() || c.schema().equals(t.schema())))
                .toList();
        if (sameDs.size() == 1) return sameDs.getFirst();
        List<TableKey> sameSchema = cands.stream().filter(c -> c.schema().equals(t.schema())).toList();
        if (sameSchema.size() == 1) return sameSchema.getFirst();
        if (cands.size() == 1 && t.schema().isEmpty()) return cands.getFirst();
        return null;
    }

    public boolean has(TableKey t) {
        return resolve(t) != null;
    }

    public boolean hasColumn(TableKey t, String column) {
        TableKey r = resolve(t);
        return r != null && tables.get(r).contains(Names.norm(column));
    }

    /** 컬럼 목록(순서 유지). 없으면 null. */
    public List<String> columns(TableKey t) {
        TableKey r = resolve(t);
        return r == null ? null : List.copyOf(tables.get(r));
    }

    public String origin(TableKey t) {
        TableKey r = resolve(t);
        return r == null ? null : origin.getOrDefault(r, "catalog");
    }

    public Map<TableKey, List<String>> all() {
        return tables;
    }
}
