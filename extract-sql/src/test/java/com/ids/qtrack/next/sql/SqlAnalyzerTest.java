package com.ids.qtrack.next.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ids.qtrack.next.catalog.Catalog;
import com.ids.qtrack.next.catalog.CatalogLoader;
import com.ids.qtrack.next.catalog.DataSourceMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {
    static Catalog catalog() {
        Catalog c = Catalog.empty();
        CatalogLoader.loadDdl(c, """
                CREATE TABLE ORDERS (ORDER_ID NUMBER, CUST_ID VARCHAR2(10), AMOUNT NUMBER, STATUS CHAR(1));
                CREATE TABLE CUSTOMER (CUST_ID VARCHAR2(10), CUST_NAME VARCHAR2(50), CUST_GRADE CHAR(1));
                CREATE TABLE ORDER_HIST (ORDER_ID NUMBER, CUST_ID VARCHAR2(10), AMOUNT NUMBER);
                """, DataSourceMap.defaults());
        return c;
    }

    static SqlResult run(Catalog c, String sql) throws Exception {
        return new SqlAnalyzer(c, DataSourceMap.defaults(), "x").analyze(sql);
    }

    static Set<String> binds(SqlResult r, int slot) {
        return r.binds.getOrDefault(slot, List.of()).stream()
                .map(b -> b.column() + "@" + b.clause() + "/" + b.column().conf()).collect(Collectors.toSet());
    }

    @Test
    void appendixExample() throws Exception {
        SqlResult r = run(Catalog.empty(), "SELECT * FROM ORDERS WHERE CUST_ID = :__b0");
        assertEquals(Set.of("ORDERS.CUST_ID@2/0"), binds(r, 0));
        assertEquals("ORDERS.*", r.outputs.getFirst().sources().getFirst().toString());
    }

    @Test
    void unqualifiedWithCatalog() throws Exception {
        SqlResult r = run(catalog(), """
                SELECT o.ORDER_ID, CUST_NAME FROM ORDERS o, CUSTOMER c
                 WHERE o.CUST_ID = c.CUST_ID(+) AND CUST_GRADE = :__b1 AND AMOUNT > :__b2""");
        assertEquals(Set.of("CUSTOMER.CUST_GRADE@2/1"), binds(r, 1));
        assertEquals(Set.of("ORDERS.AMOUNT@2/1"), binds(r, 2));
        assertEquals("CUSTOMER.CUST_NAME", r.outputs.get(1).sources().getFirst().toString());
    }

    @Test
    void unqualifiedWithoutCatalogIsHeuristicToAllCandidates() throws Exception {
        SqlResult r = run(Catalog.empty(),
                "SELECT 1 FROM ORDERS o JOIN CUSTOMER c ON o.CUST_ID = c.CUST_ID WHERE CUST_GRADE = :__b0");
        assertEquals(Set.of("ORDERS.CUST_GRADE@2/2", "CUSTOMER.CUST_GRADE@2/2"), binds(r, 0));
    }

    @Test
    void inlineViewStarAndCte() throws Exception {
        SqlResult r = run(Catalog.empty(), """
                WITH G AS (SELECT CUST_ID, CUST_GRADE AS GRADE FROM CUSTOMER)
                SELECT * FROM (SELECT v.CUST_ID AS CID, AMOUNT FROM ORDERS v) x, G
                 WHERE x.CID = G.CUST_ID AND GRADE = :__b0""");
        assertEquals(Set.of("CUSTOMER.CUST_GRADE@2/0"), binds(r, 0));
        List<String> names = r.outputs.stream().map(SqlResult.Output::name).toList();
        assertEquals(List.of("CID", "AMOUNT", "CUST_ID", "GRADE"), names);
    }

    @Test
    void baseStarUsesCatalogOrder() throws Exception {
        SqlResult r = run(catalog(), "SELECT * FROM CUSTOMER");
        assertEquals(List.of("CUST_ID", "CUST_NAME", "CUST_GRADE"),
                r.outputs.stream().map(SqlResult.Output::name).toList());
        assertEquals(1, r.outputs.getFirst().sources().getFirst().conf());
    }

    @Test
    void insertSelectWithoutColumnListUsesCatalogOrder() throws Exception {
        SqlResult r = run(catalog(),
                "INSERT INTO ORDER_HIST SELECT ORDER_ID, CUST_ID, AMOUNT * 2 FROM ORDERS WHERE STATUS = 'C'");
        Set<String> d = r.derives.stream().filter(x -> !x.implicit())
                .map(x -> x.target() + "<-" + x.source() + ":" + x.derive()).collect(Collectors.toSet());
        assertEquals(Set.of("ORDER_HIST.ORDER_ID<-ORDERS.ORDER_ID:0", "ORDER_HIST.CUST_ID<-ORDERS.CUST_ID:0",
                "ORDER_HIST.AMOUNT<-ORDERS.AMOUNT:1"), d);
        assertTrue(r.derives.stream().anyMatch(x -> x.implicit() && x.source().toString().equals("ORDERS.STATUS")));
    }

    @Test
    void mergeAndCtas() throws Exception {
        SqlResult m = run(catalog(), """
                MERGE INTO CUSTOMER t USING (SELECT CUST_ID, MAX(AMOUNT) MX FROM ORDERS GROUP BY CUST_ID) s
                   ON (t.CUST_ID = s.CUST_ID)
                 WHEN MATCHED THEN UPDATE SET t.CUST_GRADE = CASE WHEN s.MX > :__b0 THEN 'A' ELSE 'B' END
                 WHEN NOT MATCHED THEN INSERT (CUST_ID, CUST_GRADE) VALUES (s.CUST_ID, 'C')""");
        Set<String> d = m.derives.stream().map(x -> x.target() + "<-" + x.source() + (x.implicit() ? "(i)" : ""))
                .collect(Collectors.toSet());
        assertTrue(d.contains("CUSTOMER.CUST_GRADE<-ORDERS.AMOUNT"), d.toString());
        assertTrue(d.contains("CUSTOMER.CUST_ID<-ORDERS.CUST_ID"), d.toString());
        assertTrue(d.contains("CUSTOMER.CUST_GRADE<-ORDERS.CUST_ID(i)"), d.toString());
        SqlResult c = run(catalog(), "CREATE TABLE VIP AS SELECT CUST_ID ID, CUST_NAME FROM CUSTOMER WHERE CUST_GRADE='A'");
        assertEquals(List.of("ID", "CUST_NAME"), c.createdColumns);
        assertTrue(c.derives.stream().anyMatch(x -> x.target().toString().equals("VIP.ID")
                && x.source().toString().equals("CUSTOMER.CUST_ID")));
    }

    @Test
    void oracleSyntax() throws Exception {
        run(catalog(), "SELECT /*+ INDEX(o IX1) */ LEVEL, o.ORDER_ID FROM ORDERS o START WITH o.ORDER_ID = :__b0 "
                + "CONNECT BY PRIOR o.ORDER_ID = o.CUST_ID");
        run(catalog(), "SELECT * FROM (SELECT CUST_ID, STATUS, AMOUNT FROM ORDERS) "
                + "PIVOT (SUM(AMOUNT) FOR STATUS IN ('A' AS A, 'C' AS C))");
        SqlResult u = run(catalog(), "UPDATE ORDERS SET AMOUNT = (SELECT MAX(h.AMOUNT) FROM ORDER_HIST h "
                + "WHERE h.ORDER_ID = ORDERS.ORDER_ID) WHERE CUST_ID = :__b3");
        assertTrue(u.derives.stream().anyMatch(x -> x.target().toString().equals("ORDERS.AMOUNT")
                && x.derive() == SqlAnalyzer.AGG));
        assertEquals(Set.of("ORDERS.CUST_ID@2/0"), binds(u, 3));
    }
}
