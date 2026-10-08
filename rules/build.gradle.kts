// Tier D 규칙 엔진: YAML 로더 + tree-sitter query 실행, 기본 Rule Pack (설계서 3.3, FR-IN-06/08)
dependencies {
    api(libs.jtreesitter)
    implementation(libs.snakeyaml)
}
