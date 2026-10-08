// 골든 테스트 코퍼스와 정답 경로 (VR-02~04, 설계서 11장)
dependencies {
    testImplementation(project(":cli"))
    testImplementation(project(":extract-java"))
    testImplementation(project(":graph-build"))
    testImplementation(project(":query"))
    testImplementation(libs.snakeyaml)
}

tasks.withType<Test>().configureEach {
    systemProperty("golden.root", projectDir.resolve("cases").absolutePath)
    systemProperty("golden.rules", projectDir.resolve("rules").absolutePath)
    systemProperty("golden.out", layout.buildDirectory.dir("golden").get().asFile.absolutePath)
    inputs.dir(projectDir.resolve("cases"))
    inputs.dir(projectDir.resolve("rules"))
}
