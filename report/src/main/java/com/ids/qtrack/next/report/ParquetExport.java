package com.ids.qtrack.next.report;

import com.ids.qtrack.next.query.QueryResult;
import com.ids.qtrack.next.store.Json;
import com.ids.qtrack.next.store.Kinds;
import com.ids.qtrack.next.store.Snapshot;
import com.ids.qtrack.next.store.SourceLines;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 결과와 집계를 DuckDB + Parquet으로 내보냅니다 (FR-RP-03, 설계서 8장).
 * <pre>
 * impact(query_id, target, type, distance, confidence, implicit, control, path_id)
 * path_step(path_id, seq, node_gid, kind, name, file, line, via, conditions)
 * node(gid, kind, name, method, file, byte_offset, line)   — span.i64의 바이트 오프셋과 표시용 줄
 * </pre>
 */
public final class ParquetExport {
    private ParquetExport() {}

    public static Connection duckdb() throws SQLException {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(e);
        }
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    /** 질의 결과 디렉터리: result.json + impact.parquet + path_step.parquet. */
    public static void write(QueryResult r, Path dir) throws IOException, SQLException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("result.json"), Json.write(r.toJson()));
        try (Connection c = duckdb(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE impact(query_id VARCHAR, query VARCHAR, target VARCHAR, type VARCHAR, distance INTEGER,"
                    + " confidence VARCHAR, implicit BOOLEAN, control BOOLEAN, path_id INTEGER, file VARCHAR, line INTEGER)");
            st.execute("CREATE TABLE path_step(path_id INTEGER, seq INTEGER, node_gid INTEGER, kind VARCHAR, name VARCHAR,"
                    + " file VARCHAR, line INTEGER, via VARCHAR, conditions VARCHAR)");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO impact VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                for (QueryResult.Item i : r.items) {
                    p.setString(1, r.queryId);
                    p.setString(2, r.kind + " " + r.query);
                    p.setString(3, i.target());
                    p.setString(4, i.type());
                    p.setInt(5, i.distance());
                    p.setString(6, i.confidence());
                    p.setBoolean(7, i.implicit());
                    p.setBoolean(8, i.control());
                    p.setInt(9, i.pathId());
                    p.setString(10, i.file());
                    p.setInt(11, i.line());
                    p.addBatch();
                }
                p.executeBatch();
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO path_step VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (QueryResult.Step s : r.steps) {
                    p.setInt(1, s.pathId());
                    p.setInt(2, s.seq());
                    p.setInt(3, s.gid());
                    p.setString(4, s.kind());
                    p.setString(5, s.name());
                    p.setString(6, s.file());
                    p.setInt(7, s.line());
                    p.setString(8, s.via());
                    p.setString(9, s.conditions());
                    p.addBatch();
                }
                p.executeBatch();
            }
            st.execute("COPY impact TO '" + esc(dir.resolve("impact.parquet")) + "' (FORMAT PARQUET)");
            st.execute("COPY path_step TO '" + esc(dir.resolve("path_step.parquet")) + "' (FORMAT PARQUET)");
            if (r.graph != null) writeGraph(c, st, r, dir);
        }
    }

    /** 전체 흐름 (--graph): graph_node / graph_edge Parquet + graph.dot. */
    private static void writeGraph(Connection c, Statement st, QueryResult r, Path dir) throws IOException, SQLException {
        st.execute("CREATE TABLE graph_node(key VARCHAR, gid INTEGER, method VARCHAR, lid INTEGER, kind VARCHAR, name VARCHAR,"
                + " file VARCHAR, line INTEGER, conditions VARCHAR, is_start BOOLEAN, level INTEGER)");
        st.execute("CREATE TABLE graph_edge(from_key VARCHAR, to_key VARCHAR, kind VARCHAR, confidence VARCHAR, label VARCHAR,"
                + " implicit BOOLEAN, control BOOLEAN, expanded_from VARCHAR)");
        try (PreparedStatement p = c.prepareStatement("INSERT INTO graph_node VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            for (QueryResult.GNode n : r.graph.nodes.values()) {
                p.setString(1, n.key());
                p.setInt(2, n.gid());
                p.setString(3, n.method());
                p.setInt(4, n.lid());
                p.setString(5, n.kind());
                p.setString(6, n.name());
                p.setString(7, n.file());
                p.setInt(8, n.line());
                p.setString(9, n.conditions());
                p.setBoolean(10, n.start());
                p.setInt(11, r.graph.level);
                p.addBatch();
            }
            p.executeBatch();
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO graph_edge VALUES (?,?,?,?,?,?,?,?)")) {
            for (QueryResult.GEdge e : r.graph.edges) {
                p.setString(1, e.from());
                p.setString(2, e.to());
                p.setString(3, e.kind());
                p.setString(4, e.conf());
                p.setString(5, e.label());
                p.setBoolean(6, e.implicit());
                p.setBoolean(7, e.control());
                p.setString(8, e.via());
                p.addBatch();
            }
            p.executeBatch();
        }
        st.execute("COPY graph_node TO '" + esc(dir.resolve("graph_node.parquet")) + "' (FORMAT PARQUET)");
        st.execute("COPY graph_edge TO '" + esc(dir.resolve("graph_edge.parquet")) + "' (FORMAT PARQUET)");
        Files.writeString(dir.resolve("graph.dot"), GraphDot.render(r.graph, r.kind + " " + r.query + "  (graph " + r.graph.level + ")"));
    }

    /** 인덱스의 인터페이스·전역 노드 표 (node.parquet). */
    public static void writeNodes(Snapshot g, Path file) throws IOException, SQLException {
        Files.createDirectories(file.getParent());
        try (Connection c = duckdb(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE node(gid INTEGER, kind VARCHAR, name VARCHAR, method VARCHAR, file VARCHAR, byte_offset INTEGER, line INTEGER)");
            SourceLines lines = new SourceLines(g);
            try (PreparedStatement p = c.prepareStatement("INSERT INTO node VALUES (?,?,?,?,?,?,?)")) {
                for (int v = 0; v < g.nodeCount(); v++) {
                    long sp = g.span(v);
                    p.setInt(1, v);
                    p.setString(2, Kinds.NODE_NAMES[g.kind(v)]);
                    p.setString(3, g.name(v));
                    p.setString(4, g.owner(v) >= 0 ? g.methodSig(g.owner(v)) : null);
                    p.setString(5, sp < 0 ? null : lines.file(sp));
                    p.setInt(6, SourceLines.offset(sp));
                    p.setInt(7, lines.line(sp));
                    p.addBatch();
                }
                p.executeBatch();
            }
            st.execute("COPY node TO '" + esc(file) + "' (FORMAT PARQUET)");
        }
    }

    static String esc(Path p) {
        return p.toAbsolutePath().toString().replace("'", "''");
    }
}
