package com.ids.qtrack.next.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 규칙이 한 파일에서 만든 사실(fact). 노드는 시작 바이트로 식별합니다.
 * emit 종류: endpoint, endpointParam, classAttr, bean, inject, mapper, sql, jdbc (설계서 3.3 emit 종류 제안 + sql/jdbc).
 */
public final class Facts {
    public record Endpoint(int method, String httpMethod, String path, String ruleId) {}

    public record EndpointParam(int param, String name, String source, String ruleId) {}

    public record Sql(int method, String text, String type, String ruleId) {}

    public record Jdbc(int call, int sqlArg, int bindFrom, String ruleId) {}

    public record Inject(int target, String qualifier, String ruleId) {}

    public final List<Endpoint> endpoints = new ArrayList<>();
    public final Map<Integer, EndpointParam> endpointParams = new LinkedHashMap<>();
    public final Map<Integer, Map<String, String>> classAttrs = new HashMap<>();
    public final Map<Integer, String> beans = new LinkedHashMap<>();
    public final Set<Integer> mappers = new LinkedHashSet<>();
    public final Map<Integer, Inject> injects = new LinkedHashMap<>();
    public final Map<Integer, Sql> sqls = new LinkedHashMap<>();
    public final Map<Integer, Jdbc> jdbcs = new LinkedHashMap<>();
    /** 규칙별 emit 횟수 (규칙 커버리지 집계). */
    public final Map<String, Integer> ruleHits = new TreeMap<>();

    void hit(String rule) {
        ruleHits.merge(rule, 1, Integer::sum);
    }

    /** 골든 테스트용 정규화 표현 (VR-03). 바이트 위치 대신 원문 줄 번호를 씁니다. */
    public List<String> canonical(String source) {
        List<String> out = new ArrayList<>();
        for (Endpoint e : endpoints) out.add("endpoint " + e.httpMethod + " " + e.path + " @L" + line(source, e.method));
        for (EndpointParam p : endpointParams.values()) out.add("endpointParam " + p.name + " " + p.source + " @L" + line(source, p.param));
        classAttrs.forEach((k, v) -> v.forEach((a, b) -> out.add("classAttr " + a + "=" + b + " @L" + line(source, k))));
        beans.forEach((k, v) -> out.add("bean " + v + " @L" + line(source, k)));
        for (int m : mappers) out.add("mapper @L" + line(source, m));
        injects.forEach((k, v) -> out.add("inject " + (v.qualifier == null ? "" : v.qualifier) + " @L" + line(source, k)));
        sqls.forEach((k, v) -> out.add("sql " + v.type + " " + v.text.replaceAll("\\s+", " ").trim() + " @L" + line(source, k)));
        jdbcs.forEach((k, v) -> out.add("jdbc " + v.sqlArg + " " + v.bindFrom + " @L" + line(source, k)));
        out.sort(null);
        return out;
    }

    static int line(String src, int byteOffset) {
        byte[] b = src.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int line = 1;
        for (int i = 0; i < Math.min(byteOffset, b.length); i++) if (b[i] == '\n') line++;
        return line;
    }
}
