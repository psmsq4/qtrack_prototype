package com.ids.qtrack.next.catalog;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 데이터소스 매핑 (설계서 3.5 테이블 식별, --datasource-map). Mapper/DAO 패키지 → (데이터소스, 기본 스키마).
 * <pre>
 * default: { dataSource: DEFAULT, schema: APP }
 * mappings:
 *   - package: com.x.billing
 *     dataSource: BILLING
 *     schema: BILL
 * </pre>
 */
public final class DataSourceMap {
    public static final String DEFAULT = "DEFAULT";

    public record Entry(String prefix, String dataSource, String schema) {}

    private final Entry def;
    private final List<Entry> entries;

    public DataSourceMap(Entry def, List<Entry> entries) {
        this.def = def;
        this.entries = new ArrayList<>(entries);
        this.entries.sort(Comparator.comparingInt((Entry e) -> e.prefix.length()).reversed());
    }

    public static DataSourceMap defaults() {
        return new DataSourceMap(new Entry("", DEFAULT, ""), List.of());
    }

    @SuppressWarnings("unchecked")
    public static DataSourceMap load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file)) {
            Map<String, Object> y = new Yaml().load(r);
            Map<String, Object> d = (Map<String, Object>) y.getOrDefault("default", Map.of());
            Entry def = new Entry("", str(d.get("dataSource"), DEFAULT), str(d.get("schema"), ""));
            List<Entry> list = new ArrayList<>();
            for (Map<String, Object> m : (List<Map<String, Object>>) y.getOrDefault("mappings", List.of()))
                list.add(new Entry(str(m.get("package"), ""), str(m.get("dataSource"), def.dataSource),
                        str(m.get("schema"), def.schema)));
            return new DataSourceMap(def, list);
        }
    }

    private static String str(Object o, String d) {
        return o == null ? d : o.toString();
    }

    /** 클래스/namespace FQN에 해당하는 (데이터소스, 기본 스키마). */
    public Entry forOwner(String fqn) {
        if (fqn != null)
            for (Entry e : entries) if (fqn.startsWith(e.prefix)) return e;
        return def;
    }

    /** 스키마가 생략된 참조를 데이터소스의 기본 스키마로 정규화합니다. */
    public TableKey normalize(String ownerFqn, String schema, String table) {
        Entry e = forOwner(ownerFqn);
        String s = schema == null || schema.isEmpty() ? e.schema : schema;
        return new TableKey(e.dataSource, s, table);
    }
}
