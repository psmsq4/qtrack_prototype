package com.ids.qtrack.next.mybatis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ids.qtrack.next.catalog.Catalog;
import com.ids.qtrack.next.catalog.DataSourceMap;
import com.ids.qtrack.next.ir.EdgeKind;
import com.ids.qtrack.next.ir.MethodIR;
import com.ids.qtrack.next.ir.NodeKind;
import com.ids.qtrack.next.sql.PreparedSql;
import com.ids.qtrack.next.sql.SqlMethodFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisExtractorTest {
    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.x.OrderMapper">
              <sql id="cols">ORDER_ID, CUST_ID, AMOUNT</sql>
              <select id="select" resultType="com.x.Order">
                SELECT <include refid="cols"/> FROM ORDERS WHERE CUST_ID = #{id}
              </select>
              <select id="search" resultType="com.x.Order">
                SELECT <include refid="cols"/> FROM ORDERS
                <where>
                  <if test="custId != null">AND CUST_ID = #{custId}</if>
                  <if test="status != null">AND STATUS = #{status}</if>
                  <if test="ids != null">AND ORDER_ID IN
                    <foreach collection="ids" item="oid" open="(" close=")" separator=",">#{oid}</foreach>
                  </if>
                </where>
                <choose>
                  <when test="sort == 'amt'">ORDER BY AMOUNT</when>
                  <otherwise>ORDER BY ${sortCol}</otherwise>
                </choose>
              </select>
              <update id="updateGrade" parameterType="com.x.Customer">
                UPDATE CUSTOMER <set><if test="custGrade != null">CUST_GRADE = #{custGrade},</if></set>
                WHERE CUST_ID = #{custId}
              </update>
              <select id="huge">
                SELECT 1 FROM ORDERS <where>
                <if test="a">AND A=#{a}</if><if test="b">AND B=#{b}</if><if test="c">AND C=#{c}</if>
                <if test="d">AND D=#{d}</if><if test="e">AND E=#{e}</if><if test="f">AND F=#{f}</if>
                <if test="g">AND G=#{g}</if></where>
              </select>
            </mapper>
            """;

    @Test
    void extractsAndExpands(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("OrderMapper.xml");
        Files.writeString(f, XML);
        MyBatisExtractor ex = new MyBatisExtractor(64);
        Map<String, PreparedSql> st = ex.extract(f, 3).stream()
                .collect(Collectors.toMap(PreparedSql::name, Function.identity()));
        assertEquals(4, st.size());

        PreparedSql search = st.get("search");
        assertTrue(search.dynamic());
        assertFalse(search.capped());
        assertEquals(16, search.variants().size());                          // 2*2*2 * choose 2
        assertEquals(List.of("custId", "status", "ids", "sortCol"), search.params());

        PreparedSql huge = st.get("huge");
        assertTrue(huge.capped());                                            // 2^7 = 128 > 64
        assertEquals(1, huge.variants().size());

        SqlMethodFactory fac = new SqlMethodFactory(Catalog.empty(), DataSourceMap.defaults());
        MethodIR sel = fac.build(st.get("select"));
        // 부록 A: FORMAL_IN id, BIND #{id}, FORMAL_OUT 결과행, BIND → ORDERS.CUST_ID (WHERE)
        assertEquals(List.of(NodeKind.FORMAL_IN, NodeKind.FORMAL_OUT, NodeKind.BIND),
                sel.getNodesList().stream().map(n -> n.getKind()).toList());
        assertTrue(sel.getEdgesList().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO && e.getDstExt() > 0
                && sel.getExternals(e.getDstExt() - 1).getColumn().getColumn().equals("CUST_ID")
                && e.getClauseValue() == 2));
        assertTrue(sel.getEdgesList().stream().anyMatch(e -> e.getKind() == EdgeKind.LOCAL_FLOW && e.getImplicit()));

        MethodIR s = fac.build(search);
        assertEquals(0, s.getSql().getParseFailures());
        assertTrue(s.getNodesList().stream().anyMatch(n -> n.getKind() == NodeKind.UNKNOWN && n.getName().equals("${sortCol}")));

        MethodIR up = fac.build(st.get("updateGrade"));
        // DTO parameterType: FIELD(com.x.Customer.custGrade) ─LOAD→ #{custGrade}
        assertTrue(up.getEdgesList().stream().anyMatch(e -> e.getKind() == EdgeKind.LOAD
                && up.getExternals(e.getSrcExt() - 1).getField().equals("com.x.Customer.custGrade")));
        assertTrue(up.getEdgesList().stream().anyMatch(e -> e.getKind() == EdgeKind.MAPS_TO && e.getDstExt() > 0
                && up.getExternals(e.getDstExt() - 1).getColumn().getColumn().equals("CUST_GRADE") && e.getClauseValue() == 3));
    }
}
