// JMH 벤치마크, 합성 코퍼스 생성기, 측정·기준선 스크립트 (VR-05/06, 설계서 11장)
dependencies {
    implementation(project(":cli"))
    implementation(project(":query"))
    implementation(project(":graph-store"))
    implementation(libs.jmh.core)
    annotationProcessor(libs.jmh.annprocess)
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.removeAll { it.startsWith("-Xlint") }
}

val benchDir = layout.buildDirectory.dir("bench")

// 합성 코퍼스로 빌드·질의 측정 → build/bench/report.md (Go/No-Go 표)
tasks.register<JavaExec>("measure") {
    group = "bench"
    description = "합성 코퍼스를 만들어 인덱싱·질의 지연(p50/p95/p99)·메모리를 측정한다. -Pdomains=N -Pqueries=N"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ids.qtrack.next.bench.Measure")
    val domains = (project.findProperty("domains") ?: "200").toString()
    val queries = (project.findProperty("queries") ?: "300").toString()
    val src = (project.findProperty("src") ?: "").toString()
    args(benchDir.get().asFile.absolutePath, domains, queries, src)
    maxHeapSize = "2g"
    jvmArgs("-XX:+UseG1GC")
    if (project.hasProperty("jfr")) jvmArgs("-XX:StartFlightRecording=filename=${benchDir.get().asFile.absolutePath}/measure.jfr,settings=profile")
}

// JMH (플러그인 없이 annotation processor + JavaExec)
tasks.register<JavaExec>("jmh") {
    group = "bench"
    description = "JMH 벤치마크 실행 (QueryBench, BuildBench). -Pjmh.args='…' 로 JMH 옵션 전달"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.openjdk.jmh.Main")
    val extra = (project.findProperty("jmh.args") ?: "-f 1 -wi 2 -i 3 -w 2s -r 3s").toString()
    args(extra.split(" ").filter { it.isNotBlank() } + listOf("-rf", "json", "-rff", benchDir.get().asFile.absolutePath + "/jmh.json"))
    doFirst { benchDir.get().asFile.mkdirs() }
}
