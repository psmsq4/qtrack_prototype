package com.ids.qtrack.next.store;

import java.util.List;

/** 노드·간선 종류와 속성 상수. 값은 IR enum 번호와 같습니다 (설계서 4장). */
public final class Kinds {
    private Kinds() {}

    // NodeKind
    public static final int FORMAL_IN = 0, FORMAL_OUT = 1, ACTUAL_IN = 2, ACTUAL_OUT = 3, LOCAL = 4, BIND = 5,
            COLUMN = 6, FIELD = 7, ENDPOINT = 8, UNKNOWN = 9, PREDICATE = 10, FIELD_LOAD = 11, FIELD_STORE = 12;
    public static final String[] NODE_NAMES = {"FORMAL_IN", "FORMAL_OUT", "ACTUAL_IN", "ACTUAL_OUT", "LOCAL", "BIND",
            "COLUMN", "FIELD", "ENDPOINT", "UNKNOWN", "PREDICATE", "FIELD_LOAD", "FIELD_STORE"};

    /** LOCAL과 PREDICATE는 L2(chunk 로컬 lid)에만 존재합니다 (D-01). */
    public static boolean isInterface(int nodeKind) {
        return nodeKind != LOCAL && nodeKind != PREDICATE;
    }

    /** 전역 노드: 메서드에 속하지 않고 gid 공간 맨 뒤 구간에 놓입니다. */
    public static boolean isGlobal(int nodeKind) {
        return nodeKind == COLUMN || nodeKind == FIELD || nodeKind == ENDPOINT;
    }

    // EdgeKind
    public static final int DEF_USE = 0, LOCAL_FLOW = 1, ARG_IN = 2, RET_OUT = 3, SUMMARY = 4, STORE = 5, LOAD = 6,
            BIND_TO = 7, MAPS_TO = 8, COL_DERIVES = 9, CONTROL = 10, CONTROL_FLOW = 11;
    public static final String[] EDGE_NAMES = {"DEF_USE", "LOCAL_FLOW", "ARG_IN", "RET_OUT", "SUMMARY", "STORE", "LOAD",
            "BIND_TO", "MAPS_TO", "COL_DERIVES", "CONTROL", "CONTROL_FLOW"};
    /** 상주 간선 종류 (설계서 5.3, D-05: STORE/LOAD 포함). */
    public static final List<Integer> RESIDENT = List.of(LOCAL_FLOW, ARG_IN, RET_OUT, SUMMARY, STORE, LOAD, BIND_TO,
            MAPS_TO, COL_DERIVES, CONTROL_FLOW);

    // Confidence: 값이 작을수록 확정적 (D-07)
    public static final int EXACT = 0, RESOLVED = 1, HEURISTIC = 2;
    public static final String[] CONF_NAMES = {"EXACT", "RESOLVED", "HEURISTIC"};

    // Clause
    public static final int CL_NONE = 0, CL_SELECT_ITEM = 1, CL_WHERE = 2, CL_SET = 3, CL_INSERT_VALUE = 4,
            CL_JOIN_ON = 5, CL_GROUP_ORDER = 6;
    public static final String[] CLAUSE_NAMES = {"NONE", "SELECT_ITEM", "WHERE", "SET", "INSERT_VALUE", "JOIN_ON",
            "GROUP_ORDER"};

    // BranchLabel
    public static final int L_TRUE = 0, L_FALSE = 1, L_CASE = 2, L_DEFAULT = 3, L_EXCEPTION = 4;
    public static final String[] LABEL_NAMES = {"TRUE", "FALSE", "CASE", "DEFAULT", "EXCEPTION"};

    // 간선 flags 비트 (설계서 5.3)
    public static final int F_IMPLICIT = 1, F_CONTROL = 2;

    public static int edgeKind(String name) {
        for (int i = 0; i < EDGE_NAMES.length; i++) if (EDGE_NAMES[i].equals(name)) return i;
        throw new IllegalArgumentException("unknown edge kind " + name);
    }

    public static int conf(String name) {
        for (int i = 0; i < CONF_NAMES.length; i++) if (CONF_NAMES[i].equalsIgnoreCase(name)) return i;
        throw new IllegalArgumentException("unknown confidence " + name);
    }
}
