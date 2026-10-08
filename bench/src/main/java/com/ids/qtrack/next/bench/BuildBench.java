package com.ids.qtrack.next.bench;

import com.ids.qtrack.next.cli.Indexer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

/** JMH: 인덱스 빌드 시간 (추출 + IR 기록·읽기 + 그래프 빌드 + 스냅샷 기록). */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BuildBench {
    @Param({"50"})
    public int domains;

    Path dir;
    int n;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dir = Files.createTempDirectory("qtrack-jmh-build");
        SyntheticCorpus.generate(dir.resolve("src"), domains);
    }

    @Benchmark
    public Map<String, Object> build() throws Exception {
        return Indexer.run(new Indexer.Config(List.of(dir.resolve("src")), List.of(), null, null, 64, dir.resolve("index" + n++)));
    }
}
