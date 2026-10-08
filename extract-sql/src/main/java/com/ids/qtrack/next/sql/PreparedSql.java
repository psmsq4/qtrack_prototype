package com.ids.qtrack.next.sql;

import com.ids.qtrack.next.ir.Span;
import java.util.List;
import java.util.Map;

/**
 * 전개가 끝난 SQL 문 하나 (MyBatis XML, MyBatis 어노테이션, Java 내장 SQL 공통).
 *
 * @param variants    동적 SQL 전개 변형들. 바인드는 {@code :__b<slot>}로 치환되어 있습니다.
 * @param params      합성 메서드 FORMAL_IN 이름 (바인드의 루트 이름 또는 _parameter)
 * @param resultProps 결과 컬럼(대문자) → DTO 프로퍼티 (resultMap)
 */
public record PreparedSql(
        String signature,
        String className,
        String name,
        Span span,
        String statementType,
        String parameterType,
        String resultType,
        Map<String, String> resultProps,
        List<String> params,
        List<SqlMethodBuilder.BindSlot> slots,
        List<String> variants,
        boolean dynamic,
        boolean capped) {}
