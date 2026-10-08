package com.ids.qtrack.next.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsrTest {
    /** 설계서 부록 A: ARG_IN 3→6, 7→10 (노드 14개). */
    @Test
    void appendixArgInCsr() {
        EdgeList e = new EdgeList(new String[]{"conf"});
        e.add(3, 6, Kinds.RESOLVED);
        e.add(7, 10, Kinds.EXACT);
        CsrData fwd = CsrData.build(14, e, false);
        CsrData rev = CsrData.build(14, e, true);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2}, fwd.off);
        assertArrayEquals(new int[]{6, 10}, fwd.tgt);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2}, rev.off);
        assertArrayEquals(new int[]{3, 7}, rev.tgt);
    }

    /** D-02: 재배치 후에도 간선과 신뢰도·속성의 짝이 유지된다. */
    @Test
    void attributesFollowPermutation(@TempDir Path dir) {
        EdgeList e = new EdgeList(new String[]{"conf", "clause"}, "caseVal");
        e.add(2, 0, new int[]{Kinds.HEURISTIC, 3}, 30);
        e.add(0, 1, new int[]{Kinds.EXACT, 1}, 10);
        e.add(1, 2, new int[]{Kinds.RESOLVED, 2}, 20);
        e.add(0, 2, new int[]{Kinds.HEURISTIC, 4}, 40);
        CsrData.build(3, e, false).write(dir, "X.fwd");
        try (Arena a = Arena.ofConfined()) {
            Csr c = Csr.open(dir, "X.fwd", a);
            assertEquals(0, c.begin(0));
            assertEquals(2, c.end(0));
            assertEquals(1, c.target(0));
            assertEquals(Kinds.EXACT, c.conf(0));
            assertEquals(1, c.clause(0));
            assertEquals(2, c.target(1));
            assertEquals(Kinds.HEURISTIC, c.conf(1));
            assertEquals(4, c.clause(1));
            assertEquals(0, c.target(3));
            assertEquals(Kinds.HEURISTIC, c.conf(3));
        }
    }

    @Test
    void sortedIndexAndDict(@TempDir Path dir) {
        new SortedIndex.Builder().put("ORDERS.CUST_ID", 5).put("CUSTOMER.CUST_GRADE", 9).put("ORDERS.CUST_ID", 7)
                .put("ORDERS.*", 3).write(dir.resolve("c.idx"));
        StringDict.Builder sb = new StringDict.Builder();
        sb.intern("가나다");
        sb.intern("custId");
        sb.write(dir.resolve("s.dict"));
        try (Arena a = Arena.ofConfined()) {
            SortedIndex idx = SortedIndex.open(dir.resolve("c.idx"), a);
            assertArrayEquals(new int[]{5, 7}, idx.lookup("ORDERS.CUST_ID"));
            assertEquals(3, idx.prefix("ORDERS.").size());
            StringDict d = StringDict.open(dir.resolve("s.dict"), a);
            assertEquals("가나다", d.get(0));
            assertEquals("custId", d.get(1));
        }
    }
}
