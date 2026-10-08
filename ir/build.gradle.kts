// 추출기 ↔ 엔진 계약: protobuf IR (FR-IR-01)
plugins { alias(libs.plugins.protobuf) }

val protocVersion: String = libs.versions.protobufJava.get()

dependencies {
    api(libs.protobuf.java)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protocVersion" }
}

tasks.withType<JavaCompile>().configureEach {
    // 생성 코드 경고는 무시
    options.compilerArgs.removeAll { it.startsWith("-Xlint") }
}
