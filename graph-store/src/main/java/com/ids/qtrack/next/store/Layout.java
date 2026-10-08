package com.ids.qtrack.next.store;

/** 스냅샷 디렉터리 레이아웃 (설계서 5.6). */
public final class Layout {
    private Layout() {}

    public static final int SCHEMA_VERSION = 1;
    public static final String META = "meta.json";
    public static final String STRINGS = "strings.dict";
    public static final String FILES = "files.tsv";
    public static final String NODES = "nodes";
    public static final String METHODS = "methods";
    public static final String IFACE = "iface";
    public static final String L2 = "l2";
    public static final String IDX = "idx";
    public static final String DELTA = "delta";
    public static final String SUMMARY_TBL = "summary.tbl";
    public static final String CHUNKS_IDX = "chunks.idx";
    public static final String CHUNKS_BIN = "chunks.bin";
    public static final String COLUMN_IDX = "column.idx";
    public static final String ENDPOINT_IDX = "endpoint.idx";
    public static final String ENDPOINT_PARAM_IDX = "endpoint_param.idx";
    public static final String METHOD_IDX = "method.idx";
    public static final String CALL = "call";
}
