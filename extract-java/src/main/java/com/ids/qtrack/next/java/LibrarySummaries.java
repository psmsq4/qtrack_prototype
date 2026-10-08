package com.ids.qtrack.next.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/** JDK 내장 라이브러리 요약표 (FR-IN-07). */
public final class LibrarySummaries {
    /** recv/args/argIdx → 결과, mut = 인자 → 수신 객체. known=false면 HEURISTIC 기본 흐름. */
    public record Summary(boolean recv, boolean allArgs, int argIdx, boolean mut, boolean known) {
        public static final Summary UNKNOWN = new Summary(true, true, -1, false, false);
    }

    private final Map<String, Map<String, Summary>> table = new HashMap<>();

    @SuppressWarnings("unchecked")
    public static LibrarySummaries load() {
        LibrarySummaries s = new LibrarySummaries();
        try (InputStream in = LibrarySummaries.class.getResourceAsStream("/library-summaries.yaml")) {
            Map<String, Map<String, Object>> y = new Yaml(new LoaderOptions()).load(in);
            y.forEach((type, methods) -> {
                Map<String, Summary> m = new HashMap<>();
                methods.forEach((name, flow) -> m.put(name, parse(String.valueOf(flow))));
                s.table.put(type, m);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return s;
    }

    private static Summary parse(String flow) {
        boolean recv = false, args = false, mut = false;
        int idx = -1;
        for (String t : flow.split(",")) {
            t = t.trim();
            switch (t) {
                case "recv" -> recv = true;
                case "args" -> args = true;
                case "mut" -> mut = true;
                case "none" -> { }
                default -> {
                    if (t.startsWith("arg")) idx = Integer.parseInt(t.substring(3));
                }
            }
        }
        return new Summary(recv, args, idx, mut, true);
    }

    /** 타입(FQN 또는 단순 이름)과 메서드 이름으로 요약 조회. 타입을 모르면 "*" 와 메서드 이름이 유일한 타입을 봅니다. */
    public Summary lookup(String type, String method) {
        String simple = type == null ? null : type.substring(type.lastIndexOf('.') + 1).replace("[]", "");
        if (simple != null && table.containsKey(simple)) {
            Summary s = table.get(simple).get(method);
            return s == null ? Summary.UNKNOWN : s;
        }
        if (table.get("*").containsKey(method)) return table.get("*").get(method);
        if (type == null || type.isEmpty()) {
            Summary found = null;
            for (Map<String, Summary> m : table.values()) {
                Summary s = m.get(method);
                if (s == null) continue;
                if (found != null && !found.equals(s)) return Summary.UNKNOWN;
                found = s;
            }
            if (found != null) return new Summary(found.recv, found.allArgs, found.argIdx, found.mut, false);
        }
        return Summary.UNKNOWN;
    }
}
