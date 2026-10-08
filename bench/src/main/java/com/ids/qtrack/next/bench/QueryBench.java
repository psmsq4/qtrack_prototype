package com.ids.qtrack.next.bench;

import com.ids.qtrack.next.cli.Indexer;
import com.ids.qtrack.next.query.QueryEngine;
import com.ids.qtrack.next.query.QueryResult;
import com.ids.qtrack.next.store.Snapshot;
import com.ids.qtrack.next.store.SortedIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

/** JMH: 질의 지연 (설계서 11장 p50/p95/p99는 SampleTime 모드 결과에서 읽습니다). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class QueryBench {
    @Param({"100"})
    public int domains;

    Snapshot g;
    QueryEngine qe;
    List<String> cols = new ArrayList<>(), params = new ArrayList<>(), methods = new ArrayList<>();
    int i;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        Path dir = Files.createTempDirectory("qtrack-jmh");
        SyntheticCorpus.generate(dir.resolve("src"), domains);
        Indexer.run(new Indexer.Config(List.of(dir.resolve("src")), List.of(), null, null, 64, dir.resolve("index")));
        g = Snapshot.open(dir.resolve("index"));
        qe = new QueryEngine(g);
        for (SortedIndex.Entry e : g.columnIdx.all())
            if (!e.key().contains(":") && e.key().chars().filter(c -> c == '.').count() == 1 && !e.key().endsWith(".*")) cols.add(e.key());
        for (SortedIndex.Entry e : g.endpointParamIdx.all()) if (!e.key().startsWith("*")) params.add(e.key());
        for (SortedIndex.Entry e : g.methodIdx.all()) if (e.key().contains("(") && e.key().contains("Service")) methods.add(e.key());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        g.close();
    }

    @Benchmark
    public QueryResult q1Column() {
        return qe.columnImpact(cols.get(i++ % cols.size()), QueryEngine.Options.defaults());
    }

    @Benchmark
    public QueryResult q2Flow() {
        String k = params.get(i++ % params.size());
        return qe.paramFlow(k.substring(0, k.indexOf('#')), k.substring(k.indexOf('#') + 1), QueryEngine.Options.defaults());
    }

    @Benchmark
    public QueryResult q3Method() {
        return qe.methodImpact(methods.get(i++ % methods.size()), QueryEngine.Options.defaults());
    }
}
